#!/usr/bin/env bash
# 从交付源码重新导出 app 侧补丁（patches/app/0001-anchor-panel.patch）+ 基线。
#
# 用法: tools/export_app_patch.sh --repo <Mishka 仓库>
#
# 流程：在干净仓库上先打稳定的锚点面板 seed，再打订阅页可视化入口迁移 seed，
# 再打主页/代理页修复 + ROOT EBPF seed 与主页外部面板 seed，然后以交付目录中的最新 custom/ 源码覆盖，
# 最终导出完整 app 补丁与逐文件基线。
# 导出物 0001 可能不断更新；必须使用独立 seed，不能把上一次 0001 再作为生成输入。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PATCH_DIR="$ROOT/patches/app"
PATCH="$PATCH_DIR/0001-anchor-panel.patch"
ANCHOR_SEED="$PATCH_DIR/anchor-panel.seed.patch"
ENTRY_SEED="$PATCH_DIR/visual-config-entry.seed.patch"
FIXES_SEED="$PATCH_DIR/home-proxy-root-fixes.seed.patch"
PANEL_SEED="$PATCH_DIR/external-panel.seed.patch"
CUSTOM_ROOT_REL="app/src/main/kotlin/top/yukonga/mishka/custom"

REPO=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    -h|--help) sed -n '2,8p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$REPO" ]] || { echo "错误：必须给 --repo <Mishka 仓库>" >&2; exit 2; }
REPO="$(cd "$REPO" && pwd)"
[[ -d "$REPO/.git" ]] || { echo "错误：$REPO 不是 git 仓库" >&2; exit 2; }
[[ -f "$ANCHOR_SEED" ]] || { echo "错误：缺少锚点面板 seed 补丁：$ANCHOR_SEED" >&2; exit 1; }
[[ -f "$ENTRY_SEED" ]] || { echo "错误：缺少订阅入口迁移 seed 补丁：$ENTRY_SEED" >&2; exit 1; }
[[ -f "$FIXES_SEED" ]] || { echo "错误：缺少主页/代理页修复 + ROOT EBPF seed 补丁：$FIXES_SEED" >&2; exit 1; }
[[ -f "$PANEL_SEED" ]] || { echo "错误：缺少主页外部面板 seed 补丁：$PANEL_SEED" >&2; exit 1; }

if [[ -n "$(git -C "$REPO" status --porcelain)" ]]; then
  echo "错误：仓库工作区不干净，先还原（本脚本只应在基线干净仓库运行）" >&2
  exit 1
fi
if [[ -e "$REPO/$CUSTOM_ROOT_REL" ]]; then
  echo "错误：仓库里已存在 $CUSTOM_ROOT_REL（可能被 .git/info/exclude 忽略），请先移走后再导出" >&2
  exit 1
fi

upstream_commit="$(git -C "$REPO" rev-parse HEAD)"
restore_repo() {
  git -C "$REPO" reset -q >/dev/null 2>&1 || true
  git -C "$REPO" checkout -- . >/dev/null 2>&1 || true
  # seed 可能在 custom/ 之外新增上游文件（如 ui/screen/panel/），untracked 残留要一并清掉
  git -C "$REPO" clean -fdq >/dev/null 2>&1 || true
  rm -rf "$REPO/$CUSTOM_ROOT_REL"
}
trap restore_repo EXIT

echo "1/8 打锚点面板基础 seed（保留原工具栏入口）"
git -C "$REPO" apply "$ANCHOR_SEED"

echo "2/8 打订阅页可视化入口迁移 seed"
git -C "$REPO" apply "$ENTRY_SEED"

echo "3/8 打主页/代理页修复 + ROOT EBPF seed"
git -C "$REPO" apply "$FIXES_SEED"

echo "4/8 打主页外部面板 seed"
git -C "$REPO" apply "$PANEL_SEED"

echo "5/8 用交付目录中的最新 custom/ 源码覆盖"
mkdir -p "$REPO/$CUSTOM_ROOT_REL"
cp -r "$ROOT/$CUSTOM_ROOT_REL"/. "$REPO/$CUSTOM_ROOT_REL"/

echo "6/8 导出完整 app 补丁"
# setup.sh 会将 custom/ 登记到 .git/info/exclude；导出时必须强制入索引。
git -C "$REPO" add -f -A
git -C "$REPO" diff --cached --binary > "$PATCH"
git -C "$REPO" diff --cached --stat | tail -5

echo "7/8 写逐文件基线"
{
  echo "# app 侧补丁基线（导出时生成）"
  echo "upstream_commit=$upstream_commit"
  echo "patch_file=patches/app/0001-anchor-panel.patch"
  echo "patch_sha256=$(sha256sum "$PATCH" | cut -d' ' -f1)"
  while read -r rel; do
    if git -C "$REPO" cat-file -e "HEAD:$rel" 2>/dev/null; then
      echo "blob_after=$(git -C "$REPO" hash-object "$rel") $rel"
    else
      echo "blob_new=$(git -C "$REPO" hash-object "$rel") $rel"
    fi
  done < <(git -C "$REPO" diff --cached --name-only | sort)
} > "$PATCH_DIR/BASELINE.txt"

echo "8/8 还原仓库工作区"
restore_repo
trap - EXIT
if [[ -n "$(git -C "$REPO" status --porcelain)" ]]; then
  echo "警告：还原后工作区仍有改动：" >&2
  git -C "$REPO" status --porcelain >&2
  exit 1
fi
echo "完成：$PATCH"
cat "$PATCH_DIR/BASELINE.txt"
