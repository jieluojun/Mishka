#!/usr/bin/env bash
# 校验 app 侧补丁：apply --check → apply → 逐文件比对基线 blob → apply -R → 工作区必须回到干净。
#
# 用法: tools/verify_app_patch.sh --repo <Mishka 仓库> [--series]
#
# 要求仓库处在补丁基线 commit（BASELINE.txt 的 upstream_commit）且工作区干净；
# 脚本跑完不会留下任何改动（成功与失败都还原）。
#
# --series：0001 验收通过后，按 0002 → 0003 → 0004 → 0005 → 0006 → 0007 → 0008 的顺序叠加，
#           逐条跑 0004 专属断言（字段整理 / DNS maplist 拖动 / 规则序号 / 批量测速），
#           0005 面板 / 规则省略号断言，再检查 0006 编辑间距 / 连接代理类型 / WebView 缓存，
#           0007 字段整理按钮位置与缓存页透明黑屏修复，0008 重进恢复所选面板入口页，最后逆序还原。
#           0005 还会顺带跑 tools/panel/check_panel_refs.py（面板字符串键 × 4 locale、图标名对表）。
#           不加 --series 只验 0001（与原行为一致）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PATCH="$ROOT/patches/app/0001-anchor-panel.patch"
BASELINE="$ROOT/patches/app/BASELINE.txt"

REPO=""
SERIES=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    --series) SERIES=1; shift ;;
    -h|--help) sed -n '2,14p' "${BASH_SOURCE[0]}"; exit 0 ;;
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

# 基线 blob 记录的是**只应用 0001 之后**的状态（BASELINE.txt 生成时点），所以要在叠 0002+ 之前比。
echo "--- 逐文件比对基线 blob（0001 之后的状态） ---"
while read -r _ expected path; do
  actual="$(git -C "$REPO" hash-object "$path")"
  if [[ "$actual" == "$expected" ]]; then
    echo "ok  $path"
  else
    echo "FAIL $path 期望 $expected 实际 $actual" >&2; fail=1
  fi
done < <(grep -E '^blob_(new|after)=' "$BASELINE" | sed 's/^blob_[a-z]*=/x /')

if grep -Fq 'val mihomoBuildTags = listOf("mishka", "with_gvisor", "with_ebpf")' "$REPO/app/build.gradle.kts"; then
  echo "ok  Android mihomo build enables eBPF and the native process resolver (no callback-less cmfa tag)"
else
  echo "FAIL Android mihomo build tags would disable VPN/ROOT PROCESS-NAME support" >&2
  fail=1
fi

assert_contains 'ExtraSafePathFunc = isValidationProvidersPath' \
  app/src/main/native/mishka_core/safe_paths.go \
  'validation providers dirs (processing/ and imported/<uuid>/) pass the kernel safe-path check'

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
assert_contains 'findProcessMode = userOverride.findProcessMode' \
  app/src/main/kotlin/top/yukonga/mishka/service/RuntimeOverrideBuilder.kt \
  'profile find-process-mode survives when Meta setting is not modified'
assert_contains 'getConnectionOwnerUid(protocol, source, target)' \
  app/src/main/kotlin/top/yukonga/mishka/service/AndroidProcessResolver.kt \
  'VPN process rules use Android authoritative socket-owner lookup'
assert_contains 'installAndroidProcessResolver(androidProcessResolver)' \
  app/src/main/native/mishka_core/runtime.go \
  'standalone mihomo process installs the Android package resolver bridge'
assert_contains 'add("--android-process-resolver"); add(processResolverAddress)' \
  app/src/main/kotlin/top/yukonga/mishka/service/MihomoRunner.kt \
  'VPN and ROOT cores receive the process resolver endpoint'
assert_not_contains 'findProcessMode = userOverride.findProcessMode ?: "off"' \
  app/src/main/kotlin/top/yukonga/mishka/service/RuntimeOverrideBuilder.kt \
  'runtime override no longer silently forces process lookup off'
assert_contains 'startProxy(subscriptionId, preCleaned = true)' \
  app/src/main/kotlin/top/yukonga/mishka/service/MishkaRootService.kt \
  'ROOT restart skips duplicate orphan/rule cleanup'
assert_contains 'while kill -0 $pid 2>/dev/null' \
  app/src/main/kotlin/top/yukonga/mishka/service/RootHelper.kt \
  'ROOT process shutdown polls inside one su session'
assert_contains 'single-pass fast path' \
  app/src/main/kotlin/top/yukonga/mishka/service/RootTetherHijacker.kt \
  'ROOT tether teardown no longer launches verify/retry su sessions'
assert_contains 'syncDirectoryContentsAsRoot' \
  app/src/main/kotlin/top/yukonga/mishka/service/ProfileFileOps.kt \
  'ROOT restart preserves provider caches and incrementally refreshes runtime'
assert_contains 'teardownActiveRootRules(storage)' \
  app/src/main/kotlin/top/yukonga/mishka/service/MishkaRootService.kt \
  'ROOT normal lifecycle tears down only the active interception mode'
