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

assert_not_contains() {
  local needle="$1" path="$2" label="$3"
  if grep -Fq -- "$needle" "$REPO/$path"; then
    echo "FAIL $label" >&2
    fail=1
  else
    echo "ok  $label"
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
assert_contains 'ProxyProviders     json.RawMessage' \
  app/src/main/native/mishka_core/transform_bridge.go \
  'effective proxy-provider definitions are exposed from native config parsing'
assert_contains 'readProxyProviderFileSource' \
  app/src/main/kotlin/top/yukonga/mishka/viewmodel/SubscriptionViewModel.kt \
  'file provider type and path are read from the effective profile config'
assert_contains 'ProfileFileOps.getRuntimeDir(context, subscription.id)' \
  app/src/main/kotlin/top/yukonga/mishka/viewmodel/SubscriptionViewModel.kt \
  'ROOT file provider operations resolve the runtime/{uuid} destination'
assert_contains 'RootHelper.copyFileAsRoot(target.profileFile.absolutePath, target.runtimeFile.absolutePath)' \
  app/src/main/kotlin/top/yukonga/mishka/ui/screen/provider/ProviderScreen.kt \
  'uploaded or edited file providers are synchronized into ROOT runtime'
assert_contains 'isFileProxyProvider' \
  app/src/main/kotlin/top/yukonga/mishka/ui/screen/provider/ProviderScreen.kt \
  'file proxy providers use edit/upload actions instead of refresh'
assert_contains 'provider.type.lowercase()' \
  app/src/main/kotlin/top/yukonga/mishka/ui/screen/provider/ProviderScreen.kt \
  'provider source is shown as lowercase http/file instead of generic Proxy'
assert_contains 'NON_SELECTABLE_PROXY_TYPES' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/ConfigFormPanel.kt \
  'proxy candidates filter Mihomo built-in and unusable proxy types'
assert_contains '"proxies" -> host.candidates("selectable-proxies", exclude = setOf(name))' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/FlowFormPages.kt \
  'proxy-group member choices use only selectable proxies'
assert_contains 'TetherInterfaceEditDialog(' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/ConfigFormPanel.kt \
  'eBPF downstream interface uses the current-interface detector'
assert_contains 'colors = ButtonDefaults.textButtonColorsPrimary()' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/FormDialogs.kt \
  'visual configuration dialogs use native override primary confirm styling'
assert_contains 'if (field.type == FormFieldType.BOOL && !tri) setSwitchValue(!switchShown)' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/ConfigFormPanel.kt \
  'tapping anywhere on a boolean switch row toggles it'
assert_contains '0.0.0.0' \
  app/src/main/res/values-zh-rCN/strings.xml \
  'Chinese external-control hint warns about wildcard unauthenticated exposure'

# --- 外部面板（Web 界面）---
assert_contains 'entry<Route.Panel>(swipeDismiss = NavSwipeDirection.None)' \
  app/src/main/kotlin/top/yukonga/mishka/ui/navigation/AppNavigation.kt \
  'external panel route is registered without swipe-to-dismiss'
assert_contains 'onNavigatePanel = { navigator.push(Route.Panel) }' \
  app/src/main/kotlin/top/yukonga/mishka/ui/navigation/AppNavigation.kt \
  'home screen can navigate to the external panel'
assert_contains 'onNavigatePanel = onNavigatePanel' \
  app/src/main/kotlin/top/yukonga/mishka/ui/screen/home/HomeScreen.kt \
  'home screen forwards the external panel callback'
assert_contains '<string name="home_panel">' \
  app/src/main/res/values/strings.xml \
  'external panel quick-entry string exists in the default locale'
assert_contains '<string name="panel_title">' \
  app/src/main/res/values-zh-rCN/strings.xml \
  'external panel strings exist in the Chinese locale'
assert_contains 'PANEL_URL_ZASHBOARD = "http://board.zash.run.place"' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelEntry.kt \
  'built-in Zashboard panel URL matches box.app'
assert_contains 'PANEL_URL_METACUBEXD = "https://metacubex.github.io/metacubexd"' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelEntry.kt \
  'built-in MetaCubeXD panel URL matches box.app'
assert_contains '"http://${status.externalController}/ui"' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelScreen.kt \
  'local panel URL is resolved from the running controller address'
assert_contains 'mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelWebView.kt \
  'panel WebView allows http calls from https panel pages'
assert_contains 'NavigationBackHandler(' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelScreen.kt \
  'system back is routed into the panel page history first'
assert_contains 'MiuixIcons.ChevronBackward' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelScreen.kt \
  'panel top bar back icon is the box.app chevron'
assert_contains 'MiuixIcons.Tune' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelScreen.kt \
  'panel list opens from the box.app sliders icon (right-2 slot)'
assert_contains 'MiuixTheme.colorScheme.surface' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelScreen.kt \
  'panel top bar background follows the app theme surface color'
assert_contains 'MiuixTheme.colorScheme.onSurface' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelScreen.kt \
  'panel top bar title and icons use the theme on-surface color'
assert_not_contains 'forceLightStatusBars' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelScreen.kt \
  'panel screen leaves status-bar appearance to the app theme'
assert_not_contains 'forceLightStatusBars' \
  app/src/main/kotlin/top/yukonga/mishka/MainActivity.kt \
  'main activity enforces status-bar appearance from theme only'
assert_contains 'WebViewPreloader' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelWebView.kt \
  'panel WebView is prewarmed box.app-style for instant entry'
assert_contains 'PanelWebViewCache' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelWebView.kt \
  'panel WebView instance survives back/re-enter instead of reloading'
assert_contains 'val (webView, fresh) = PanelWebViewCache.acquire' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelWebView.kt \
  'only a freshly created WebView loads URL (re-enter never cold-reloads)'
assert_contains 'cachedHost?.get() === host' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelWebView.kt \
  'reuse liveness checks recorded host identity, never webView.context (app-context pitfall)'
assert_contains 'history.go(' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelWebView.kt \
  're-entering returns to the panel home via a history walk, not a page reload'
assert_contains 'loadUrl(entryUrl)' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelWebView.kt \
  'a fresh WebView loads the panel entry URL'
assert_contains 'clearHistoryAfterNextPageFinished' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelWebView.kt \
  'URL change clears history so back never walks into the previous panel'
assert_contains 'doUpdateVisitedHistory' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelWebView.kt \
  'canGoBack tracks SPA history changes so back dispatch matches box.app'
assert_contains 'released.onPause()' \
  app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelWebView.kt \
  'leaving the panel only pauses the retained WebView'

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
