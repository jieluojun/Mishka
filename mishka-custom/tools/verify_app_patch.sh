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

# --- 与 mihomo_box 对齐的三处交互：拖动排序 / 图标按钮 / 并发测速 ---
assert_contains 'internal fun rememberDragSortState(' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'hold-and-drag sorting helper is present'
assert_contains 'internal fun LazyItemScope.dragSortItem(' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'lazy hosts attach the per-item drag modifier (FLIP only for non-dragged rows)'
assert_contains 'items(dragSort.order, key = { it }) { i ->' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/FlowFormPages.kt \
  'sequence list rows render the live drag order and keep the drag handle'
assert_contains 'DragSortRow(' \
  app/src/main/kotlin/top/yukonga/mishka/ui/screen/overrides/SubscriptionOverridesScreen.kt \
  'override list uses the drag-sort handle'
assert_contains 'MiniIconButton(' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/P3FormEditors.kt \
  'structured editors delete rows through mini icon buttons'
assert_contains 'icon = MiuixIcons.Location,' \
  app/src/main/kotlin/top/yukonga/mishka/custom/anchor/AnchorPanel.kt \
  'anchor card actions use mini icon buttons'
assert_not_contains 'ItemMenuDialog' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/FormDialogs.kt \
  'the up/down action menu is fully removed'
assert_not_contains 'override_move_up' \
  app/src/main/res/values/strings.xml \
  'unused move-up strings are removed (default locale)'
assert_contains 'suspend fun getGroupDelay(' \
  app/src/main/kotlin/top/yukonga/mishka/data/api/MihomoApiClient.kt \
  'group-level delay endpoint is wired'
assert_contains 'val semaphore = Semaphore(BATCH_CONCURRENCY)' \
  app/src/main/kotlin/top/yukonga/mishka/viewmodel/ProxyViewModel.kt \
  'concurrent batch test uses the 8-lane pool'
assert_contains 'private val batchTesting = mutableSetOf<String>()' \
  app/src/main/kotlin/top/yukonga/mishka/viewmodel/ProxyViewModel.kt \
  'batch test re-entrancy lock is wired'