assert_contains 'ROOT_LAST_STOP_CLEAN' \
  app/src/main/kotlin/top/yukonga/mishka/service/MishkaRootService.kt \
  'ROOT clean stop lets the next start skip recovery sweeps'
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
assert_contains 'dragTo(change.position.y + containerTop)' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'the container gesture resolves the finger Y in window coordinates (container-local + window origin; reference e.clientY, never accumulated)'
assert_contains 'fun dragTo(windowY: Float)' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'dragTo takes an already-resolved window Y (no node-local delta accumulation)'
assert_not_contains 'dragAmount' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'no local-delta accumulation: the dragged row jumps a whole row height per swap and the auto-scroll moves the content under the finger, so node-local deltas contain phantom motion (it drifted the finger Y by one row per swap -> swap flicker + stray up-scroll)'
# 七版（2026-10-07 四轮实机后重写）：手势从「会被 lazy 列表回收的把手节点」挪到「不会被回收的容器节点」。
# 把手只量窗口矩形；容器 pointerInput 在 DOWN 命中判定，命中就在 PointerEventPass.Initial 上逐事件消费
# 整条流——Initial 是比 Main（scrollable 的 slop 检测所在相位）更早的全局相位，容器因此永远先于外层
# scrollable 消费，抢流无从发生，且不依赖 userScrollEnabled 的重组时序；容器节点不被回收，手势协程活到
# 底（修「快速拖动被打断、慢速没事」——慢速不触发回收）。detectDragGestures / pointerInteropFilter 仍整段移除。
assert_contains 'val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'the container grabs the stream on DOWN on the Initial pass (beats the Main-pass scrollable slop detection)'
assert_contains '.pointerInput(Unit) {' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'the gesture lives on the stable container node, not the recyclable handle node (fixes fast-drag interruption)'
assert_contains 'private fun hitHandle(x: Float, y: Float): Int' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'DOWN is hit-tested against the handle window rects to decide drag vs. normal scroll'
# §7 同类 CI 报错的防回归：v6 重写连踩两次 import 坑（CI 两轮各报一次）：
#  1) pointerInput 的 import 漏补（跨包工具只管 custom.* 符号，androidx 的它看不见）；
#  2) awaitFirstDown / forEachGesture 不在 ui.input.pointer 而在 foundation.gestures，
#     awaitPointerEventScope / awaitPointerEvent 是接口成员、根本不能 import。
assert_contains 'import androidx.compose.ui.input.pointer.pointerInput' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'pointerInput is imported (v7 container gesture uses it; missing import broke CI once)'
assert_contains 'import androidx.compose.foundation.gestures.awaitFirstDown' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'awaitFirstDown is imported from foundation.gestures (it is NOT in ui.input.pointer)'
assert_contains 'import androidx.compose.foundation.gestures.forEachGesture' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'forEachGesture is imported from foundation.gestures (it is NOT in ui.input.pointer)'
assert_contains 'import androidx.compose.ui.input.pointer.PointerEventPass' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'PointerEventPass is imported (v7 container gesture consumes on the Initial pass)'
assert_not_contains 'import androidx.compose.ui.input.pointer.await' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'no bogus ui.input.pointer.await* imports (those symbols are members or live in foundation.gestures)'
assert_contains 'startDrag(hit, down.position.y + containerTop)' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'press on a handle = immediate drag start at the DOWN window Y'
assert_contains 'if (up) break' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'the drag loop ends only when the pressed pointer goes up (lift or framework cancel)'
assert_not_contains 'detectDragGestures(' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'the shared-arbitration drag detector is gone (it let the outer scrollable cancel/steal the drag)'
assert_not_contains 'pointerInteropFilter(' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'the interop-filter path is gone too (its suppressMovementConsumption let the outer scrollable steal the stream on-device)'
assert_contains 'userScrollEnabled = dragSort.dragging < 0' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/FlowFormPages.kt \
  'sequence list disables user scrolling for the whole drag (outer scrollable exits arbitration)'
assert_contains 'userScrollEnabled = dragSort.dragging < 0' \
  app/src/main/kotlin/top/yukonga/mishka/ui/screen/overrides/SubscriptionOverridesScreen.kt \
  'override list disables user scrolling for the whole drag'
assert_contains 'verticalScroll(scrollState, enabled = dragSort.dragging < 0)' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/FormDialogs.kt \
  'string-list dialog disables user scrolling for the whole drag'
assert_contains 'verticalScroll(dnsValueScroll, enabled = dnsValueSort.dragging < 0)' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/P3FormEditors.kt \
  'dns value list disables user scrolling for the whole drag'
# boundsInWindow 是 androidx.compose.ui.layout 包里的扩展函数（不是接口成员）：
# 少了 import，K2 报 Unresolved reference —— 与曾经漏 import dragSortItem 的 CI 失败同一类。
assert_contains 'import androidx.compose.ui.layout.boundsInWindow' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'boundsInWindow is imported (it is an extension, not a LayoutCoordinates member; used by the handle hit-test)'
assert_contains 'import androidx.compose.ui.layout.LayoutCoordinates' \
  app/src/main/kotlin/top/yukonga/mishka/custom/forms/DragSort.kt \
  'LayoutCoordinates is imported for the live handle coordinates map'
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

