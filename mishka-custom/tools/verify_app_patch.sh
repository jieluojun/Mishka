#!/usr/bin/env bash
# 校验 app 侧补丁：apply --check → apply → 逐文件比对基线 blob → apply -R → 工作区必须回到干净。
#
# 用法: tools/verify_app_patch.sh --repo <Mishka 仓库>
#
# 要求仓库处在补丁基线 commit（BASELINE.txt 的 upstream_commit）且工作区干净；
# 脚本跑完不会留下任何改动（成功与失败都还原）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PATCH="$ROOT/patches/app/0001-anchor-panel.patch"
BASELINE="$ROOT/patches/app/BASELINE.txt"

REPO=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    -h|--help) sed -n '2,8p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$REPO" ]] || { echo "错误：必须给 --repo <Mishka 仓库>" >&2; exit 2; }
REPO="$(cd "$REPO" && pwd)"

base_commit="$(sed -n 's/^upstream_commit=//p' "$BASELINE" | head -1)"
expected_sha="$(sed -n 's/^patch_sha256=//p' "$BASELINE" | head -1)"
actual_sha="$(sha256sum "$PATCH" | cut -d' ' -f1)"

fail=0
assert_contains() {
  local needle="$1" path="$2" label="$3"
  if grep -Fq -- "$needle" "$REPO/$path"; then
    echo "ok  $label"
  else
    echo "FAIL $label" >&2
    fail=1
  fi
}

[[ "$actual_sha" == "$expected_sha" ]] && echo "ok  补丁文件 sha256 与基线一致" || {
  echo "FAIL 补丁 sha256 与基线不一致：$actual_sha != $expected_sha" >&2; fail=1; }

head_commit="$(git -C "$REPO" rev-parse HEAD)"
[[ "$head_commit" == "$base_commit" ]] && echo "ok  仓库 HEAD 与基线 commit 一致" || {
  echo "警告：仓库 HEAD=$head_commit，基线=$base_commit（补丁可能因上游改动而失配）" >&2; }

if [[ -n "$(git -C "$REPO" status --porcelain)" ]]; then
  echo "错误：仓库工作区不干净（本工具要在干净工作区上做 apply/apply -R 往返测试）。" >&2
  echo "      先还原：bash mishka-custom/scripts/revert-patches.sh --repo \"$REPO\"" >&2
  git -C "$REPO" status --short >&2
  exit 1
fi

custom_dir="$REPO/app/src/main/kotlin/top/yukonga/mishka/custom"

echo "--- apply --check ---"
git -C "$REPO" apply --check "$PATCH" && echo "ok  补丁可应用"

echo "--- apply（正向） ---"
git -C "$REPO" apply "$PATCH"

if grep -Fq 'val mihomoBuildTags = listOf("cmfa", "mishka", "with_gvisor", "with_ebpf")' "$REPO/app/build.gradle.kts"; then
  echo "ok  Android mihomo build enables the with_ebpf feature tag"
else
  echo "FAIL Android mihomo build is missing the with_ebpf feature tag" >&2
  fail=1
fi

assert_contains 'RuntimeConfigValues.selectSecret(profileSecret, userOverride.secret)' \
  app/src/main/kotlin/top/yukonga/mishka/service/ConfigGenerator.kt \
  'profile secret priority and passwordless fallback are wired'
assert_contains 'RuntimeConfigValues.selectExternalController(' \
  app/src/main/kotlin/top/yukonga/mishka/service/ConfigGenerator.kt \
  'external-controller profile priority is wired'
assert_contains 'RuntimeConfigValues.selectTunDevice(' \
  app/src/main/kotlin/top/yukonga/mishka/service/ConfigGenerator.kt \
  'ROOT TUN device profile priority is wired'
assert_contains 'forceTunEnabled = submode == Submode.Tun' \
  app/src/main/kotlin/top/yukonga/mishka/service/MishkaRootService.kt \
  'ROOT TUN startup forces tun.enable in active mode preparation'
