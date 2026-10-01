#!/usr/bin/env bash
# 从「交付根目录里的 Kotlin 源码」重新导出 app 侧补丁（patches/app/0001-anchor-panel.patch）+ 基线。
#
# 用法: tools/export_app_patch.sh --repo <Mishka 仓库>
#
# 过程：在干净仓库上先打旧补丁（拿到入口 UI 改动），再用交付目录里的最新源码覆盖（custom/ 整棵树），
# 于是新补丁 = 入口改动 + 最新源文件；最后把工作区还原干净，并刷新 patches/app/BASELINE.txt。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PATCH_DIR="$ROOT/patches/app"
PATCH="$PATCH_DIR/0001-anchor-panel.patch"
CUSTOM_ROOT_REL="app/src/main/kotlin/top/yukonga/mishka/custom"
FMES_REL="app/src/main/kotlin/top/yukonga/mishka/ui/screen/settings/FileManagerEditorScreen.kt"

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

if [[ -n "$(git -C "$REPO" status --porcelain)" ]]; then
  echo "错误：仓库工作区不干净，先还原（git -C \"$REPO\" checkout -- . && git -C \"$REPO\" clean -fd）" >&2
  exit 1
fi

upstream_commit="$(git -C "$REPO" rev-parse HEAD)"
fmes_before="$(git -C "$REPO" rev-parse "HEAD:$FMES_REL")"

echo "1/5 打旧补丁（取入口 UI 改动）"
if [[ -f "$PATCH" ]]; then
  git -C "$REPO" apply "$PATCH"
else
  echo "   没有旧补丁：假定入口改动已在工作区（首次导出请手工改好入口）" >&2
fi

echo "2/5 用交付目录里的最新源文件覆盖（整棵 custom/ 树：anchor + forms）"
mkdir -p "$REPO/$CUSTOM_ROOT_REL"
cp -r "$ROOT/$CUSTOM_ROOT_REL"/. "$REPO/$CUSTOM_ROOT_REL"/

echo "3/5 导出补丁"
# -f：setup.sh 会把 app/.../custom/ 写进 .git/info/exclude 保持 git status 干净，
# 导出时必须强制入索引，否则补丁里会缺掉整棵源码树。
git -C "$REPO" add -f -A
git -C "$REPO" diff --cached --binary > "$PATCH"
git -C "$REPO" diff --cached --stat | tail -3

echo "4/5 写基线"
fmes_after="$(git -C "$REPO" hash-object "$FMES_REL")"
{
  echo "# app 侧补丁基线（导出时生成）"
  echo "upstream_commit=$upstream_commit"
  echo "patch_file=patches/app/0001-anchor-panel.patch"
  echo "patch_sha256=$(sha256sum "$PATCH" | cut -d' ' -f1)"
  echo "blob_before=$fmes_before $FMES_REL"
  echo "blob_after=$fmes_after $FMES_REL"
  while read -r rel; do
    echo "blob_new=$(git -C "$REPO" hash-object "$rel") $rel"
  done < <(cd "$REPO" && find "$CUSTOM_ROOT_REL" -name '*.kt' | sort)
} > "$PATCH_DIR/BASELINE.txt"

echo "5/5 还原工作区"
git -C "$REPO" reset -q
git -C "$REPO" checkout -- "$FMES_REL"
rm -rf "$REPO/app/src/main/kotlin/top/yukonga/mishka/custom"
if [[ -n "$(git -C "$REPO" status --porcelain)" ]]; then
  echo "警告：还原后工作区仍有改动：" >&2
  git -C "$REPO" status --porcelain >&2
  exit 1
fi
echo "完成：$PATCH"
cat "$PATCH_DIR/BASELINE.txt"
