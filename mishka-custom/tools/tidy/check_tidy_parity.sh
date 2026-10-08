#!/usr/bin/env bash
# 字段整理对拍：把 ConfigTidy.kt（Kotlin 版「整理配置字段顺序」）和参考实现逐字节比一遍。
#
# 参考实现直接取自 mihomo_box 模块 20261001-1620 的 webroot/ui/js/core.js（tidyMihomoConfig
# 及其辅助函数，原样抽到 tidy-ref.mjs，只去掉 export 前缀 + 末尾统一导出），所以这不是
# 「重写一遍再手测」，而是**同一批输入、两份实现、逐字节 diff**。
#
# 用法:
#   tools/tidy/check_tidy_parity.sh --repo <Mishka 仓库> [--kotlinc <kotlinc 路径>]
#
# 依赖：node ≥ 18（跑参考实现）、JDK（跑编译产物）、kotlinc（编译 Kotlin 版；
#       没装在 PATH 上就用 --kotlinc 或环境变量 KOTLINC 指路径，例如 kotlin-compiler-2.x 解压后的 bin/kotlinc）。
#
# 换行符口径：参考实现按 `\n` 切分再按 `\n` 拼回，CRLF 输入会在行尾留下裸 `\r`（并混进新加的
# 空行）；Kotlin 版保留原文的行尾符（CRLF 文件整理后仍是 CRLF，见 14-crlf 用例）。因此对拍时
# 两边都先去掉 `\r` 再比，另加一条「Kotlin 输出没有裸换行」的断言。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"     # mishka-custom/

REPO=""
KOTLINC="${KOTLINC:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)    REPO="$2"; shift 2 ;;
    --kotlinc) KOTLINC="$2"; shift 2 ;;
    -h|--help) sed -n '2,16p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$REPO" ]] || { echo "错误：必须给 --repo <Mishka 仓库>" >&2; exit 2; }
REPO="$(cd "$REPO" && pwd)"

FORMS="$REPO/app/src/main/kotlin/top/yukonga/mishka/custom/forms"
for f in "$FORMS/ConfigTidy.kt" "$FORMS/YamlEngine.kt"; do
  [[ -f "$f" ]] || { echo "错误：找不到 $f（仓库没打 0004 补丁？）" >&2; exit 2; }
done

command -v node >/dev/null || { echo "错误：需要 node 跑参考实现" >&2; exit 2; }
if [[ -z "$KOTLINC" ]]; then
  KOTLINC="$(command -v kotlinc || true)"
fi
[[ -n "$KOTLINC" && -x "$KOTLINC" ]] || {
  echo "错误：找不到 kotlinc。装一个（kotlin-compiler-*.zip 解压即用），或用 --kotlinc/KOTLINC 指定。" >&2; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "--- 编译 Kotlin 版（ConfigTidy.kt + YamlEngine.kt + Main.kt）---"
"$KOTLINC" "$FORMS/ConfigTidy.kt" "$FORMS/YamlEngine.kt" "$SCRIPT_DIR/Main.kt" -include-runtime -d "$WORK/tidy.jar" >/dev/null
echo "ok  编译通过"

pass=0; fail=0
for f in "$SCRIPT_DIR"/cases/*.yaml; do
  name="$(basename "$f")"
  # CRLF 用例：参考实现的 `.*` 不匹配 \r、`$` 也不认行尾的 \r，它自己处理 CRLF 是「半失效」的
  # （这就是下面那条行尾符断言存在的原因）。所以 CRLF 用例的期望值取「同一份文件去掉 \r 后参考实现
  # 的输出」——即 Kotlin 版 = 参考实现在等价 LF 文件上的结果 + 原样换回 CRLF。
  if grep -q $'\r' "$f"; then
    tr -d '\r' < "$f" > "$WORK/lf.yaml"
    node "$SCRIPT_DIR/run-ref.mjs" "$WORK/lf.yaml" | tr -d '\r' > "$WORK/ref.out"
  else
    node "$SCRIPT_DIR/run-ref.mjs" "$f" | tr -d '\r' > "$WORK/ref.out"
  fi
  java -jar "$WORK/tidy.jar" "$f" > "$WORK/kt.raw"
  tr -d '\r' < "$WORK/kt.raw" > "$WORK/kt.out"
  if cmp -s "$WORK/ref.out" "$WORK/kt.out"; then
    echo "ok  $name"
    pass=$((pass + 1))
  else
    echo "FAIL $name（参考实现 vs Kotlin 版逐字节不一致）" >&2
    diff -u "$WORK/ref.out" "$WORK/kt.out" | head -40 >&2 || true
    fail=$((fail + 1))
  fi
done

echo "--- 行尾符：CRLF 文件整理后必须仍是 CRLF（没有裸换行）---"
java -jar "$WORK/tidy.jar" "$SCRIPT_DIR/cases/14-crlf.yaml" > "$WORK/crlf.out"
if python3 - "$WORK/crlf.out" <<'PY'
import sys
data = open(sys.argv[1], 'rb').read()
assert b'\r\n' in data, '输出里没有 CRLF'
bad = [i for i, b in enumerate(data) if b == 0x0A and (i == 0 or data[i-1] != 0x0D)]
sys.exit(1 if bad else 0)
PY
then
  echo "ok  CRLF 保留"
  pass=$((pass + 1))
else
  echo "FAIL CRLF 输出里出现裸 LF（混排换行）" >&2
  fail=$((fail + 1))
fi

echo
echo "对拍结果：same=$pass diff=$fail"
[[ $fail -eq 0 ]]
