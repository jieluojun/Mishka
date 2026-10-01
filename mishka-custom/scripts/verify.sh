#!/usr/bin/env bash
# 一键跑全部自检：离线部分（语法 / API / 算法对拍 / 性质测试）总是跑；
# 给了 --repo / --kernel-dir 就再跑补丁双向校验。
#
#   scripts/verify.sh [--repo <Mishka 仓库>] [--kernel-dir <内核目录>]
#
# 补丁校验要求「工作区干净」，所以想连补丁一起验，先 revert-patches.sh 还原。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

REPO_ARG=""
KERNEL_ARG=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)       REPO_ARG="$2"; shift 2 ;;
    --kernel-dir) KERNEL_ARG="$2"; shift 2 ;;
    -h|--help)    sed -n '2,9p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done

DELIVER="$(deliver_root)"
PY="python3"
command -v "$PY" >/dev/null 2>&1 || die "需要 python3"

pass=0
fail=0
skipped=0

run() {
  local name="$1"; shift
  step "$name"
  if "$@"; then
    ok "$name"
    pass=$((pass + 1))
  else
    warn "$name（退出码非 0）"
    fail=$((fail + 1))
  fi
}

skip() {
  warn "$1：跳过（$2）"
  skipped=$((skipped + 1))
}

step "离线自检"

if "$PY" -c "import tree_sitter, tree_sitter_language_pack" >/dev/null 2>&1; then
  run "Kotlin 语法门（tree-sitter）" \
    "$PY" "$DELIVER/tools/check_kotlin.py" "$DELIVER/app/src/main/kotlin/top/yukonga/mishka/custom/anchor"
else
  skip "Kotlin 语法门" "缺依赖：pip install tree-sitter tree-sitter-language-pack"
fi

if "$PY" -c "import yaml" >/dev/null 2>&1; then
  run "编辑层性质测试（PyYAML 真解析）" "$PY" "$DELIVER/tools/equiv/edit_props.py"
else
  skip "编辑层性质测试" "缺 pyyaml"
fi

run "转写模型新鲜度" "$PY" "$DELIVER/tools/equiv/model_freshness.py"

if command -v node >/dev/null 2>&1; then
  run "锚点扫描三方可对拍（参考 JS == Kotlin 转写 == 期望）" \
    "$PY" "$DELIVER/tools/equiv/compare.py"
else
  skip "锚点扫描三方可对拍" "没有 node（只跑 Kotlin 转写本身）"
  if "$PY" "$DELIVER/tools/equiv/scan_kotlin.py" "$DELIVER/tools/equiv/corpus" >/dev/null; then
    ok "Kotlin 转写可运行"
    pass=$((pass + 1))
  else
    warn "Kotlin 转写运行失败"
    fail=$((fail + 1))
  fi
fi

# API / 具名实参核对需要依赖源码路径（tools/api_paths.json）；缺路径会记「未核对」，不算失败
run "API 符号与具名实参核对" "$PY" "$DELIVER/tools/check_api.py"

step "补丁校验"

if [[ -n "$REPO_ARG" ]]; then
  repo="$(find_repo_root "$REPO_ARG")"
  if [[ -z "$(git -C "$repo" status --porcelain)" ]]; then
    run "app 侧补丁双向可逆" bash "$DELIVER/tools/verify_app_patch.sh" --repo "$repo"
  else
    skip "app 侧补丁双向校验" "工作区不干净（先 revert-patches.sh）"
  fi
else
  skip "app 侧补丁双向校验" "没给 --repo"
fi

if [[ -n "$KERNEL_ARG" ]]; then
  if [[ -z "$(git -C "$KERNEL_ARG" status --porcelain 2>/dev/null)" ]]; then
    run "内核补丁累积应用 + 逐文件哈希" bash "$DELIVER/tools/verify_mihomo_patches.sh" --kernel-dir "$KERNEL_ARG"
  else
    skip "内核补丁校验" "内核工作区不干净（先还原到基线 commit）"
  fi
else
  skip "内核补丁校验" "没给 --kernel-dir"
fi

say ""
say "通过 $pass 项，失败 $fail 项，跳过 $skipped 项"
[[ $fail -eq 0 ]] || exit 1
