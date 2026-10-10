#!/usr/bin/env bash
# 脚本共用部分：日志、路径探测、常量。被其它脚本 source，不单独执行。

KERNEL_URL_DEFAULT="https://github.com/jieluojun/mihomo"
KERNEL_BRANCH_DEFAULT="Alpha"
CORE_MODULE_REL="app/src/main/native/mishka_core"
FMES_REL="app/src/main/kotlin/top/yukonga/mishka/ui/screen/settings/FileManagerEditorScreen.kt"
CUSTOM_REL="app/src/main/kotlin/top/yukonga/mishka/custom"
CUSTOM_DIR_NAME="mishka-custom"
APP_PKG_REL="app/src/main/kotlin/top/yukonga/mishka"
VM_REL="$APP_PKG_REL/viewmodel/SubscriptionViewModel.kt"
PROXY_VM_REL="$APP_PKG_REL/viewmodel/ProxyViewModel.kt"
CONN_REL="$APP_PKG_REL/ui/screen/connection/ConnectionScreen.kt"
APRC_REL="$APP_PKG_REL/service/ActiveProfileRuntimeConfig.kt"

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

# 内核版本串：对齐 jieluojun/mihomo 自己 CI（sync-and-build.yml）的
#   VERSION="alpha-smart-$(git rev-parse --short HEAD)-with-at"（发布 tag：with-at-latest）。
# app 主页「内核版本」直接显示内核 /version 的返回：构建时必须用 -Pmihomo.version 注入，
# 否则沿用上游 gradle.properties 里 pin 的 v1.19.31+（那是 metacubex/mihomo 的版本号，
# 与本定制内核无关——这正是「主页版本显示 1.19.31+」的根因）。
kernel_version_string() {
  local repo="$1" sha
  sha="$(git -C "$repo/mihomo" rev-parse --short=8 HEAD 2>/dev/null || true)"
  if [[ -z "$sha" ]]; then
    # 内核目录没有 .git（源码包 / 浅缓存）：退回补丁基线 commit 的前 8 位
    sha="$(kernel_base_commit "$(deliver_root)" | cut -c1-8)"
  fi
  printf 'alpha-smart-%s-with-at' "$sha"
}

kernel_patched() {
  local dir="$1"
  [[ -f "$dir/config/patch_mishka.go" ]] && grep -q 'mishkaPatch = patchMishka' "$dir/config/patch_mishka.go" 2>/dev/null
}

# 第 N 个 app 补丁是否已经在仓库里：看它引入的独有文件 / 标记串。
# 标记在该补丁之后的状态里存在、在它之前的状态里不存在（已对 0..8 每个前缀状态逐一核对）。
app_feature_applied() {
  local repo="$1" num="$2"
  local fmes="$repo/$FMES_REL"
  case "$num" in
    # 0001 是合并版：锚点面板 + 可视化编辑器（含 maplist 拖动排序、路由规则序号、匹配值省略号、应用选择器），五个标记都在才算已装
    0001) [[ -f "$repo/$CUSTOM_REL/anchor/AnchorPanel.kt" ]] && grep -q 'MishkaAnchorPanel' "$fmes" 2>/dev/null \
          && grep -q '拖动中按 order 实时换位' "$repo/$CUSTOM_REL/forms/P3FormEditors.kt" 2>/dev/null \
          && grep -q 'RuleIndexBadge' "$repo/$CUSTOM_REL/forms/FlowFormPages.kt" 2>/dev/null \
          && grep -q 'titleMaxLines' "$repo/$CUSTOM_REL/forms/ConfigFormPanel.kt" 2>/dev/null \
          && grep -q 'AppPickerSectionTitle' "$repo/$CUSTOM_REL/forms/P3FormEditors.kt" 2>/dev/null ;;
    0002) grep -q 'importBuiltinMianliuOnce' "$repo/$VM_REL" 2>/dev/null ;;
    0003) [[ -f "$repo/$CUSTOM_REL/forms/ConfigTidy.kt" ]] && grep -q 'fun tidyConfig()' "$fmes" 2>/dev/null ;;
    0004) grep -q 'refreshSelectionAfterTest' "$repo/$PROXY_VM_REL" 2>/dev/null ;;
    0005) [[ -f "$repo/$CUSTOM_REL/panel/PanelWebView.kt" && -f "$repo/$APP_PKG_REL/PanelActivity.kt" ]] ;;
    0006) grep -q 'val proxyType = meta.type.trim().uppercase()' "$repo/$CONN_REL" 2>/dev/null ;;
    0007) grep -q 'forceTunDisabled' "$repo/$APRC_REL" 2>/dev/null ;;
    0008) [[ -f "$repo/$CUSTOM_REL/panel/PanelProxyOverride.kt" ]] ;;
    0009) [[ -f "$repo/$CUSTOM_REL/runtime/TproxyAppFilter.kt" ]] \
          && grep -q 'ROOT_TPROXY_APP_MODE_ACTIVE' "$repo/$APP_PKG_REL/service/MishkaRootService.kt" 2>/dev/null ;;
    0010) [[ -f "$repo/$APP_PKG_REL/service/SystemIpv6.kt" ]] \
          && grep -q 'ROOT_SYSTEM_IPV6' "$repo/$APP_PKG_REL/platform/PlatformStorage.kt" 2>/dev/null ;;
    0011) grep -q 'include("arm64-v8a", "armeabi-v7a")' "$repo/app/build.gradle.kts" 2>/dev/null ;;
    0012) grep -q 'const val MIN_SDK = 26' "$repo/buildSrc/src/main/kotlin/ProjectConfig.kt" 2>/dev/null ;;
    0013) grep -q 'ebpfTakesOverIpv6' "$repo/$APP_PKG_REL/service/SystemIpv6.kt" 2>/dev/null ;;
    0014) grep -q 'apn_drift_check' "$repo/app/src/main/res/raw/system_ipv6.sh" 2>/dev/null ;;
    *) return 1 ;;
  esac
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

# 内核补丁「累积」校验：把 patches/mihomo/*.patch 依次应用到临时索引上（不动工作区）。
# 用临时索引而不是逐个 git apply --check，是因为补丁之间有依赖（0003 的上下文来自 0001），
# 单独 --check 会假失败。tools/verify_mihomo_patches.sh 做的是同一件事（外加逐文件哈希比对），
# 这里内联一份是为了让 setup.sh 在没有 tools/ 目录时也能工作。
kernel_patches_check() {
  local deliver="$1" kernel_dir="$2" tmp_index
  tmp_index="$(mktemp -u)"
  rm -f "$tmp_index"
  GIT_INDEX_FILE="$tmp_index" git -C "$kernel_dir" read-tree HEAD || return 1
  local p
  for p in "$deliver"/patches/mihomo/[0-9]*.patch; do
    GIT_INDEX_FILE="$tmp_index" git -C "$kernel_dir" apply --cached "$p" || { rm -f "$tmp_index"; return 1; }
  done
  rm -f "$tmp_index"
  return 0
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
