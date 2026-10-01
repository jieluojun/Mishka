#!/usr/bin/env bash
# 用 kotlinc 现编译 ConfigRefs.kt（删除前引用检查 + 改名同步）+ 对拍 CLI，再和参考实现的 JS 逐行对拍
# （见 tools/forms/refs_diff.mjs；参考实现片段在 tools/forms/ref/）。
#
# 用法: tools/forms/run_refs_diff.sh [--jar /tmp/refs.jar] [--keep]
#
# 退出码：0 全部一致；1 有差异或编译失败；3 找不到 kotlinc / node / java（调用方按「跳过」处理）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DELIVER="$(cd "$SCRIPT_DIR/../.." && pwd)"
JAR="/tmp/refs.jar"
KEEP=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar) JAR="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    -h|--help) sed -n '2,7p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

KOTLINC="${KOTLINC:-}"
if [[ -z "$KOTLINC" ]]; then
  if command -v kotlinc >/dev/null 2>&1; then
    KOTLINC="$(command -v kotlinc)"
  elif [[ -x "$HOME/.cache/kt/kotlinc/bin/kotlinc" ]]; then
    KOTLINC="$HOME/.cache/kt/kotlinc/bin/kotlinc"
  fi
fi
if [[ -z "$KOTLINC" || ! -x "$KOTLINC" ]]; then
  echo "找不到 kotlinc（KOTLINC=/path/to/kotlinc 可指定；安装见 VERIFY.md），本项跳过" >&2
  exit 3
fi
command -v node >/dev/null 2>&1 || { echo "找不到 node，本项跳过" >&2; exit 3; }
command -v java >/dev/null 2>&1 || { echo "找不到 java（refs_diff.mjs 要用 java -jar 跑 Kotlin 侧），本项跳过" >&2; exit 3; }

F="$DELIVER/app/src/main/kotlin/top/yukonga/mishka/custom/forms"
SRCS=("$F/YamlEngine.kt" "$F/FormSpecs.kt" "$F/FormSpecsP2.kt" "$F/FlowText.kt" "$F/FormValues.kt" "$F/ConfigRefs.kt" "$SCRIPT_DIR/kotlin/ConfigRefsCli.kt")

if [[ "$KEEP" -eq 0 || ! -f "$JAR" ]]; then
  echo "编译 ConfigRefs + 对拍 CLI（$("$KOTLINC" -version 2>&1 | head -1)）…"
  tmp_jar="${JAR}.$$.jar"
  "$KOTLINC" "${SRCS[@]}" -include-runtime -d "$tmp_jar" >/tmp/refs_diff_build.log 2>&1 || {
    echo "编译失败，日志尾部：" >&2
    tail -20 /tmp/refs_diff_build.log >&2
    exit 1
  }
  mv "$tmp_jar" "$JAR"
  echo "编译完成：$JAR ($(du -h "$JAR" | cut -f1))"
else
  echo "复用已有 $JAR（--keep）"
fi

node "$SCRIPT_DIR/refs_diff.mjs" --jar "$JAR"
