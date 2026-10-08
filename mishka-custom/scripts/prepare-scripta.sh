#!/usr/bin/env bash
# 确保 <仓库>/scripta 是可用源码（Mishka 的 settings.gradle.kts 有 includeBuild("scripta")，缺了构建不了）。
#
#   scripts/prepare-scripta.sh [--repo <仓库>] [--upstream <Mishka 仓库>] [--scripta-url <URL>]
#                              [--pin <commit>] [--force]
#
# 依次尝试三种情况：
#   1. <仓库>/scripta 已是 git 仓库且有内容 → 直接用（给了 --pin 且工作区干净时顺手切到该 commit）
#   2. fork 里有 scripta 子模块条目（gitlink）→ git submodule update --init scripta
#   3. 都没有（例如 fork 是「重新提交」出来的、丢了子模块条目）→ 按上游 pin 的 commit 直接 clone
#
# pin 的取法：--pin > 上游仓库 main 里 scripta 的 gitlink > 内置默认值（Mishka 上游最近的 pin）。
# 全程不改仓库里已有的跟踪文件，必要的话把 /scripta/ 登记进 .git/info/exclude。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

REPO_ARG=""
UPSTREAM="https://github.com/YuKongA/Mishka.git"
SCRIPTA_URL="https://github.com/YuKongA/scripta.git"
PIN=""
FORCE=0
# Mishka 上游里 scripta 子模块指向的 commit（与本包基线 b66e844a 处一致），取不到上游时用它兜底：
FALLBACK_PIN="23820ff3085016a7490b1c1bb1c9dc76168be052"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)        REPO_ARG="$2"; shift 2 ;;
    --upstream)    UPSTREAM="$2"; shift 2 ;;
    --scripta-url) SCRIPTA_URL="$2"; shift 2 ;;
    --pin)         PIN="$2"; shift 2 ;;
    --force)       FORCE=1; shift ;;
    -h|--help)     sed -n '2,16p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done

REPO="$(find_repo_root "$REPO_ARG")"
DIR="$REPO/scripta"

if [[ -d "$DIR" ]] && git -C "$DIR" rev-parse --git-dir >/dev/null 2>&1 && [[ -n "$(ls -A "$DIR" | head -1)" ]]; then
  head_sha="$(git -C "$DIR" rev-parse HEAD)"
  ok "scripta 已就位（$DIR @ ${head_sha:0:8}）"
  if [[ $FORCE -eq 1 && -n "$(git -C "$DIR" status --porcelain)" ]]; then
    warn "scripta 工作区有改动，--force 不处理（请自行处理后重跑）"
  fi
  exit 0
fi

# 情况 2：fork 里有 gitlink
if git -C "$REPO" ls-files -s scripta 2>/dev/null | grep -q '^160000'; then
  say "检测到 scripta 子模块条目，执行 git submodule update --init scripta …"
  git -C "$REPO" submodule sync --quiet scripta 2>/dev/null || true
  if git -C "$REPO" submodule update --init scripta; then
    ok "scripta 子模块已检出（$(git -C "$DIR" rev-parse --short HEAD)）"
    exit 0
  fi
  warn "子模块检出失败（网络或 .gitmodules 配置问题），改用直接 clone"
fi

# 情况 3：直接 clone 到 pin 的 commit
if [[ -z "$PIN" ]]; then
  say "向上游取 scripta 的 pin：$UPSTREAM"
  if git -C "$REPO" fetch --depth=1 --no-tags "$UPSTREAM" main 2>/dev/null; then
    PIN="$(git -C "$REPO" ls-tree FETCH_HEAD scripta 2>/dev/null | awk '{print $3}')"
  fi
  [[ -n "$PIN" ]] || { PIN="$FALLBACK_PIN"; warn "取不到上游 pin，用内置默认值 ${PIN:0:12}"; }
fi
ok "scripta 目标 commit：$PIN"

say "clone $SCRIPTA_URL → $DIR"
rm -rf "$DIR"
git clone --no-checkout --quiet "$SCRIPTA_URL" "$DIR"
if ! git -C "$DIR" checkout --quiet "$PIN" 2>/dev/null; then
  # pin 太新/太旧时退一步：拉全量历史再切
  warn "浅历史里没有 $PIN，拉取完整历史后重试"
  git -C "$DIR" fetch --quiet origin
  git -C "$DIR" checkout --quiet "$PIN" || die "scripta 里找不到 commit $PIN"
fi
ok "scripta 已就位（$DIR @ $(git -C "$DIR" rev-parse --short HEAD)）"

# 这个目录在 fork 里没有 gitlink 条目（git 看不到它），登记 exclude 免得脏 git status
if ! git -C "$REPO" ls-files -s scripta 2>/dev/null | grep -q '^160000'; then
  exclude_path "$REPO" "/scripta/"
  ok "已把 /scripta/ 登记进 .git/info/exclude（它不在你的子模块表里）"
fi
say ""
say "提醒：本地构建需要它；如果之后想让 fork 恢复成正常子模块，见 mishka-custom/INSTALL.md「scripta 子模块」一节。"
