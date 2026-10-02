#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DELIVER="$(cd "$SCRIPT_DIR/../.." && pwd)"
KOTLINC="${KOTLINC:-}"
if [[ -z "$KOTLINC" ]]; then
  if command -v kotlinc >/dev/null 2>&1; then
    KOTLINC="$(command -v kotlinc)"
  elif [[ -x "$HOME/.cache/kt/kotlinc/bin/kotlinc" ]]; then
    KOTLINC="$HOME/.cache/kt/kotlinc/bin/kotlinc"
  fi
fi
if [[ -z "$KOTLINC" || ! -x "$KOTLINC" ]]; then
  echo "找不到 kotlinc（KOTLINC=/path/to/kotlinc 可指定）" >&2
  exit 3
fi

FORMS="$DELIVER/app/src/main/kotlin/top/yukonga/mishka/custom/forms"
KOTLIN_HOME="$(dirname "$(dirname "$(readlink -f "$KOTLINC")")")"
STDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"
if [[ ! -f "$STDLIB" ]]; then
  echo "Kotlin standard library not found under $KOTLIN_HOME/lib" >&2
  exit 2
fi
BUILD_DIR="$(mktemp -d /tmp/editor-logic-tests.XXXXXX)"
trap 'rm -rf "$BUILD_DIR"' EXIT
"$KOTLINC" \
  "$FORMS/YamlEngine.kt" \
  "$FORMS/FormSpecs.kt" \
  "$FORMS/FormValues.kt" \
  "$FORMS/ConfigRefs.kt" \
  "$FORMS/EbpfFormLogic.kt" \
  "$FORMS/FormMapListLogic.kt" \
  "$FORMS/AnchorInheritance.kt" \
  "$SCRIPT_DIR/kotlin/EditorLogicProps.kt" \
  "$SCRIPT_DIR/kotlin/AnchorInheritanceProps.kt" \
  -d "$BUILD_DIR"
java -cp "$BUILD_DIR:$STDLIB" top.yukonga.mishka.custom.forms.EditorLogicPropsKt
java -cp "$BUILD_DIR:$STDLIB" top.yukonga.mishka.custom.forms.AnchorInheritancePropsKt