# --- 跨包 import 检查（防「调用点对、import 漏」类编译错误）---
# 教训：SubscriptionOverridesScreen 调用 dragSortItem 但漏 import，CI 报
# Unresolved reference 'dragSortItem'。没有编译器时用本脚本兜底。
if command -v python3 >/dev/null 2>&1 && [[ -f "$ROOT/tools/check_cross_package_imports.py" ]]; then
  if python3 "$ROOT/tools/check_cross_package_imports.py" --repo "$REPO"; then
    echo "ok  跨包 import 全树检查通过"
  else
    echo "FAIL 跨包 import 全树检查发现缺失（见上方清单）" >&2
    fail=1
  fi
else
  echo "warn 没有 python3，跳过 tools/check_cross_package_imports.py"
fi

# 注：这里原先有一组「web 界面已完全移除（防回归）」的断言。0005 起按用户要求把面板 / Web 界面
# 加了回来（custom/panel + Route.WebPanel + 主页入口 + home_panel_* 字符串），那组否定断言已被
# 0005 series 段里的肯定断言取代——再留着只会和 0005 的验收目标打架。

# ---------------------------------------------------------------- 后续补丁（--series）
EXTRA_PATCHES=()
if [[ $SERIES -eq 1 ]]; then
  for n in 0002 0003 0004 0005; do
    p="$(find "$ROOT/patches/app" -maxdepth 1 -name "$n-*.patch" | sort | head -1)"
    [[ -n "$p" ]] && EXTRA_PATCHES+=("$p")
  done

  echo "--- 后续补丁：sha256 与基线比对 ---"
  for p in "${EXTRA_PATCHES[@]}"; do
    name="$(basename "$p" | cut -d- -f1)"
    expected="$(sed -n "s/^patch${name}_sha256=//p" "$BASELINE" | head -1)"
    actual="$(sha256sum "$p" | cut -d' ' -f1)"
    if [[ -z "$expected" ]]; then
      echo "warn BASELINE.txt 里没有 patch${name}_sha256，跳过 $(basename "$p") 的 sha 校验"
    elif [[ "$actual" == "$expected" ]]; then
      echo "ok  $(basename "$p") sha256 与基线一致"
    else
      echo "FAIL $(basename "$p") sha256 与基线不一致：$actual != $expected" >&2; fail=1
    fi
  done

  echo "--- 后续补丁：应用（0002 → 0003 → 0004 → 0005） ---"
  for p in "${EXTRA_PATCHES[@]}"; do
    git -C "$REPO" apply --check "$p" || { echo "FAIL $(basename "$p") 无法应用" >&2; fail=1; }
    git -C "$REPO" apply "$p" && echo "ok  已应用 $(basename "$p")"
  done

  # 静态检查在整个序列（0001–0004）上再跑一遍：上面那次只看到 0001 之后的树。
  echo "--- 0001–0005 全序列的静态检查 ---"
  if command -v python3 >/dev/null 2>&1 && [[ -f "$ROOT/tools/check_cross_package_imports.py" ]]; then
    python3 "$ROOT/tools/check_cross_package_imports.py" --repo "$REPO" || {
      echo "FAIL 跨包 import 检查（0001–0005 全序列）" >&2; fail=1; }
  fi
  if command -v python3 >/dev/null 2>&1 && [[ -f "$ROOT/tools/check_trailing_lambda.py" ]]; then
    python3 "$ROOT/tools/check_trailing_lambda.py" --repo "$REPO" || {
      echo "FAIL 尾随 lambda 检查（0001–0005 全序列）" >&2; fail=1; }
  fi

  # ---- 0004：字段整理（ConfigTidy）----
  CT="app/src/main/kotlin/top/yukonga/mishka/custom/forms/ConfigTidy.kt"
  assert_contains 'internal object ConfigTidy {' "$CT" \
    'field-tidy core object exists (ported from mihomo_box tidyMihomoConfig)'
  assert_contains 'fun apply(source: String): Outcome' "$CT" \
    'apply() is the single entry the editor button calls'
  assert_contains 'internal sealed interface Outcome {' "$CT" \
    'apply() reports a structured outcome instead of guessing from the string'
  assert_contains 'data class Tidied(val text: String) : Outcome' "$CT" \
    'tidied text is carried in the outcome'
  assert_contains 'data class SourceInvalid(val detail: String) : Outcome' "$CT" \
    'YAML syntax errors are reported before any rewrite (reference: 源码存在 YAML 语法错误)'
  assert_contains 'data class VerifyFailed(val detail: String) : Outcome' "$CT" \
    'post-tidy structural re-check can fail loudly instead of writing a broken file'
  assert_contains 'val eol = if (text.contains("\r\n")) "\r\n" else "\n"' "$CT" \
    'CRLF files keep CRLF (documented divergence from the JS reference, which joins with LF only)'

  # ---- 0004：配置页入口按钮 ----
  FMES="app/src/main/kotlin/top/yukonga/mishka/ui/screen/settings/FileManagerEditorScreen.kt"
  assert_contains 'import top.yukonga.mishka.custom.forms.ConfigTidy' "$FMES" \
    'the editor screen imports ConfigTidy (missing import = same class of CI failure as dragSortItem)'
  assert_contains 'import top.yukonga.miuix.kmp.icon.extended.Sort' "$FMES" \
    'MiuixIcons.Sort is imported (整理配置字段顺序 icon, left of 配置表单)'
  assert_contains 'MiuixIcons.Sort,' "$FMES" \
    'the tidy button renders the Sort icon'
  assert_contains 'when (val outcome = ConfigTidy.apply(controller.getText()))' "$FMES" \
    'the button runs ConfigTidy.apply on the live editor buffer'
  assert_contains 'showToast("✅ 已按官方规范整理配置字段顺序")' "$FMES" \
    'success toast matches the reference wording'
  assert_contains 'showToast("配置字段顺序已符合官方规范")' "$FMES" \
    'no-op toast matches the reference wording'
  assert_contains '源码存在 YAML 语法错误，请先修正后再整理' "$FMES" \
    'syntax-error toast is long-form (the user must fix the source first)'
  assert_contains '整理后验证失败：' "$FMES" \
    'verification-failure toast carries the detail'
  python3 - "$REPO/$FMES" <<'PY2' || { echo "FAIL 字段整理按钮不在「配置表单」按钮左侧" >&2; fail=1; }
