#!/usr/bin/env bash
# 应用补丁（幂等）：app 侧锚点面板与订阅可视化配置入口补丁；--kernel 时连内核补丁一起打。
#
#   scripts/apply-patches.sh [--repo <Mishka 仓库>] [--kernel] [--kernel-dir <内核目录>]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

REPO_ARG=""
KERNEL=0
KERNEL_DIR_ARG=""
APP_PATCH_REL="patches/app/0001-anchor-panel.patch"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)       REPO_ARG="$2"; shift 2 ;;
    --kernel)     KERNEL=1; shift ;;
    --kernel-dir) KERNEL_DIR_ARG="$2"; shift 2 ;;
    -h|--help)    sed -n '2,5p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done

DELIVER="$(deliver_root)"
REPO="$(find_repo_root "$REPO_ARG")"

step "app 侧补丁"
if app_patch_applied "$REPO"; then
  ok "已应用，跳过"
else
  patch="$DELIVER/$APP_PATCH_REL"
  [[ -f "$patch" ]] || die "找不到补丁 $patch"
  if git -C "$REPO" apply --check "$patch"; then
    git -C "$REPO" apply "$patch"
    ok "已应用 $APP_PATCH_REL"
  elif git -C "$REPO" apply --check --3way "$patch"; then
    git -C "$REPO" apply --3way "$patch"
    warn "3way 合并成功——上游改过 app 入口文件，请确认订阅页、路由和 YAML 编辑器的改动"
  else
    die "补丁打不上（上游 $FMES_REL 可能已变）。试试 git -C \"$REPO\" apply -v --3way $patch 看冲突"
  fi
fi

if [[ $KERNEL -eq 1 ]]; then
  step "内核补丁"
  KERNEL_DIR="${KERNEL_DIR_ARG:-$REPO/mihomo}"
  git -C "$KERNEL_DIR" rev-parse --git-dir >/dev/null 2>&1 || die "$KERNEL_DIR 不是 git 仓库"
  if kernel_patched "$KERNEL_DIR"; then
    ok "已应用，跳过"
  else
    [[ -z "$(git -C "$KERNEL_DIR" status --porcelain)" ]] || die "内核工作区不干净，先还原"
    bash "$DELIVER/tools/verify_mihomo_patches.sh" --kernel-dir "$KERNEL_DIR" >/dev/null \
      || die "内核补丁无法干净应用（内核 commit 与基线不符？）"
    for p in "$DELIVER"/patches/mihomo/[0-9]*.patch; do
      git -C "$KERNEL_DIR" apply "$p"
      ok "已应用 $(basename "$p")"
    done
  fi
fi

say ""
say "完成。撤销：scripts/revert-patches.sh"
