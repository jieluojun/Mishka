#!/usr/bin/env bash
# 本地出 release APK（**只出 release**，不碰 debug 变体）。
#
#   scripts/build-release.sh [--repo <Mishka 仓库>] [--clean] [--unsigned]
#                            [--refresh-sum] [--no-keystore]
#
# 流程：
#   前置检查（JDK 21+ / Android SDK / Go 1.25+ / 补丁与 go.work 在位）
#   → 没有签名就自动调 gen-keystore.sh 生成一个（否则 release APK 装不上）
#   → ./gradlew :app:downloadGeoFiles
#   → ./gradlew -I mishka-custom/init/no-debug.init.gradle :app:assembleRelease
#   → 报告 APK 路径、体积、sha256
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

REPO_ARG=""
CLEAN=0
UNSIGNED=0
REFRESH_SUM=0
NO_KEYSTORE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)        REPO_ARG="$2"; shift 2 ;;
    --clean)       CLEAN=1; shift ;;
    --unsigned)    UNSIGNED=1; shift ;;
    --refresh-sum) REFRESH_SUM=1; shift ;;
    --no-keystore) NO_KEYSTORE=1; shift ;;
    -h|--help)     sed -n '2,14p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done

DELIVER="$(deliver_root)"
REPO="$(find_repo_root "$REPO_ARG")"

# ---------------------------------------------------------------- 前置检查
step "前置检查"
[[ -f "$REPO/go.work" && -f "$REPO/go.work.sum" ]] \
  || die "缺少 go.work / go.work.sum（内核换成 jieluojun 后必需）：先跑 scripts/setup.sh"
app_patch_applied "$REPO" || die "锚点面板补丁没打上：先跑 scripts/setup.sh（或 apply-patches.sh）"

java_ver="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/' || echo 0)"
if [[ "${java_ver:-0}" -lt 21 ]]; then
  warn "当前 java 主版本 ${java_ver:-未知}，本项目要 21+（sourceCompatibility=21）。设 JAVA_HOME 指向 JDK 21 再跑。"
else
  ok "JDK $java_ver"
fi

if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]] && ! grep -q '^\s*sdk.dir=' "$REPO/local.properties" 2>/dev/null; then
  warn "没找到 Android SDK（ANDROID_HOME / ANDROID_SDK_ROOT / local.properties 的 sdk.dir 都没有）"
  warn "需要 SDK Platform 37 + AGP 默认 NDK（原生构建要用），缺失时 Gradle 会去自动下载"
else
  ok "Android SDK 已配置"
fi

if command -v go >/dev/null 2>&1; then
  go_ver="$(go version | sed -E 's/.*go([0-9]+\.[0-9]+).*/\1/')"
  ok "Go $go_ver"
  if [[ "$(printf '%s\n' 1.25 "$go_ver" | sort -V | head -1)" != "1.25" ]]; then
    warn "mishka_core 要求 go 1.25+，当前 $go_ver"
  fi
else
  die "找不到 go（GoBuildTask 要用它交叉编译 libmihomo.so）"
fi

# ---------------------------------------------------------------- 签名
step "签名"
if [[ -n "${KEYSTORE_PATH:-}" ]] || grep -q '^\s*KEYSTORE_PATH=' "$REPO/local.properties" 2>/dev/null; then
  ok "已有 keystore 配置"
elif [[ $UNSIGNED -eq 1 ]]; then
  warn "--unsigned：release APK 会没有签名，装不上（除作对比体积用）"
elif [[ $NO_KEYSTORE -eq 1 ]]; then
  die "--no-keystore 但也没有现成 keystore：release 无法安装"
else
  say "  没有 keystore，自动生成一个（想用自己的：scripts/gen-keystore.sh --help）"
  bash "$DELIVER/scripts/gen-keystore.sh" --repo "$REPO"
fi

# ---------------------------------------------------------------- Go 工作区
if [[ $REFRESH_SUM -eq 1 ]]; then
  step "刷新 go.work.sum"
  ( cd "$REPO/$CORE_MODULE_REL" && GOOS=android GOARCH=arm64 go mod download all ) \
    && ok "已刷新" || warn "刷新失败（离线？）——沿用交付自带的 go.work.sum"
fi

# ---------------------------------------------------------------- 构建
gradlew="$REPO/gradlew"
[[ -x "$gradlew" ]] || die "找不到 $gradlew"
init_script="$DELIVER/init/no-debug.init.gradle"
log="$DELIVER/build.log"

step "Gradle：:app:downloadGeoFiles"
( cd "$REPO" && ./gradlew --console=plain :app:downloadGeoFiles ) 2>&1 | tee "$log.download" | tail -5

step "Gradle：:app:assembleRelease（release only）"
gradle_args=(--console=plain)
[[ $CLEAN -eq 1 ]] && gradle_args+=(clean)
gradle_args+=(-I "$init_script" :app:assembleRelease)
set +e
( cd "$REPO" && ./gradlew "${gradle_args[@]}" ) 2>&1 | tee "$log" | tail -40
status=${PIPESTATUS[0]}
set -e

if [[ $status -ne 0 ]]; then
  say ""
  if grep -qE "missing (go\.work\.)?go\.sum entry|updates to go\.mod needed" "$log"; then
    die "看起来是依赖哈希不全（$log）：跑 bash \"$DELIVER/scripts/setup.sh\" --repo \"$REPO\" --refresh-sum 后重试"
  fi
  die "构建失败，完整日志：$log"
fi

# ---------------------------------------------------------------- 报告
step "产物"
mapfile -t apks < <(find "$REPO/app/build/outputs/apk/release" -name '*.apk' -newermt '-2 hours' 2>/dev/null | sort)
[[ ${#apks[@]} -gt 0 ]] || mapfile -t apks < <(find "$REPO/app/build/outputs/apk/release" -name '*.apk' 2>/dev/null | sort)
[[ ${#apks[@]} -gt 0 ]] || die "构建成功但没找到 APK，看看 $REPO/app/build/outputs/"
for apk in "${apks[@]}"; do
  size="$(du -h "$apk" | cut -f1)"
  sum="$(sha256sum "$apk" | cut -d' ' -f1)"
  say "  $apk"
  say "    体积 $size    sha256 ${sum:0:16}…"
done
say ""
say "安装：adb install -r \"${apks[0]}\""
say "（debug 变体被 init/no-debug.init.gradle 挡住；确实要构建它：加 -PallowDebugBuild=true）"