import sys
s = open(sys.argv[1], encoding="utf-8").read()
sort_at = s.index("MiuixIcons.Sort,")
tune_at = s.index("MiuixIcons.Tune,")
sys.exit(0 if sort_at < tune_at else 1)
PY2
  echo "ok  字段整理按钮在「配置表单」（Tune）按钮左侧"

  # ---- 0004：路由规则数字序号 ----
  FFP="app/src/main/kotlin/top/yukonga/mishka/custom/forms/FlowFormPages.kt"
  assert_contains 'numbered: Boolean = false,' "$FFP" \
    'sequence list can optionally number its rows (default off for the other lists)'
  assert_contains 'numbered = seqPath == TOP_RULES_PATH,' "$FFP" \
    'only the top-level rules page is numbered (子规则 in the reference is a maplist, no .rule-no)'
  assert_contains 'private val TOP_RULES_PATH: YPath = listOf("rules")' "$FFP" \
    'the rules path constant exists'
  assert_contains 'private fun RuleIndexBadge(index: Int) {' "$FFP" \
    'the badge composable mirrors the reference .rule-no pill'
  assert_contains 'text = index.toString(),' "$FFP" \
    'the badge shows the 1-based rule index'
  assert_not_contains 'numbered = true,' "$FFP" \
    'numbering is not hard-wired on (it would leak into the sub-rule lists)'

  # ---- 0004：DNS maplist（按域名分流解析）拖动排序 ----
  P3="app/src/main/kotlin/top/yukonga/mishka/custom/forms/P3FormEditors.kt"
  assert_contains 'val listScroll = rememberScrollState()' "$P3" \
    'maplist dialog owns its scroll state (needed by the drag auto-scroll)'
  assert_contains 'val dragSort = rememberDragSortState(rows.size)' "$P3" \
    'maplist dialog wires the shared drag-sort state'
  assert_contains '.then(dragSort.containerModifier { listScroll.dispatchRawDelta(it) })' "$P3" \
    'container grabs the drag gesture and drives edge auto-scroll'
  assert_contains '.verticalScroll(listScroll, enabled = dragSort.dragging < 0),' "$P3" \
    'user scrolling is disabled for the whole drag (outer scrollable exits arbitration)'
  assert_contains 'dragSort.order.forEach { index ->' "$P3" \
    'maplist rows render the live drag order'
  # 只看 MapListFieldDialog 自己的函数体（同文件里 HeadersEditorDialog 仍是普通滚动，别误伤）
  python3 - "$REPO/$P3" <<'PY3' || { echo "FAIL maplist（DNS 按域名分流解析）对话框仍是旧的普通滚动实现" >&2; fail=1; }
