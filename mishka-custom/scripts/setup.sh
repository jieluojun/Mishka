#!/usr/bin/env bash
# 装配 + 应用 app 补丁（幂等）。r14 范围收窄后不再换内核：内核用上游 mihomo 子模块原样构建。
#
# 用法: scripts/setup.sh [--repo <Mishka 仓库>] [--ci]
#
# 步骤：1/3 装配 mishka-custom/（交付目录不在仓库内时复制进去）；
#       2/3 应用 patches/app/0001（已应用则跳过）；
#       3/3 打印下一步命令。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

REPO_ARG=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO_ARG="$2"; shift 2 ;;
    --ci)   shift ;;   # 兼容旧调用（CI 工作流），无额外动作
    -h|--help)     sed -n '2,10p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "未知参数：$1（--help 看用法）" ;;
  esac
done

DELIVER="$(deliver_root)"
REPO="$(find_repo_root "$REPO_ARG")"
CUSTOM_DIR_NAME="mishka-custom"

say "交付目录：$DELIVER"
say "仓库：    $REPO（$(git -C "$REPO" rev-parse --short HEAD)）"

step "1/3 装配 mishka-custom/"
if [[ "$DELIVER" != "$REPO/$CUSTOM_DIR_NAME" ]]; then
  target="$REPO/$CUSTOM_DIR_NAME"
  mkdir -p "$target"
  for d in scripts patches tools init ci app; do
    [[ -e "$DELIVER/$d" ]] || continue
    rm -rf "$target/$d"
    cp -R "$DELIVER/$d" "$target/$d"
    ok "复制 $d/"
  done
  for f in README.md INSTALL.md BUILD.md VERIFY.md CUSTOMIZATION.md FORMS.md FORMS-P1.md FORMS-P2.md; do
    [[ -f "$DELIVER/$f" ]] && cp "$DELIVER/$f" "$target/$f"
  done
  echo "/$CUSTOM_DIR_NAME/" >> "$REPO/.git/info/exclude" 2>/dev/null || true
else
  ok "交付目录已在仓库内"
fi

step "2/3 应用 app 补丁（锚点面板 + 可视化配置）"
PATCH="$REPO/$CUSTOM_DIR_NAME/patches/app/0001-anchor-panel.patch"
[[ -f "$PATCH" ]] || die "找不到 $PATCH"
if git -C "$REPO" apply --check -R "$PATCH" >/dev/null 2>&1; then
  ok "补丁已应用，跳过"
elif git -C "$REPO" apply --check "$PATCH" >/dev/null 2>&1; then
  git -C "$REPO" apply "$PATCH"
  ok "补丁已应用"
else
  die "补丁既不像已应用、也无法干净应用（上游 HEAD 与基线失配？）。基线见 patches/app/BASELINE.txt"
fi

step "3/3 完成"
git -C "$REPO" status --short | sed 's/^/    /' || true
cat <<EOF

  下一步：
    bash $REPO/$CUSTOM_DIR_NAME/scripts/build-release.sh --repo "$REPO"   # 本地出 release APK
    bash $REPO/$CUSTOM_DIR_NAME/tools/verify_app_patch.sh --repo "$REPO"  # 校验 app 补丁可逆
  回滚：
    bash $REPO/$CUSTOM_DIR_NAME/scripts/revert-patches.sh --repo "$REPO"
EOF