assert_contains 'if (forceTunEnabled)' \
  app/src/main/kotlin/top/yukonga/mishka/service/ActiveProfileRuntimeConfig.kt \
  'plain active profiles persist tun.enable=true for ROOT TUN'
assert_contains 'if (isAgeEncrypted(source)) return ProfileTunConfigUpdate(encrypted = true, sourceWritable = false)' \
  app/src/main/kotlin/top/yukonga/mishka/service/ActiveProfileRuntimeConfig.kt \
  'age-encrypted profile sources remain untouched by tun edits'
assert_contains 'val outcome = persistActiveTunValues(subscriptionId, mapOf("stack" to stack))' \
  app/src/main/kotlin/top/yukonga/mishka/viewmodel/HomeViewModel.kt \
  'ROOT TUN stack changes are written to the active profile'
assert_contains 'ActiveProfileRuntimeConfig.updateTunValues(context, subscriptionId, values)' \
  app/src/main/kotlin/top/yukonga/mishka/viewmodel/HomeViewModel.kt \
  'ROOT TUN MTU/GSO changes update active profile tun keys'
assert_contains 'gsoMaxSize = resolve(profileTun?.gsoMaxSize, userTun?.gsoMaxSize, defaultGsoMaxSize)' \
  app/src/main/kotlin/top/yukonga/mishka/service/RuntimeOverrideBuilder.kt \
  'ROOT TUN runtime override reads profile tun settings'
assert_contains 'subscriptionViewModel?.readProfileTunConfig(id)' \
  app/src/main/kotlin/top/yukonga/mishka/ui/screen/settings/RootSettingsScreen.kt \
  'ROOT settings inspect transformed or age-encrypted profile values'
assert_contains 'if (secret.isNotEmpty())' \
  app/src/main/kotlin/top/yukonga/mishka/service/MihomoRunner.kt \
  'empty secrets are not passed to --secret'
assert_contains 'if (secret.isNotEmpty())' \
  app/src/main/kotlin/top/yukonga/mishka/data/api/MihomoApiClient.kt \
  'empty secrets omit API authorization headers'
assert_contains 'mishkaReadRuntimeConfigValues' \
  app/src/main/native/mishka_core/transform_bridge.go \
  'native runtime config reader is exported'
assert_contains 'mishkaReadRuntimeConfigValues' \
  app/src/main/cpp/mishka_jni.c \
  'JNI bridge calls the native runtime config reader'
assert_contains '0.0.0.0' \
  app/src/main/res/values-zh-rCN/strings.xml \
  'Chinese external-control hint warns about wildcard unauthenticated exposure'

while read -r _ expected path; do
  actual="$(git -C "$REPO" hash-object "$path")"
  if [[ "$actual" == "$expected" ]]; then
    echo "ok  $path"
  else
    echo "FAIL $path 期望 $expected 实际 $actual" >&2; fail=1
  fi
done < <(grep -E '^blob_(new|after)=' "$BASELINE" | sed 's/^blob_[a-z]*=/x /')

echo "--- apply -R（反向） ---"
git -C "$REPO" apply -R "$PATCH"
# 注意：目录已被反向应用删掉时 find 会退出 1，set -e + pipefail 下必须兜住
leftover="$(find "$custom_dir" -name '*.kt' 2>/dev/null | wc -l | tr -d ' ' || true)"
[[ "$leftover" == "0" ]] && echo "ok  新增源码已移除" || {
  echo "FAIL 反向应用后仍残留 $leftover 个 .kt（忽略规则会隐藏它们，git status 不可靠）" >&2; fail=1; }
if [[ -n "$(git -C "$REPO" status --porcelain)" ]]; then
  echo "FAIL 反向应用后工作区不干净：" >&2
  git -C "$REPO" status --porcelain >&2
  fail=1
else
  echo "ok  工作区已回到干净状态"
fi

[[ $fail -eq 0 ]] && echo "PASS: app 侧补丁双向可逆、结果与基线逐文件一致" || exit 1