import re, sys
s = open(sys.argv[1], encoding="utf-8").read()
i = s.index("internal fun MapListFieldDialog(")
m = re.search(r"\n@Composable\ninternal fun ", s[i + 10:])
body = s[i:i + 10 + (m.start() if m else len(s) - i)]
assert "rememberDragSortState" in body, "no drag-sort state in MapListFieldDialog"
assert "verticalScroll(rememberScrollState())" not in body, "old plain-scroll body still there"
PY3
  echo "ok  maplist（DNS 按域名分流解析）对话框已换成拖动排序实现"

  # ---- 0004：整组测速与 mihomo_box testGroupAll 逐条对齐 ----
  API="app/src/main/kotlin/top/yukonga/mishka/data/api/MihomoApiClient.kt"
  VM="app/src/main/kotlin/top/yukonga/mishka/viewmodel/ProxyViewModel.kt"
  assert_contains 'delays[name] = delay' "$API" \
    'getGroupDelay keeps every parseable member verdict, including 0'
  assert_not_contains 'if (delay > 0) delays[name] = delay' "$API" \
    'the old 0-filter is gone (0 = 内核测过但不通 — must not be re-tested in the batch pool)'
  assert_contains 'private fun isSpeedTestable(name: String, type: String): Boolean' "$VM" \
    'isSpeedTestable mirrors the reference isProxySpeedTestable'
  assert_contains 'private val NOT_SPEED_TESTABLE = setOf("reject", "reject-drop", "block", "pass", "pass-rule", "dns")' "$VM" \
    'the untestable built-in policies match the reference list'
  assert_contains 'private fun applyBatchDelays(group: String, verdicts: Map<String, Int>)' "$VM" \
    'per-node verdicts are written straight into that group (reference paint)'
  assert_contains 'private fun setNodesTesting(names: List<String>, testing: Boolean)' "$VM" \
    'per-node testing flags are set/cleared through one helper (no leaked spinners)'
  assert_contains 'val fromGroup = withTimeoutOrNull(GROUP_DELAY_BUDGET_MILLIS)' "$VM" \
    'the group endpoint is still the first (fast) path'
  assert_contains 'name !in answered && name !in inFlight && isSpeedTestable(name, target.nodeTypes[name].orEmpty())' "$VM" \
    'only members the group endpoint did not answer, that are not already testing and are testable, go to the pool'
  assert_contains 'settled.add(nodeName)' "$VM" \
    'finished lanes are recorded so the 400ms loading hint cannot re-light them'
  assert_contains 'val loading = nodes.filter { name ->' "$VM" \
    'the 400ms hint lights only the still-unanswered testable members (reference hintTimer)'

  # ---- 0005：面板 / Web 界面（custom/panel，jieluojun/mihomo_box 同源的 box.app 移植）----
  PE="app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelEntry.kt"
  PST="app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelStore.kt"
  PSC="app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelScreen.kt"
  PSH="app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelSheet.kt"
  PW="app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelWebView.kt"
  assert_contains 'internal const val PANEL_ID_LOCAL = "local"' "$PE" \
    'built-in local panel id matches box.app'
  assert_contains 'internal const val PANEL_URL_ZASHBOARD = "http://board.zash.run.place"' "$PE" \
    'Zashboard built-in url matches box.app'
  assert_contains 'internal const val PANEL_URL_METACUBEXD = "https://metacubex.github.io/metacubexd"' "$PE" \
    'MetaCubeXD built-in url matches box.app'
  assert_contains 'val url: String?' "$PE" \
    'the local panel has no compile-time url (it is derived from the running core)'
  assert_contains 'private const val PREFS_NAME = "panel_cache"' "$PST" \
    'panel prefs file matches box.app (panel_cache)'
  assert_contains 'private const val KEY_LIST = "panel_list_v1"' "$PST" \
    'custom panel list key matches box.app'
  assert_contains 'private const val KEY_SELECTED = "panel_selected_id_v1"' "$PST" \
    'selected panel key matches box.app'
  assert_contains 'private const val KEY_LOCAL_URL = "panel_url_v1"' "$PST" \
    'cached local url key matches box.app (no blank first screen after a cold start)'
  assert_contains 'fun cachedLocalUrl(context: Context): String?' "$PST" \
    'the last resolved local url is cached for the stopped-core case'
  assert_contains '"http://${status.externalController}/ui"' "$PSC" \
    'the local panel is the core external-controller at /ui (box.app getPanelUrl equivalent)'
  assert_contains 'status.state == ProxyState.Running' "$PSC" \
    'the live controller address is only trusted while the core is really running'
  assert_contains 'PanelStore.cacheLocalUrl(context, it)' "$PSC" \
    'a freshly resolved local url is cached'
  assert_contains 'PanelStore.cachedLocalUrl(context)' "$PSC" \
    'a stopped core falls back to the cached local url'
  assert_contains 'PanelStore.saveSelectedId(context, panel.id)' "$PSC" \
    'switching panels persists the selection'
  assert_contains 'sessionKey += 1' "$PSC" \
    'switching panels rebuilds the WebView session (no leaked login state)'
  assert_contains 'hideUntilCommitVisible = true' "$PSC" \
    'the web area hides until the content is committed (box.app anti white-flash)'
  assert_contains 'resetHistoryOnUrlChange = true' "$PSC" \
    'switching panels clears web history (back does not walk the previous panel)'
  assert_contains 'isBackEnabled = canGoBack' "$PSC" \
    'system back walks web history first, then leaves the screen'
  assert_contains 'clearWebViewAppData(context)' "$PSC" \
    'the top bar clear-data action is wired to the webview wipe'
  assert_contains 'if (!panel.isBuiltIn)' "$PSH" \
    'built-in panels cannot be deleted, custom ones can'
  assert_contains 'addError = urlInvalid' "$PSH" \
    'custom panel urls are validated before they are stored'
  assert_contains 'addJavascriptInterface(jsBridge, "MishkaAndroid")' "$PW" \
    'blob:/data: exports are handed to the JS bridge (DownloadListener cannot see them)'
  assert_contains 'blob:' "$PW" \
    'the download hook intercepts blob:/data: anchors'
  assert_contains 'clearWebViewAppData' "$PW" \
    'the wipe helper is declared next to the webview it wipes'
  assert_contains 'mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW' "$PW" \
    'mixed content is allowed (https panels talking to an http controller)'
  assert_contains 'isAlgorithmicDarkeningAllowed = isDark' "$PW" \
    'web page dark mode follows the app theme'
  assert_contains 'requestDisallowInterceptTouchEvent(true)' "$PW" \
    'the webview keeps vertical scroll gestures'
  assert_contains 'override fun onShowFileChooser(' "$PW" \
    'file uploads inside a panel open the system picker'
  assert_contains 'Intent.ACTION_CREATE_DOCUMENT' "$PW" \
    'panel downloads go through SAF save-as'
  assert_contains 'internal object WebViewPreloader {' "$PW" \
    'two-phase webview prewarm (box.app same) keeps re-entry instant'
  assert_contains 'WebViewPreloader.take() ?: WebView(ctx)' "$PW" \
    'every entry builds a fresh webview, taking the prewarmed instance when ready'

  RT="app/src/main/kotlin/top/yukonga/mishka/ui/navigation/Route.kt"
  NAV="app/src/main/kotlin/top/yukonga/mishka/ui/navigation/AppNavigation.kt"
  QE="app/src/main/kotlin/top/yukonga/mishka/ui/screen/home/QuickEntriesSection.kt"
  HS="app/src/main/kotlin/top/yukonga/mishka/ui/screen/home/HomeScreen.kt"
  MA="app/src/main/kotlin/top/yukonga/mishka/MainActivity.kt"
  assert_contains 'data object Panel : Route' "$RT" \
    'the panel is a @Serializable route (back stack survives process death)'
  assert_contains 'import top.yukonga.mishka.custom.panel.PanelScreen' "$NAV" \
    'AppNavigation imports the panel screen (missing import = Unresolved reference)'
  assert_contains 'entry<Route.Panel>(swipeDismiss = NavSwipeDirection.None) {' "$NAV" \
    'the route is registered with swipe-dismiss off (web history owns the edge gesture)'
  assert_contains 'onNavigatePanel = { navigator.push(Route.Panel) },' "$NAV" \
    'the home entry pushes the panel route'
  assert_contains 'onNavigatePanel: () -> Unit = {},' "$QE" \
    'the tools section exposes the panel callback'
  assert_contains 'title = stringResource(R.string.home_panel)' "$QE" \
    'the entry card is titled 面板 (box.app home_quick_panel_title)'
  assert_contains 'subtitle = stringResource(R.string.home_panel_subtitle)' "$QE" \
    'the entry card subtitle is Web 界面 (box.app home_quick_panel_subtitle)'
  assert_contains 'onNavigatePanel = onNavigatePanel,' "$HS" \
    'HomeScreen passes the callback into the tools section'
  assert_contains 'WebViewPreloader.preload(this)' "$MA" \
    'the webview prewarm is kicked off from MainActivity.onCreate'
  for loc in values values-zh-rCN values-zh-rTW values-ru; do
    assert_contains 'name="home_panel"' "app/src/main/res/$loc/strings.xml" \
      "panel home entry strings present ($loc)"
    assert_contains 'name="panel_local_name"' "app/src/main/res/$loc/strings.xml" \
      "panel screen strings present ($loc)"
  done
  # 入口必须排在「工具」分组的网格之后（用户要求：工具下方）
  python3 - "$REPO/$QE" <<'PY5' || { echo "FAIL 面板入口不在「工具」网格下方" >&2; fail=1; }
