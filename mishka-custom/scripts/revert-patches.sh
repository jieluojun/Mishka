#!/usr/bin/env bash
# 撤销补丁，把仓库还原到「可以放心 git pull」的干净状态。
#
#   scripts/revert-patches.sh [--repo <Mishka 仓库>] [--all]
#
# 默认只撤 app 侧补丁（还原编辑器入口 + 删掉 custom/ 源码目录），mishka-custom/ 与内核保持不动。
# --all 额外：把 mihomo 子模块还原成上游内核、删掉 go.work / go.work.sum、取消 submodule 忽略设置。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

REPO_ARG=""
ALL=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO_ARG="$2"; shift 2 ;;
    --all)  ALL=1; shift ;;
    -h|--help) sed -n '2,8p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done

DELIVER="$(deliver_root)"
REPO="$(find_repo_root "$REPO_ARG")"
patch="$DELIVER/patches/app/0001-anchor-panel.patch"

step "撤销 app 侧补丁"
reverted=0
if [[ -f "$patch" ]] && git -C "$REPO" apply --check --reverse "$patch" 2>/dev/null; then
  git -C "$REPO" apply --reverse "$patch"
  ok "已反向应用补丁（编辑器入口还原、custom/ 源码目录删除）"
  reverted=1
fi
if [[ $reverted -eq 0 ]]; then
  if [[ -z "$(git -C "$REPO" status --porcelain -- "$CUSTOM_REL" "$FMES_REL")" ]]; then
    ok "没有需要撤销的改动"
  else
    warn "补丁无法整体反向应用（可能被手工改过），改为逐项还原"
    if git -C "$REPO" checkout -- "$FMES_REL" 2>/dev/null; then
      ok "已还原 $FMES_REL"
    fi
    rm -rf "$REPO/$CUSTOM_REL"
    ok "已删除 $CUSTOM_REL/"
  fi
fi
if [[ -n "$(git -C "$REPO" status --porcelain -- "$CUSTOM_REL" "$FMES_REL")" ]]; then
  warn "这两个路径仍有改动，请人工确认："
  git -C "$REPO" status --short -- "$CUSTOM_REL" "$FMES_REL" >&2
fi

if [[ $ALL -eq 1 ]]; then
  step "还原内核与 Go 工作区"
  if [[ -e "$REPO/mihomo" ]]; then
    if git -C "$REPO/mihomo" remote get-url origin 2>/dev/null | grep -q "jieluojun/mihomo"; then
      rm -rf "$REPO/mihomo"
      git -C "$REPO" config --unset submodule.mihomo.ignore 2>/dev/null || true
      git -C "$REPO" submodule sync --quiet mihomo 2>/dev/null || true
      if git -C "$REPO" submodule update --init --quiet mihomo; then
        ok "已还原上游 mihomo 子模块（$(git -C "$REPO/mihomo" rev-parse --short HEAD)）"
      else
        warn "子模块还原失败（离线？）：稍后手动跑 git -C \"$REPO\" submodule update --init mihomo"
      fi
    else
      ok "mihomo/ 不是本定制装的内核，保持原样"
    fi
  fi
  rm -f "$REPO/go.work" "$REPO/go.work.sum"
  ok "已删除 go.work / go.work.sum"
  say ""
  say "仓库现在的状态："
  git -C "$REPO" status --short | sed 's/^/  /' || true
  say "（mishka-custom/ 与 .git/info/exclude 里的登记会保留，下次 setup.sh 直接复用）"
fi

say ""
say "完成。"
