#!/usr/bin/env bash
# 生成 release 签名用的 keystore，并把路径/口令写进 local.properties（被 .gitignore 忽略）。
#
#   scripts/gen-keystore.sh [--repo <Mishka 仓库>] [--force] [--alias <别名>]
#                           [--store-pass <口令>] [--key-pass <口令>] [--dname <X.500>]
#
# 生成的 keystore 放在 <仓库>/mishka-custom/keystore/mishka-release.jks（mishka-custom 不进 git）。
# 注意：keystore 一旦用来发布就不能再换——换了签名，老用户必须卸载重装。请备份该文件与口令。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

REPO_ARG=""
FORCE=0
ALIAS="mishka"
STORE_PASS=""
KEY_PASS=""
DNAME="CN=Mishka Custom,OU=Self,O=Self,L=Shanghai,C=CN"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)       REPO_ARG="$2"; shift 2 ;;
    --force)      FORCE=1; shift ;;
    --alias)      ALIAS="$2"; shift 2 ;;
    --store-pass) STORE_PASS="$2"; shift 2 ;;
    --key-pass)   KEY_PASS="$2"; shift 2 ;;
    --dname)      DNAME="$2"; shift 2 ;;
    -h|--help)    sed -n '2,10p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done

REPO="$(find_repo_root "$REPO_ARG")"
DELIVER="$(deliver_root)"
GEN_DIR="$DELIVER/keystore"
KS="$GEN_DIR/mishka-release.jks"

KEYTOOL="$(command -v keytool || true)"
if [[ -z "$KEYTOOL" ]]; then
  for cand in "${JAVA_HOME:-}/bin/keytool" /usr/lib/jvm/*/bin/keytool; do
    [[ -x "$cand" ]] && KEYTOOL="$cand" && break
  done
fi
[[ -n "$KEYTOOL" ]] || die "找不到 keytool（装个 JDK 21，或设置 JAVA_HOME）"

if [[ -f "$KS" && $FORCE -eq 0 ]]; then
  ok "keystore 已存在：$KS（要重新生成加 --force，注意换签名会导致老用户必须卸载重装）"
else
  mkdir -p "$GEN_DIR"
  [[ -n "$STORE_PASS" ]] || STORE_PASS="$(head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 24)"
  [[ -n "$KEY_PASS" ]] || KEY_PASS="$STORE_PASS"
  "$KEYTOOL" -genkeypair -v \
    -keystore "$KS" \
    -storetype PKCS12 \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10950 \
    -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
    -dname "$DNAME" >/dev/null
  printf 'KEYSTORE_PASS=%s\nKEY_ALIAS=%s\nKEY_PASSWORD=%s\n' "$STORE_PASS" "$ALIAS" "$KEY_PASS" > "$GEN_DIR/keystore.properties"
  chmod 600 "$KS" "$GEN_DIR/keystore.properties"
  ok "已生成 $KS（口令记在 $GEN_DIR/keystore.properties，请自行备份）"
fi

# 写 local.properties：只动我们这四个键，别踩掉 sdk.dir
props="$REPO/local.properties"
store_pass_val="$STORE_PASS"
key_pass_val="$KEY_PASS"
alias_val="$ALIAS"
if [[ -f "$GEN_DIR/keystore.properties" ]]; then
  # shellcheck disable=SC1091
  source "$GEN_DIR/keystore.properties"
  store_pass_val="${KEYSTORE_PASS:-$store_pass_val}"
  key_pass_val="${KEY_PASSWORD:-$key_pass_val}"
  alias_val="${KEY_ALIAS:-$alias_val}"
fi
tmp="$(mktmp)/local.properties"
touch "$props"
grep -vE '^\s*(KEYSTORE_PATH|KEYSTORE_PASS|KEY_ALIAS|KEY_PASSWORD)\s*=' "$props" > "$tmp" || true
{
  printf 'KEYSTORE_PATH=%s\n' "$KS"
  printf 'KEYSTORE_PASS=%s\n' "$store_pass_val"
  printf 'KEY_ALIAS=%s\n' "$alias_val"
  printf 'KEY_PASSWORD=%s\n' "$key_pass_val"
} >> "$tmp"
mv "$tmp" "$props"
ok "已写 $props（local.properties 在 .gitignore 里，不会进版本库）"

say ""
"$KEYTOOL" -list -v -keystore "$KS" -storepass "$store_pass_val" 2>/dev/null \
  | grep -E "别名|Alias|SHA256|有效期|Valid" | head -4 | sed 's/^/  /' || true
say "  备份好 $KS 与 $GEN_DIR/keystore.properties —— 丢了就再也发不出同签名的更新包。"