import sys
s = open(sys.argv[1], encoding="utf-8").read()
sys.exit(0 if s.index("R.string.home_dns") < s.index("R.string.home_panel") else 1)
PY5
  echo "ok  面板入口排在「工具」网格下方"
  # 面板是纯前端页面，代理没起也能进（不跟其它入口一起受 isRunning 门控）
  python3 - "$REPO/$QE" <<'PY8' || { echo "FAIL 面板入口被 isRunning 门控了" >&2; fail=1; }
import sys
s = open(sys.argv[1], encoding="utf-8").read()
i = s.index("R.string.home_panel")
sys.exit(0 if "isRunning = true," in s[i - 200:i + 400] else 1)
PY8
  echo "ok  面板入口不受「代理运行中」门控"
  # 面板文件不许 import 纯逻辑文件之外的东西混进来（tools/panel 的静态检查要能独立跑）
  if command -v python3 >/dev/null 2>&1; then
    python3 "$ROOT/tools/panel/check_panel_refs.py" --repo "$REPO" || {
      echo "FAIL 面板字符串 / 图标引用检查（0005）" >&2; fail=1; }
  fi

  # ---- 0005：路由规则匹配值过长用省略号 ----
  CFP="app/src/main/kotlin/top/yukonga/mishka/custom/forms/ConfigFormPanel.kt"
  FFP="app/src/main/kotlin/top/yukonga/mishka/custom/forms/FlowFormPages.kt"
  assert_contains 'titleMaxLines: Int = Int.MAX_VALUE,' "$CFP" \
    'RowCard gained an opt-in title line cap (default unchanged)'
  assert_contains 'summaryMaxLines: Int = Int.MAX_VALUE,' "$CFP" \
    'RowCard gained an opt-in summary line cap (default unchanged)'
  assert_contains 'overflow = TextOverflow.Ellipsis,' "$CFP" \
    'the capped RowCard renders with a trailing ellipsis'
  assert_contains 'fontSize = MiuixTheme.textStyles.headline1.fontSize,' "$CFP" \
    'the content-overload title keeps the BasicComponent title metrics'
  assert_contains 'val summaryColor = BasicComponentDefaults.summaryColor()' "$CFP" \
    'the content-overload summary keeps the BasicComponent colours'
  assert_contains 'rowMaxLines: Int = Int.MAX_VALUE,' "$FFP" \
    'sequence lists can opt into single-line rows (sub-rule lists keep wrapping)'
  assert_contains 'rowMaxLines = 1,' "$FFP" \
    'the rules list caps its rows to one line'
  assert_contains 'titleMaxLines = rowMaxLines,' "$FFP" \
    'the row cap is forwarded to RowCard'
  # endActions 必须是最后一个参数，否则调用点的尾随 lambda 会落到别的参数上
  python3 - "$REPO/$CFP" <<'PY6' || { echo "FAIL RowCard 的 endActions 不再是最后一个参数" >&2; fail=1; }
