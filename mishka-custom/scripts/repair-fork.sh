#!/usr/bin/env bash
# 修复「重新提交/网页上传」造成的 fork 结构损伤。这类 fork 会丢三样东西，每一样都会让构建失败：
#   1. 子模块指针（scripta / mihomo 变成普通空目录甚至消失）→ CI 的 submodule 步骤 / includeBuild("scripta") 失败
#   2. gradlew 的可执行位（变成 644）→ ./gradlew: Permission denied
#   3. 上游文件（例如 .github/workflows/build.yml）→ fork 与上游不一致，pull 时冲突
#
#   scripts/repair-fork.sh [--repo <仓库>] [--upstream <Mishka 仓库>] [--pin <scripta commit>] [--dry-run]
#
# 默认会**真的改**（都是本地 git 操作，不 push；每步都打印）。先看计划：--dry-run
# 改完需要你自己 commit + push：
#   git add -A && git commit -m "fix: 恢复 scripta/mihomo 子模块指针与 gradlew 权限位" && git push
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

REPO_ARG=""
UPSTREAM="https://github.com/YuKongA/Mishka.git"
PIN=""
DRY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)     REPO_ARG="$2"; shift 2 ;;
    --upstream) UPSTREAM="$2"; shift 2 ;;
    --pin)      PIN="$2"; shift 2 ;;
    --dry-run)  DRY=1; shift ;;
    -h|--help)  sed -n '2,14p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done

REPO="$(find_repo_root "$REPO_ARG")"
run() { if [[ $DRY -eq 1 ]]; then say "  [dry-run] $*"; else eval "$@"; fi; }

step "取上游的 pin（$UPSTREAM）"
if ! git -C "$REPO" fetch --depth=1 --no-tags "$UPSTREAM" main 2>/dev/null; then
  # 有些环境 fetch 到 FETCH_HEAD 失败但分支能取到
  git -C "$REPO" fetch --depth=1 --no-tags "$UPSTREAM" main:refs/remotes/upstream-check/main >/dev/null 2>&1 \
    && UP_REF="refs/remotes/upstream-check/main" || die "取不到上游（设置 --upstream 或先加个 remote）"
fi
UP_REF="${UP_REF:-FETCH_HEAD}"
up_tree="$(git -C "$REPO" rev-parse "$UP_REF^{tree}")"
ok "上游树 $up_tree"

# ---------- 1. 子模块指针 ----------
step "子模块指针"
if [[ -z "$PIN" ]]; then
  PIN="$(git -C "$REPO" ls-tree "$UP_REF" scripta | awk '{print $3}')"
fi
mihomo_pin="$(git -C "$REPO" ls-tree "$UP_REF" mihomo | awk '{print $3}')"

restore_link() {
  local path="$1" pin="$2"
  if git -C "$REPO" ls-files -s -- "$path" | grep -q '^160000'; then
    ok "$path 指针在（$(git -C "$REPO" ls-files -s -- "$path" | awk '{print substr($2,1,8)}')）"
    return
  fi
  if [[ -z "$pin" ]]; then
    warn "$path: 上游也取不到 pin，跳过"
    return
  fi
  if ! grep -q "path = $path" "$REPO/.gitmodules" 2>/dev/null; then
    warn "$path: .gitmodules 里没有条目，跳过（需要的话手工 git submodule add）"
    return
  fi
  warn "$path 指针丢失 → 恢复成上游 pin ${pin:0:8}"
  run "git -C '$REPO' update-index --add --cacheinfo 160000,$pin,'$path'"
}
restore_link scripta "$PIN"
restore_link mihomo "$mihomo_pin"

# ---------- 2. gradlew 可执行位 ----------
step "gradlew 可执行位"
if [[ -f "$REPO/gradlew" ]]; then
  mode="$(git -C "$REPO" ls-files -s gradlew | awk '{print $1}')"
  if [[ "$mode" == "100755" ]]; then
    ok "已是 100755"
  else
    warn "是 $mode（上游 100755）→ 修正索引和工作区"
    run "git -C '$REPO' update-index --chmod=+x gradlew"
    run "chmod +x '$REPO/gradlew'"
  fi
else
  warn "工作区里没有 gradlew —— 从上游取回来"
  run "git -C '$REPO' checkout '$UP_REF' -- gradlew gradlew.bat"
  run "git -C '$REPO' update-index --chmod=+x gradlew"
fi

# ---------- 3. 上游文件缺失 ----------
step "上游文件缺失检查"
missing_file=""
while read -r mode type oid path; do
  [[ "$type" == "blob" ]] || continue
  [[ "$path" == scripta/* || "$path" == mihomo/* ]] && continue
  if ! git -C "$REPO" cat-file -e "HEAD:$path" 2>/dev/null; then
    if [[ "$path" == .github/workflows/* ]]; then
      warn "上游有而你没有（工作流文件，可能你有意删的，先不动）：$path"
    else
      missing_file+="$path"$'\n'
    fi
  fi
done < <(git -C "$REPO" ls-tree -r "$UP_REF")

if [[ -n "$missing_file" ]]; then
  warn "以下上游文件缺失，从上游取回："
  printf '%s' "$missing_file" | sed 's/^/    /'
  while IFS= read -r p; do
    [[ -n "$p" ]] || continue
    run "git -C '$REPO' checkout '$UP_REF' -- '$p'"
  done <<< "$missing_file"
else
  ok "没有缺失的上游文件（工作流文件除外）"
fi
warn "上游的 build.yml 若确实要恢复：git checkout $UP_REF -- .github/workflows/build.yml（它会在 push 时构建 debug+release；不想跑就在 Actions 页面禁用它）"

# ---------- 4. 收尾提示 ----------
step "结果"
if [[ $DRY -eq 1 ]]; then
  say "  --dry-run：什么都没改。去掉 --dry-run 即执行。"
else
  say "  索引里现在的状态："
  git -C "$REPO" status --short | sed 's/^/    /' || true
  git -C "$REPO" ls-files -s scripta mihomo gradlew 2>/dev/null | awk '{printf "    %s %s\n", $1, $4}'
  cat <<EOF

  完成。接着做这三件事（顺序别换）：

    # 1) 拉下子模块内容（scripta 是 Gradle 的 includeBuild，必须有）
    git -C "$REPO" submodule update --init scripta

    # 2) 提交并推送
    git -C "$REPO" add -A
    git -C "$REPO" commit -m "fix: 恢复 scripta/mihomo 子模块指针与 gradlew 权限位"
    git -C "$REPO" push

    # 3) 重新触发工作流（或直接 push 触发）
EOF
fi
