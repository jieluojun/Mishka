#!/usr/bin/env bash
# 脚本共用部分：日志、路径探测、常量。被其它脚本 source，不单独执行。

KERNEL_URL_DEFAULT="https://github.com/jieluojun/mihomo"
KERNEL_BRANCH_DEFAULT="Alpha"
CORE_MODULE_REL="app/src/main/native/mishka_core"
FMES_REL="app/src/main/kotlin/top/yukonga/mishka/ui/screen/settings/FileManagerEditorScreen.kt"
CUSTOM_REL="app/src/main/kotlin/top/yukonga/mishka/custom"
CUSTOM_DIR_NAME="mishka-custom"

if [[ -t 1 ]]; then
  C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'; C_DIM=$'\033[2m'; C_OFF=$'\033[0m'
else
  C_GREEN=""; C_YELLOW=""; C_RED=""; C_DIM=""; C_OFF=""
fi

say()  { printf '%s\n' "$*"; }
step() { printf '\n%s==> %s%s\n' "$C_DIM" "$*" "$C_OFF"; }
ok()   { printf '%s  ok%s   %s\n' "$C_GREEN" "$C_OFF" "$*"; }
warn() { printf '%s  warn%s %s\n' "$C_YELLOW" "$C_OFF" "$*" >&2; }
die()  { printf '%s  错误%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }

# 交付根：本文件所在目录的上一级（scripts/ 的父目录）
deliver_root() {
  local here
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  cd "$here/.." && pwd
}

# 仓库根：显式 --repo 优先，其次从当前目录向上找「Mishka 仓库」标志
find_repo_root() {
  local explicit="$1" dir
  if [[ -n "$explicit" ]]; then
    dir="$(cd "$explicit" && pwd)" || die "路径不存在：$explicit"
    [[ -f "$dir/$CORE_MODULE_REL/go.mod" ]] || die "$dir 看起来不是 Mishka 仓库（找不到 $CORE_MODULE_REL/go.mod）"
    printf '%s' "$dir"
    return
  fi
  dir="$(pwd)"
  while [[ "$dir" != "/" ]]; do
    if [[ -f "$dir/$CORE_MODULE_REL/go.mod" ]]; then
      printf '%s' "$dir"
      return
    fi
    dir="$(dirname "$dir")"
  done
  die "找不到 Mishka 仓库：请在仓库内运行，或用 --repo <路径> 指定"
}

# 内核基线 commit（从补丁基线里读，避免脚本里再写一遍）
kernel_base_commit() {
  local root="$1"
  sed -n 's/^base_commit=//p' "$root/patches/mihomo/BASELINE.txt" | head -1
}

kernel_patched() {
  local dir="$1"
  [[ -f "$dir/config/patch_mishka.go" ]] && grep -q 'mishkaPatch = patchMishka' "$dir/config/patch_mishka.go" 2>/dev/null
}

app_patch_applied() {
  local repo="$1"
  [[ -f "$repo/$CUSTOM_REL/anchor/AnchorPanel.kt" ]] && grep -q 'MishkaAnchorPanel' "$repo/$FMES_REL" 2>/dev/null
}

# 临时目录（脚本退出时清理）
TMP_DIRS=()
mktmp() {
  local d
  d="$(mktemp -d)"
  TMP_DIRS+=("$d")
  printf '%s' "$d"
}
cleanup_tmp() {
  local d
  if [[ ${#TMP_DIRS[@]} -gt 0 ]]; then
    for d in "${TMP_DIRS[@]}"; do
      [[ -n "$d" && -d "$d" ]] && rm -rf "$d"
    done
  fi
  # EXIT trap 的最后一条命令会决定脚本退出码：必须显式返回 0，
  # 否则「目录本来就不存在」这类正常情况会让脚本以 1 退出（CI 会因此判失败）。
  return 0
}
trap cleanup_tmp EXIT

# 路径是否已被版本控制（用来判断「要不要登记 exclude」：要提交的东西不能忽略）
is_tracked() {
  local repo="$1" path="$2"
  [[ -n "$(git -C "$repo" ls-files -- "$path" | head -1)" ]]
}

# 在 .git/info/exclude 里登记（保证自定义文件不脏 git status，又不动 .gitignore）
exclude_path() {
  local repo="$1" entry="$2" exclude_file
  exclude_file="$repo/.git/info/exclude"
  [[ -d "$repo/.git" ]] || return 0
  mkdir -p "$repo/.git/info"
  touch "$exclude_file"
  grep -qxF "$entry" "$exclude_file" || printf '%s\n' "$entry" >> "$exclude_file"
}