import sys
s = open(sys.argv[1], encoding="utf-8").read()
i = s.index("internal fun RowCard(")
body = s[i:s.index(") {", i)]
sys.exit(0 if body.rstrip().endswith("endActions: @Composable () -> Unit = {},") else 1)
PY6
  echo "ok  RowCard 的 endActions 仍排在最后（尾随 lambda 语义不变）"
  # 省略号只加在规则页：其它列表（provider / rulesets / 各种 maplist）保持原样
  python3 - "$REPO/$FFP" <<'PY7' || { echo "FAIL rowMaxLines = 1 只应出现在规则列表调用点" >&2; fail=1; }
import sys
s = open(sys.argv[1], encoding="utf-8").read()
sys.exit(0 if s.count("rowMaxLines = 1,") == 1 else 1)
PY7
  echo "ok  省略号只开在规则列表这一处调用点"

  # ---- 0006–0008：编辑 / 连接 / WebView 修复系列 ----
  LATEST_PATCHES=()
  for n in 0006 0007 0008; do
    p="$(find "$ROOT/patches/app" -maxdepth 1 -name "$n-*.patch" | sort | head -1)"
    if [[ -z "$p" ]]; then
      echo "FAIL 缺少 $n 补丁文件" >&2; fail=1; continue
    fi
    LATEST_PATCHES+=("$p")
    expected="$(sed -n "s/^patch${n}_sha256=//p" "$BASELINE" | head -1)"
    actual="$(sha256sum "$p" | cut -d' ' -f1)"
    if [[ "$actual" == "$expected" && -n "$expected" ]]; then
      echo "ok  $(basename "$p") sha256 与基线一致"
    else
      echo "FAIL $(basename "$p") sha256 不一致：$actual != $expected" >&2; fail=1
    fi
    if git -C "$REPO" apply --check "$p"; then
      git -C "$REPO" apply "$p" && echo "ok  已应用 $(basename "$p")"
    else
      echo "FAIL $(basename "$p") 无法应用" >&2; fail=1; break
    fi
  done

  echo "--- 0006–0008 修复断言 ---"
  FFP="app/src/main/kotlin/top/yukonga/mishka/custom/forms/FlowFormPages.kt"
  CONN="app/src/main/kotlin/top/yukonga/mishka/ui/screen/connection/ConnectionScreen.kt"
  PW="app/src/main/kotlin/top/yukonga/mishka/custom/panel/PanelWebView.kt"
  EDITOR="app/src/main/kotlin/top/yukonga/mishka/ui/screen/settings/FileManagerEditorScreen.kt"
  assert_contains 'Spacer(Modifier.height(8.dp))' "$FFP" \
    'raw rule editor toggle has the requested vertical gap'
  assert_contains 'val proxyType = meta.type.trim().uppercase()' "$CONN" \
    'connection rows read the inbound proxy type from metadata.type'
  assert_contains 'fun takeCached(sessionKey: Int): WebView?' "$PW" \
    'same-session WebView is cached and reused across re-entry'
  assert_contains 'webView.visibility = WebView.VISIBLE' "$PW" \
    'the WebView stays VISIBLE so Chromium does not pause SPA rendering'
  assert_contains 'fun tidyConfig()' "$EDITOR" \
    'field tidy behavior is extracted and remains available to the toolbar button'
  assert_contains '与文件名同处导航区' "$EDITOR" \
    'the field-tidy button is intentionally placed beside the config filename'
  assert_contains 'val contentCommitted: Boolean = false' "$PW" \
    'WebView tag remembers content commit across Compose screen disposal'
  assert_contains 'markContentCommitted(view)' "$PW" \
    'page commit and page finish persist the committed state on the WebView'
  assert_contains 'Every cached re-entry starts at the configured entry URL' "$PW" \
    'every re-entry resets the cached WebView to its configured start page'
  assert_contains 'contentCommitted = false' "$PW" \
    'the old page commit marker is cleared before loading the start page'
  assert_contains 'if (isReused && resetHistoryOnUrlChange)' "$PW" \
    'cached re-entry clears the previous WebView history'
  assert_contains 'loadUrl(entryUrl)' "$PW" \
    'the configured start URL is loaded on every factory entry'
  assert_contains 'webView.alpha = if (canShowCommittedPage) 1f else 0f' "$PW" \
    'old cached content remains hidden until the new entry page is committed'
  assert_not_contains 'reusableCommittedPage' "$PW" \
    'a committed cached subpage is never shown without reloading the entry URL'
  assert_not_contains 'reusablePendingPage' "$PW" \
    'cached views follow one deterministic entry reload path'
  assert_contains 'tab / 子页' "$PSC" \
    'PanelScreen documents that previous SPA tabs are not retained'
  if python3 - "$REPO/$PW" <<'PY9'
