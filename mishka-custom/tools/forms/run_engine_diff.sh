#!/usr/bin/env bash
# 用 kotlinc 现编译 Kotlin 引擎 + 对拍 CLI，再和 Python 规范逐字节对拍（见 tools/forms/engine_diff.py）。
#
# 用法: tools/forms/run_engine_diff.sh [--jar /tmp/engine.jar] [--keep]
#
# 退出码：0 全部一致；1 有差异或编译失败；3 找不到 kotlinc（调用方按「跳过」处理）。
# kotlinc 不在快照里，重下见 mishka-custom/VERIFY.md。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DELIVER="$(cd "$SCRIPT_DIR/../.." && pwd)"
PY="${PYTHON:-python3}"
JAR="/tmp/engine.jar"
KEEP=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar) JAR="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    -h|--help) sed -n '2,8p' "${BASH_SOURCE[0]}"; exit 0 ;;
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

ENGINE="$DELIVER/app/src/main/kotlin/top/yukonga/mishka/custom/forms/YamlEngine.kt"
CLI="$SCRIPT_DIR/kotlin/FormEngineCli.kt"

if [[ "$KEEP" -eq 0 || ! -f "$JAR" ]]; then
  echo "编译 Kotlin 引擎（$("$KOTLINC" -version 2>&1 | head -1)）…"
  tmp_jar="${JAR}.$$.jar"
  "$KOTLINC" "$ENGINE" "$CLI" -include-runtime -d "$tmp_jar" >/tmp/engine_diff_build.log 2>&1 || {
    echo "编译失败，日志尾部：" >&2
    tail -20 /tmp/engine_diff_build.log >&2
    exit 1
  }
  mv "$tmp_jar" "$JAR"
  echo "编译完成：$JAR ($(du -h "$JAR" | cut -f1))"
else
  echo "复用已有 $JAR（--keep）"
fi

"$PY" "$SCRIPT_DIR/engine_diff.py" --jar "$JAR"