# --- 结构检查：尾随 lambda 调用的函数，最后参数必须是函数类型（无编译器环境的防回归）---
# 教训：MiniIconButton 曾把 onClick 排在 modifier 之前，47 处 `MiniIconButton(icon, desc) { … }`
# 的尾随 lambda 全部绑到 Modifier 上，CI 报 108 条 "No value passed for parameter 'onClick'"。
check_last_param_is_lambda() {
  local fn="$1" path="$2" last
  last="$(awk -v fn="$fn" '$0 ~ ("^(internal |private )?fun " fn "\\(") {f=1; next} f { if ($0 ~ /^\)/) exit; if ($0 ~ /[^ \t]/ && $0 !~ /^[ \t]*\/\//) last=$0 } END{print last}' "$REPO/$path" | tr -s ' ')"
  if [[ "$last" == *"->"* || "$last" == *"@Composable"* ]]; then
    echo "ok  $fn 的最后一个参数是函数类型（尾随 lambda 调用可用）"
  else
    echo "FAIL $fn 的最后一个参数不是函数类型：${last:0:80}" >&2
    echo '     调用方用尾随 lambda 时会绑到最后一个参数上——K2 会报 No value passed for parameter …' >&2
    fail=1
  fi
}
# --- 拖动排序：与参考实现同构（列表内实时换位 = insertBefore + 虚线描边 + FLIP + 边缘翻滚）---
assert_contains 'PathEffect.dashPathEffect' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'dragged row draws the reference dashed outline'
assert_contains 'val order: List<Int> get() = orderState' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'drag state exposes the live visual order the hosts render'
assert_contains 'orderState.removeAt(cur)' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'crossing a row swaps the dragged row in-list (reference insertBefore)'
assert_contains 'if (cur < at) at -= 1' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'insert index is corrected for removing the dragged row first'
assert_contains 'if (curTop != null && curH != null && fingerY >= curTop && fingerY <= curTop + curH) return' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'finger over the dragged row itself is a no-op (no swap oscillation)'
assert_contains 'placementSpec = FlipSpec' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'non-dragged rows get the reference FLIP easing, dragged row stays instant'
assert_contains 'internal var flipSuppressed by mutableStateOf(false)' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'the commit frame suppresses the FLIP (keys renumber together with the data)'
assert_contains 'val v = velMin + (depth / edge) * velRange' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'edge auto-scroll uses the reference 1..14dp/frame depth ramp'
assert_not_contains 'checkpointIfRecycled' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'no mid-drag doc writes anymore (that was the stutter + misalignment source)'
assert_not_contains 'recycleRows' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/FlowFormPages.kt \
  'lazy sequence list needs no recycle-aware checkpointing'
assert_contains 'items(dragSort.order, key = { displayed.getOrNull(it) ?: it })' \
  app/src/main/kotlin/top/yukonga/mishka/ui/screen/overrides/SubscriptionOverridesScreen.kt \
  'override list renders the live drag order (profile ids stay the keys)'
assert_contains 'dragSort.order.forEach { index ->' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/ConfigFormPanel.kt \
  'listener list renders the live drag order'
assert_contains 'dragSort.order.forEachIndexed { pos, index ->' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/FormDialogs.kt \
  'string-list dialog renders the live drag order (line numbers follow the slot)'
assert_contains 'dnsValueSort.order.forEach { index ->' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/P3FormEditors.kt \
  'structured editor rows render the live drag order'
assert_contains 'MiniIconButton(MiuixIcons.Edit, "编辑") { onOpen(e.key) }' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/FlowFormPages.kt \
  'provider / rule-set list pencil opens the full editor (edit all), not rename'
assert_not_contains 'MiniIconButton(MiuixIcons.Edit, "改名") { renaming = e.key }' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/FlowFormPages.kt \
  'mapping list rows no longer rename from the pencil button'

check_last_param_is_lambda MiniIconButton app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt
check_last_param_is_lambda DragSortRow app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt
check_last_param_is_lambda rememberDragSortState app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt

# 全树版检查（custom/ 下所有尾随 lambda 调用）；没有 python3 就跳过，上面的 bash 守护仍在
if command -v python3 >/dev/null 2>&1 && [[ -f "$ROOT/tools/check_trailing_lambda.py" ]]; then
  if python3 "$ROOT/tools/check_trailing_lambda.py" --repo "$REPO"; then
    echo "ok  尾随 lambda 全树检查通过"
  else
    echo "FAIL 尾随 lambda 全树检查发现不匹配（见上方清单）" >&2
    fail=1
  fi
else
  echo "warn 没有 python3，跳过 tools/check_trailing_lambda.py（bash 守护仍在）"
fi

# --- web 界面已完全移除（防回归）---
assert_not_contains 'PanelScreen' \
  app/src/main/kotlin/top/yukonga/mishka/ui/navigation/AppNavigation.kt \
  'web panel is fully removed from navigation'
assert_not_contains 'onNavigatePanel' \
  app/src/main/kotlin/top/yukonga/mishka/ui/screen/home/QuickEntriesSection.kt \
  'web panel quick entry is fully removed from home'
assert_not_contains 'home_panel' \
  app/src/main/res/values/strings.xml \
  'web panel strings are fully removed (default locale)'
assert_not_contains 'home_panel' \
  app/src/main/res/values-zh-rCN/strings.xml \
  'web panel strings are fully removed (Chinese locale)'
assert_not_contains 'home_panel' \
  app/src/main/res/values-zh-rTW/strings.xml \
  'web panel strings are fully removed (Taiwan locale)'
assert_not_contains 'home_panel' \
  app/src/main/res/values-ru/strings.xml \
  'web panel strings are fully removed (Russian locale)'
if [ -d "$REPO/app/src/main/kotlin/top/yukonga/mishka/custom/panel" ]; then
  echo "FAIL web panel package still exists" >&2; fail=1
else
  echo "ok  web panel package (custom/panel) is gone"
fi

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
