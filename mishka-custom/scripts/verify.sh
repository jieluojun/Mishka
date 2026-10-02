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
# 自检脚本会 import tools/ 下的模块；不让 Python 往仓库里写 __pycache__，否则后面的补丁校验会看到「工作区不干净」
export PYTHONDONTWRITEBYTECODE=1
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
    "$PY" "$DELIVER/tools/check_kotlin.py" "$DELIVER/app/src/main/kotlin/top/yukonga/mishka/custom"
else
  skip "Kotlin 语法门" "缺依赖：pip install tree-sitter tree-sitter-language-pack"
fi

if "$PY" -c "import yaml" >/dev/null 2>&1; then
  run "锚点编辑层性质测试（PyYAML 真解析）" "$PY" "$DELIVER/tools/equiv/edit_props.py"
  if [[ -f "$DELIVER/tools/forms/forms_props.py" ]]; then
    run "配置表单 YAML 引擎性质测试（保真契约）" "$PY" "$DELIVER/tools/forms/forms_props.py"
  fi
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

if [[ -f "$DELIVER/tools/forms/gen_specs.py" ]]; then
  run "表单规格生成物与字段表同步" "$PY" "$DELIVER/tools/forms/gen_specs.py" --check
fi

# Kotlin 引擎的「行为」验证：现编译 + 与 Python 规范逐字节对拍。
# kotlinc 不进快照（见 VERIFY.md），没有就跳过；改过 YamlEngine.kt 后这一项必须跑。
if command -v kotlinc >/dev/null 2>&1 || [[ -x "$HOME/.cache/kt/kotlinc/bin/kotlinc" ]]; then
  run "Kotlin↔Python 引擎逐字节对拍" bash "$DELIVER/tools/forms/run_engine_diff.sh"
  run "表单值 / eBPF / MAPLIST 定向测试" bash "$DELIVER/tools/forms/run_editor_logic_tests.sh"
else
  skip "Kotlin↔Python 引擎逐字节对拍" "没有 kotlinc（装了以后这一项会自动跑；改过 YamlEngine.kt 必须跑）"
  skip "表单值 / eBPF / MAPLIST 定向测试" "没有 kotlinc"
fi

# 删除前引用检查 / 改名同步（ConfigRefs.kt）与参考实现 JS 的对拍：要 kotlinc + node + java；缺任一就跳过
if [[ -f "$DELIVER/tools/forms/run_refs_diff.sh" ]]; then
  if (command -v kotlinc >/dev/null 2>&1 || [[ -x "$HOME/.cache/kt/kotlinc/bin/kotlinc" ]]) && command -v node >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
    run "引用检查 / 改名同步与参考实现对拍" bash "$DELIVER/tools/forms/run_refs_diff.sh"
  else
    skip "引用检查 / 改名同步与参考实现对拍" "需要 kotlinc + node + java"
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