import sys
s = open(sys.argv[1], encoding="utf-8").read()
start = s.index("factory = { ctx ->")
end = s.index("update = { webView ->", start)
factory = s[start:end]
lines = factory.splitlines()
marker_line = next(i for i, line in enumerate(lines)
                   if "Every cached re-entry starts at the configured entry URL" in line)
entry_lines = lines[marker_line:]
load_lines = [i for i, line in enumerate(entry_lines) if line.strip() == "loadUrl(entryUrl)"]
assert len(load_lines) == 1, "entry URL must be loaded exactly once from factory"
load_line = marker_line + load_lines[0]
tag_line = next(i for i, line in enumerate(lines) if "contentCommitted = false" in line)
clear_line = next(i for i, line in enumerate(lines) if "runCatching { clearHistory() }" in line)
assert tag_line < load_line, "reset commit marker before loading"
assert clear_line < load_line, "clear cached history before loading"
assert "if (isReused && resetHistoryOnUrlChange)" in factory
assert "reusableCommittedPage" not in factory and "reusablePendingPage" not in factory
# The final load is an unconditional statement, not nested under cache-hit / reload conditions.
assert lines[load_line].strip() == "loadUrl(entryUrl)"
PY9
  then
    echo "ok  缓存重进先清 history / commit 标记，再无条件重载配置入口 URL"
  else
    echo "FAIL 缓存重进未保证从默认入口页开始" >&2; fail=1
  fi
  assert_contains 'needsCanGoBackSync = isReused' "$PW" \
    'a reattached cached view requests a one-time host canGoBack resync'
  assert_contains 'else if (last?.needsCanGoBackSync == true)' "$PW" \
    'cached-view back state is synchronized without requiring a navigation event'
  assert_not_contains 'androidx.webkit' "$PW" \
    'WebView re-entry fix uses only platform android.webkit APIs'
  assert_not_contains 'androidx.webkit' app/build.gradle.kts \
    'the patch series does not add an AndroidX WebKit dependency'
  assert_not_contains 'androidx.webkit' gradle/libs.versions.toml \
    'the version catalog remains unchanged by the WebView fix'
  if python3 - "$REPO/$EDITOR" <<'PY8'
import sys
s = open(sys.argv[1], encoding="utf-8").read()
nav_start = s.index("navigationIcon = {")
actions_start = s.index("actions = {", nav_start)
nav = s[nav_start:actions_start]
actions = s[actions_start:s.index("val canSave =", actions_start)]
sys.exit(0 if "MiuixIcons.Back" in nav and "MiuixIcons.Sort" in nav and "MiuixIcons.Sort" not in actions else 1)
PY8
  then
    echo "ok  字段整理 Sort 按钮在返回键之后、标题之前，不再占用右侧 actions"
  else
    echo "FAIL 字段整理 Sort 按钮未放进 navigationIcon，或仍留在 actions" >&2; fail=1
  fi

  echo "--- 0006–0008 静态检查 ---"
  if command -v python3 >/dev/null 2>&1 && [[ -f "$ROOT/tools/check_cross_package_imports.py" ]]; then
    python3 "$ROOT/tools/check_cross_package_imports.py" --repo "$REPO" || {
      echo "FAIL 跨包 import 检查（0001–0008 全序列）" >&2; fail=1; }
  fi
  if command -v python3 >/dev/null 2>&1 && [[ -f "$ROOT/tools/check_trailing_lambda.py" ]]; then
    python3 "$ROOT/tools/check_trailing_lambda.py" --repo "$REPO" || {
      echo "FAIL 尾随 lambda 检查（0001–0008 全序列）" >&2; fail=1; }
  fi

  echo "--- 0006–0008 apply -R（逆序还原） ---"
  for ((i = ${#LATEST_PATCHES[@]} - 1; i >= 0; i--)); do
    git -C "$REPO" apply -R "${LATEST_PATCHES[$i]}" && echo "ok  已反向应用 $(basename "${LATEST_PATCHES[$i]}")"
  done

  echo "--- 后续补丁 apply -R（逆序还原） ---"
  for ((i = ${#EXTRA_PATCHES[@]} - 1; i >= 0; i--)); do
    git -C "$REPO" apply -R "${EXTRA_PATCHES[$i]}" && echo "ok  已反向应用 $(basename "${EXTRA_PATCHES[$i]}")"
  done
fi

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

[[ $fail -eq 0 ]] && echo "PASS: app 侧补丁双向可逆、结果与基线逐文件一致（已校验到 0008）" || exit 1
