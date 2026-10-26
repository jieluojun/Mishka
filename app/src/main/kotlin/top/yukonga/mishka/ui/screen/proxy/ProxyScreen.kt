package top.yukonga.mishka.ui.screen.proxy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.CardSegment
import top.yukonga.mishka.ui.component.ListPopupDefaults.MenuPositionProvider
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.platform.IconLoader
import top.yukonga.mishka.ui.theme.LocalPlatformDensity
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.WideContentBox
import top.yukonga.mishka.ui.util.sheetContentSafePadding
import top.yukonga.mishka.ui.util.sheetHeightTransition
import top.yukonga.mishka.viewmodel.ProxyGroupUi
import top.yukonga.mishka.viewmodel.finalOutboundName
import top.yukonga.mishka.viewmodel.isBuiltInOutbound
import top.yukonga.mishka.viewmodel.ProxyUiState
import top.yukonga.mishka.viewmodel.ProxyViewModel
import top.yukonga.mishka.viewmodel.visibleProxyGroups
import top.yukonga.mishka.ui.component.verticalScrollbar
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.icon.extended.Unpin
import top.yukonga.miuix.kmp.interfaces.HoldDownInteraction
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowListPopup
import kotlin.math.roundToInt

private class GroupRowsCacheEntry(
    val all: ImmutableList<String>,
    val delays: ImmutableMap<String, Int>,
    val sortOption: Int,
    val hideUnavailable: Boolean,
    val sortedNodes: ImmutableList<String>,
    val rows: ImmutableList<ImmutableList<String>>,
)

private val SortResIds = listOf(
    R.string.proxy_sort_default,
    R.string.proxy_sort_name,
    R.string.proxy_sort_delay,
)

private sealed interface ProxyDisplayItem {
    data class ProviderHeader(
        val provider: String,
        val firstNodeRowIndex: Int,
    ) : ProxyDisplayItem

    data class NodeRow(
        val row: ImmutableList<String>,
        val nodeRowIndex: Int,
    ) : ProxyDisplayItem
}

/**
 * 按提供商把已排序节点分区（对齐 zashboard 的 `groupProxiesByProviderName`）：
 * 无提供商的节点排在最前且不显示分区标题，其余按首次出现顺序排列。
 */
private fun providerSections(
    nodes: List<String>,
    group: ProxyGroupUi,
): ImmutableList<Pair<String, ImmutableList<String>>> {
    if (nodes.isEmpty()) return persistentListOf()
    val byProvider = LinkedHashMap<String, MutableList<String>>()
    nodes.forEach { nodeName ->
        byProvider.getOrPut(group.nodeProviders[nodeName].orEmpty()) { mutableListOf() }.add(nodeName)
    }
    return buildList {
        if (byProvider.containsKey("")) {
            add("" to byProvider.getValue("").toPersistentList())
        }
        byProvider.forEach { (provider, providerNodes) ->
            if (provider.isNotEmpty()) add(provider to providerNodes.toPersistentList())
        }
    }.toPersistentList()
}

private fun proxyDisplayItems(
    rows: List<ImmutableList<String>>,
    group: ProxyGroupUi,
    groupByProvider: Boolean,
): ImmutableList<ProxyDisplayItem> {
    if (!groupByProvider) {
        return rows.mapIndexed { index, row -> ProxyDisplayItem.NodeRow(row, index) }.toPersistentList()
    }
    val items = mutableListOf<ProxyDisplayItem>()
    var lastProvider: String? = null
    rows.forEachIndexed { index, row ->
        val provider = group.nodeProviders[row.firstOrNull().orEmpty()].orEmpty()
        if (provider != lastProvider) {
            if (provider.isNotEmpty()) {
                items += ProxyDisplayItem.ProviderHeader(
                    provider = provider,
                    firstNodeRowIndex = index,
                )
            }
            lastProvider = provider
        }
        items += ProxyDisplayItem.NodeRow(row, index)
    }
    return items.toPersistentList()
}

private fun nowDisplayText(
    group: ProxyGroupUi,
    allGroups: List<ProxyGroupUi>,
    showFinalOutbound: Boolean,
): String {
    val now = group.now.ifEmpty { "-" }
    if (!showFinalOutbound || group.now.isEmpty()) return now
    val finalOutbound = finalOutboundName(allGroups, group)
    return if (finalOutbound.isNotEmpty() && finalOutbound != group.now) {
        "$now → $finalOutbound"
    } else {
        now
    }
}

@Composable
fun ProxyScreen(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    viewModel: ProxyViewModel? = null,
) {
    val uiState = viewModel?.uiState?.collectAsStateWithLifecycle()?.value ?: ProxyUiState()
    val sortOption = viewModel?.sortOption?.collectAsStateWithLifecycle()?.value ?: 0
    val singleColumn = viewModel?.singleColumn?.collectAsStateWithLifecycle()?.value == true
    val twoColumnGroups = viewModel?.twoColumnGroups?.collectAsStateWithLifecycle()?.value == true
    val groupByProvider = viewModel?.groupByProvider?.collectAsStateWithLifecycle()?.value == true
    val showFinalOutbound = viewModel?.showFinalOutbound?.collectAsStateWithLifecycle()?.value == true
    val showGroupIcons = viewModel?.showGroupIcons?.collectAsStateWithLifecycle()?.value ?: true
    val hideUnavailable = viewModel?.hideUnavailable?.collectAsStateWithLifecycle()?.value == true
    val closeConnectionsOnSwitch = viewModel?.closeConnectionsOnSwitch?.collectAsStateWithLifecycle()?.value == true
    // 0 = 行内两列并排，1 = 行内竖排
    val singleColumnProgress = animateFloatAsState(
        targetValue = if (singleColumn) 1f else 0f,
        animationSpec = tween(300),
        label = "singleColumnProgress",
    )
    val scrollBehavior = MiuixScrollBehavior()
    // 双列布局下被点开的代理组（以弹窗展开）；保留 last 值让弹窗关闭动画期间内容不塌陷
    var popupGroupName by remember { mutableStateOf<String?>(null) }
    var lastPopupGroup by remember { mutableStateOf<ProxyGroupUi?>(null) }
    val groups = uiState.groups
    val displayGroups = remember(groups, uiState.mode) { visibleProxyGroups(groups, uiState.mode) }
    val groupNames = remember(groups) { groups.map { it.name }.toSet() }
    val popupGroup = popupGroupName?.let { name -> groups.firstOrNull { it.name == name } }
    LaunchedEffect(popupGroup) {
        if (popupGroup != null) lastPopupGroup = popupGroup
    }
    LaunchedEffect(popupGroupName, groups) {
        if (popupGroupName != null && popupGroup == null) popupGroupName = null
    }
    LaunchedEffect(twoColumnGroups) {
        if (!twoColumnGroups) popupGroupName = null
    }
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalPlatformDensity.current ?: LocalDensity.current
    val maxAnimRows = remember(containerSize.height, density) {
        val windowHeightDp = with(density) { containerSize.height.toDp() }
        // 覆盖 1.8 倍视口高度作为滑动冗余缓冲，防展开中途手势下滑露底
        ((windowHeightDp * 1.8f / 68.dp).toInt()).coerceIn(16, 36)
    }
    val listState = rememberLazyListState()


    val modeHintRes = when (uiState.mode) {
        ProxyViewModel.MODE_DIRECT -> R.string.proxy_mode_direct_hint
        ProxyViewModel.MODE_GLOBAL -> R.string.proxy_mode_global_hint
        else -> null
    }

    val showPopup = remember { mutableStateOf(false) }
    val showSortPopup = remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // 节点行动态增删，展开态须存屏幕级可保存列表
    val expandedGroups = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf<String>() }

    // retainedGroups 在收缩动画期间保住 item 防脱落穿插；普通 Map 靠随后状态写入驱动重测
    val expandProgress = remember { mutableMapOf<String, MutableFloatState>() }
    val animatingGroups = remember { mutableStateListOf<String>() }
    val retainedGroups = remember { mutableStateListOf<String>() }
    val expandJobs = remember { mutableMapOf<String, Job>() }

    fun toggleGroup(name: String) {
        val progress = expandProgress.getOrPut(name) {
            mutableFloatStateOf(if (name in expandedGroups) 1f else 0f)
        }
        val expanding = name !in expandedGroups
        expandJobs[name]?.cancel()
        if (expanding) {
            retainedGroups.remove(name)
            expandedGroups.add(name)
        } else {
            if (name !in retainedGroups) retainedGroups.add(name)
        }
        if (name !in animatingGroups) animatingGroups.add(name)
        expandJobs[name] = coroutineScope.launch {
            animate(
                initialValue = progress.floatValue,
                targetValue = if (expanding) 1f else 0f,
                animationSpec = tween(
                    durationMillis = 280,
                    easing = FastOutSlowInEasing,
                ),
            ) { value, _ ->
                progress.floatValue = value
            }
            // 清理交给接手的新动画
            if (!expanding) {
                expandedGroups.remove(name)
                retainedGroups.remove(name)
            }
            animatingGroups.remove(name)
        }
    }

    // 手势介入滑动时立即收敛动画至终态，释放全量节点
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { isScrolling ->
                if (isScrolling && animatingGroups.isNotEmpty()) {
                    animatingGroups.toList().forEach { name ->
                        val isExpanding = name in expandedGroups
                        expandJobs[name]?.cancel()
                        expandProgress[name]?.floatValue = if (isExpanding) 1f else 0f
                        if (!isExpanding) {
                            expandedGroups.remove(name)
                            retainedGroups.remove(name)
                        }
                        animatingGroups.remove(name)
                    }
                }
            }
    }

    // 分组缓存排序分块，防单节点延迟变动触发全量重排
    val rowsCache = remember { mutableMapOf<String, GroupRowsCacheEntry>() }

    fun getGroupRows(group: ProxyGroupUi, displayNodes: List<String>, option: Int, hideUnavailable: Boolean): ImmutableList<ImmutableList<String>> {
        val cached = rowsCache[group.name]
        if (cached != null && cached.all === group.all && cached.delays === group.delays && cached.sortOption == option && cached.hideUnavailable == hideUnavailable) {
            return cached.rows
        }
        val sorted = sortNodes(displayNodes, group.delays, option).toPersistentList()
        val chunked = sorted.chunked(2).map { it.toPersistentList() }.toPersistentList()
        rowsCache[group.name] = GroupRowsCacheEntry(group.all, group.delays, option, hideUnavailable, sorted, chunked)
        return chunked
    }

    fun getSortedGroupNodes(group: ProxyGroupUi, displayNodes: List<String>, option: Int, hideUnavailable: Boolean): ImmutableList<String> {
        getGroupRows(group, displayNodes, option, hideUnavailable)
        return rowsCache[group.name]?.sortedNodes ?: displayNodes.toPersistentList()
    }


    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        modifier = modifier,
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.proxy_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (displayGroups.isNotEmpty()) {
                            Box {
                                IconButton(
                                    onClick = { showSortPopup.value = true },
                                    holdDownState = showSortPopup.value,
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Sort,
                                        contentDescription = stringResource(R.string.proxy_sort_title),
                                        tint = MiuixTheme.colorScheme.onSurface,
                                    )
                                }

                                WindowListPopup(
                                    show = showSortPopup.value,
                                    popupPositionProvider = MenuPositionProvider,
                                    alignment = PopupPositionProvider.Align.TopEnd,
                                    onDismissRequest = { showSortPopup.value = false },
                                ) {
                                    ListPopupColumn {
                                        val sortResIds = SortResIds
                                        val currentKey = sortOption / 2
                                        val isReverse = sortOption % 2 != 0
                                        val groupSize = sortResIds.size + 1

                                        sortResIds.forEachIndexed { index, resId ->
                                            DropdownImpl(
                                                text = stringResource(resId),
                                                optionSize = groupSize,
                                                isSelected = currentKey == index,
                                                index = index,
                                                onSelectedIndexChange = {
                                                    viewModel?.updateSortOption(
                                                        index * 2 + if (isReverse) 1 else 0
                                                    )
                                                    showSortPopup.value = false
                                                },
                                            )
                                        }
                                        HorizontalDivider(
                                            modifier = Modifier
                                                .padding(horizontal = 20.dp, vertical = 4.dp),
                                            thickness = 1.5.dp,
                                        )
                                        DropdownImpl(
                                            text = stringResource(R.string.proxy_sort_reverse),
                                            optionSize = groupSize,
                                            isSelected = isReverse,
                                            index = sortResIds.size,
                                            onSelectedIndexChange = {
                                                viewModel?.updateSortOption(
                                                    currentKey * 2 + if (!isReverse) 1 else 0
                                                )
                                                showSortPopup.value = false
                                            },
                                        )
                                    }
                                }
                            }

                            Box {
                                IconButton(
                                    onClick = { showPopup.value = true },
                                    holdDownState = showPopup.value,
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.MoreCircle,
                                        contentDescription = stringResource(R.string.common_more),
                                        tint = MiuixTheme.colorScheme.onSurface,
                                    )
                                }

                                WindowListPopup(
                                    show = showPopup.value,
                                    popupPositionProvider = MenuPositionProvider,
                                    alignment = PopupPositionProvider.Align.TopEnd,
                                    onDismissRequest = { showPopup.value = false },
                                ) {
                                    ListPopupColumn {
                                        DropdownImpl(
                                            text = stringResource(R.string.proxy_single_column),
                                            optionSize = 7,
                                            isSelected = singleColumn,
                                            index = 0,
                                            onSelectedIndexChange = {
                                                viewModel?.updateSingleColumn(!singleColumn)
                                                showPopup.value = false
                                            },
                                        )
                                        DropdownImpl(
                                            text = stringResource(R.string.proxy_show_group_icons),
                                            optionSize = 7,
                                            isSelected = showGroupIcons,
                                            index = 1,
                                            onSelectedIndexChange = {
                                                viewModel?.updateShowGroupIcons(!showGroupIcons)
                                                showPopup.value = false
                                            },
                                        )
                                        DropdownImpl(
                                            text = stringResource(R.string.proxy_two_column_layout),
                                            optionSize = 7,
                                            isSelected = twoColumnGroups,
                                            index = 2,
                                            onSelectedIndexChange = {
                                                viewModel?.updateTwoColumnGroups(!twoColumnGroups)
                                                showPopup.value = false
                                            },
                                        )
                                        DropdownImpl(
                                            text = stringResource(R.string.proxy_group_by_provider),
                                            optionSize = 7,
                                            isSelected = groupByProvider,
                                            index = 3,
                                            onSelectedIndexChange = {
                                                viewModel?.updateGroupByProvider(!groupByProvider)
                                                showPopup.value = false
                                            },
                                        )
                                        DropdownImpl(
                                            text = stringResource(R.string.proxy_show_final_outbound),
                                            optionSize = 7,
                                            isSelected = showFinalOutbound,
                                            index = 4,
                                            onSelectedIndexChange = {
                                                viewModel?.updateShowFinalOutbound(!showFinalOutbound)
                                                showPopup.value = false
                                            },
                                        )
                                        DropdownImpl(
                                            text = stringResource(R.string.proxy_hide_unavailable),
                                            optionSize = 7,
                                            isSelected = hideUnavailable,
                                            index = 5,
                                            onSelectedIndexChange = {
                                                viewModel?.updateHideUnavailable(!hideUnavailable)
                                                showPopup.value = false
                                            },
                                        )
                                        DropdownImpl(
                                            text = stringResource(R.string.proxy_close_connections_on_switch),
                                            optionSize = 7,
                                            isSelected = closeConnectionsOnSwitch,
                                            index = 6,
                                            onSelectedIndexChange = {
                                                viewModel?.updateCloseConnectionsOnSwitch(!closeConnectionsOnSwitch)
                                                showPopup.value = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        WideContentBox(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) { sidePadding ->
            if (displayGroups.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding(), bottom = bottomPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.proxy_no_groups),
                        fontSize = 16.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = stringResource(R.string.proxy_start_first),
                        modifier = Modifier.padding(top = 6.dp),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else {
                val onTestNodeDelay: (String) -> Unit = remember(viewModel) {
                    { nodeName -> viewModel?.testNodeDelay(nodeName) }
                }
                val onSelectProxy: (String, String) -> Unit = remember(viewModel) {
                    { groupName, proxyName -> viewModel?.selectProxy(groupName, proxyName) }
                }
                fun visibleNodesFor(group: ProxyGroupUi): List<String> = if (hideUnavailable) {
                    group.all.filter {
                        // 子代理组不是普通节点，不能按其当前出口的延迟过滤（对齐 zashboard）
                        it in groupNames ||
                            isBuiltInOutbound(it, group.nodeTypes[it].orEmpty()) ||
                            group.delays[it] != -1
                    }
                } else {
                    group.all
                }
                val sheetGroup = popupGroup ?: lastPopupGroup

                LazyColumn(
                    modifier = Modifier.verticalScrollbar(listState)
                        .fillMaxSize()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    state = listState,
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = bottomPadding,
                        start = sidePadding,
                        end = sidePadding,
                    ),
                ) {
                    if (modeHintRes != null) {
                        item(key = "mode_hint", contentType = "proxy_mode_hint") {
                            CardSegment(
                                isFirst = true,
                                isLast = true,
                                modifier = Modifier.animateItem(placementSpec = null),
                                outerTopPadding = 12.dp,
                                insidePadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    text = stringResource(modeHintRes),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }


                    if (twoColumnGroups) {
                        // 双列布局：组卡片两列排列，点击整卡以弹窗展开（对齐 zashboard 的移动端卡片）
                        val groupRows = displayGroups.chunked(2)
                        groupRows.forEachIndexed { rowIndex, rowGroups ->
                            item(
                                key = "group_grid_row:$rowIndex:${rowGroups.joinToString(",") { it.name }}",
                                contentType = "proxy_group_grid_row",
                            ) {
                                Row(
                                    modifier = Modifier
                                        .animateItem(placementSpec = null)
                                        .padding(
                                            start = 12.dp,
                                            end = 12.dp,
                                            top = if (rowIndex == 0) 12.dp else 8.dp,
                                        ),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    rowGroups.forEach { group ->
                                        ProxyGroupGridCard(
                                            group = group,
                                            showIcon = showGroupIcons,
                                            availableNodeCount = group.all.count { nodeName ->
                                                nodeName in groupNames ||
                                                        isBuiltInOutbound(nodeName, group.nodeTypes[nodeName].orEmpty()) ||
                                                        (group.delays[nodeName] ?: -1) >= 0
                                            },
                                            nowText = nowDisplayText(group, groups, showFinalOutbound),
                                            isTesting = group.name in uiState.testingGroups,
                                            onTestDelay = { viewModel?.testGroupDelay(group.name) },
                                            onClick = { popupGroupName = group.name },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (rowGroups.size == 1 && displayGroups.size > 1) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    } else {
                    // 展平为独立 item，仅组合可见行
                    displayGroups.forEach { group ->
                        val isExpanded = group.name in expandedGroups
                        val rowsPresent = isExpanded || group.name in retainedGroups
                        val isAnimating = group.name in animatingGroups
                        val displayNodes = if (hideUnavailable) {
                            group.all.filter {
                                // 子代理组不是普通节点，不能按其当前出口的延迟过滤（对齐 zashboard）
                                it in groupNames ||
                                    isBuiltInOutbound(it, group.nodeTypes[it].orEmpty()) ||
                                    group.delays[it] != -1
                            }
                        } else {
                            group.all
                        }
                        val allRows = if (rowsPresent) {
                            if (groupByProvider) {
                                val sortedNodes = getSortedGroupNodes(group, displayNodes, sortOption, hideUnavailable)
                                providerSections(sortedNodes, group)
                                    .flatMap { (_, sectionNodes) ->
                                        sectionNodes.chunked(2).map { it.toPersistentList() }
                                    }
                                    .toPersistentList()
                            } else {
                                getGroupRows(group, displayNodes, sortOption, hideUnavailable)
                            }
                        } else {
                            persistentListOf()
                        }
                        // 动画期间限制最大渲染行数防卡死，收尾后恢复全量虚拟化
                        val rows = if (isAnimating && allRows.size > maxAnimRows) {
                            allRows.take(maxAnimRows)
                        } else {
                            allRows
                        }
                        val groupProgress = expandProgress[group.name]
                        val currentFraction = groupProgress?.floatValue ?: if (isExpanded) 1f else 0f

                        item(key = "group:${group.name}", contentType = "proxy_group_header") {
                            // 有子行时平接（0dp），完全收起后封底（16dp）
                            val headerBottomCorner = if (currentFraction <= 0.01f) 16.dp else 0.dp

                            CardSegment(
                                isFirst = true,
                                isLast = currentFraction <= 0.01f,
                                modifier = Modifier.animateItem(placementSpec = null),
                                bottomCornerRadius = headerBottomCorner,
                                outerTopPadding = 12.dp,
                            ) {
                                ProxyGroupHeader(
                                    group = group,
                                    showIcon = showGroupIcons,
                                    visibleNodes = displayNodes,
                                    availableNodeCount = group.all.count { nodeName ->
                                        nodeName in groupNames ||
                                                isBuiltInOutbound(nodeName, group.nodeTypes[nodeName].orEmpty()) ||
                                                (group.delays[nodeName] ?: -1) >= 0
                                    },
                                    nowText = nowDisplayText(group, groups, showFinalOutbound),
                                    isExpanded = isExpanded,
                                    expandProgress = groupProgress,
                                    isTesting = group.name in uiState.testingGroups,
                                    onTestDelay = { viewModel?.testGroupDelay(group.name) },
                                    onUnfix = { viewModel?.unfixProxy(group.name) },
                                    onSelectNode = { proxyName -> onSelectProxy(group.name, proxyName) },
                                    onToggle = { toggleGroup(group.name) },
                                )
                            }
                        }

                        if (rows.isNotEmpty()) {
                            val lastRowIndex = rows.lastIndex
                            val rowCount = rows.size
                            val groupExpandProgress = expandProgress[group.name]
                            val onSelectThisGroup: (String) -> Unit = { proxyName ->
                                onSelectProxy(group.name, proxyName)
                            }

                            // 动态计算当前露出的最底行，赋予 16dp 底圆角
                            val activeBottomIndex = when {
                                currentFraction >= 0.99f -> lastRowIndex
                                currentFraction <= 0.01f -> -1
                                else -> (rowCount * currentFraction).toInt().coerceIn(0, lastRowIndex)
                            }

                            val displayItems = proxyDisplayItems(rows, group, groupByProvider)
                            displayItems.forEach { displayItem ->
                                when (displayItem) {
                                    is ProxyDisplayItem.ProviderHeader -> {
                                        item(
                                            key = "provider:${group.name}:${displayItem.provider}",
                                            contentType = "proxy_provider_header",
                                        ) {
                                            CardSegment(
                                                isFirst = false,
                                                isLast = false,
                                                modifier = Modifier
                                                    .animateItem(placementSpec = null)
                                                    .clipToBounds(),
                                                insidePadding = PaddingValues(
                                                    start = 16.dp,
                                                    end = 16.dp,
                                                ),
                                            ) {
                                                // 标题从展开第一帧就随进度同步长出，不等完全展开再插入，
                                                // 避免动画结束时突然多一行造成视觉卡顿
                                                val expand = groupExpandProgress?.floatValue ?: currentFraction
                                                val revealedRows = rowCount * expand
                                                val headerFraction = if (displayItem.firstNodeRowIndex == 0) {
                                                    revealedRows.coerceIn(0f, 1f)
                                                } else {
                                                    (revealedRows - displayItem.firstNodeRowIndex + 1f)
                                                        .coerceIn(0f, 1f)
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(36.dp * headerFraction)
                                                        .clipToBounds(),
                                                ) {
                                                    Text(
                                                        text = displayItem.provider,
                                                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MiuixTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    is ProxyDisplayItem.NodeRow -> {
                                        val rowIndex = displayItem.nodeRowIndex
                                        val isCurrentBottom = rowIndex == activeBottomIndex
                                        val rowBottomCorner = if (isCurrentBottom) 16.dp else 0.dp

                                        item(
                                            key = "nodes:${group.name}:$rowIndex",
                                            contentType = "proxy_node_row",
                                        ) {
                                            CardSegment(
                                                isFirst = false,
                                                isLast = isCurrentBottom,
                                                modifier = Modifier
                                                    .animateItem(placementSpec = null)
                                                    .clipToBounds(),
                                                bottomCornerRadius = rowBottomCorner,
                                                insidePadding = PaddingValues(
                                                    start = 12.dp,
                                                    end = 12.dp,
                                                ),
                                            ) {
                                                ProxyNodeRow(
                                                    row = displayItem.row,
                                                    group = group,
                                                    singleColumnProgress = singleColumnProgress,
                                                    expandProgress = groupExpandProgress,
                                                    rowIndex = rowIndex,
                                                    rowCount = rowCount,
                                                    testingNodes = uiState.testingNodes,
                                                    onTestNodeDelay = onTestNodeDelay,
                                                    onSelect = onSelectThisGroup,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }
                    item(key = "bottom_spacer") {
                        Spacer(Modifier.padding(bottom = 12.dp))
                    }
                }
                ProxyGroupNodesSheet(
                    show = popupGroupName != null,
                    group = sheetGroup,
                    displayNodes = sheetGroup?.let { visibleNodesFor(it) } ?: emptyList(),
                    sortOption = sortOption,
                    singleColumn = singleColumn,
                    groupByProvider = groupByProvider,
                    nowText = sheetGroup?.let { nowDisplayText(it, groups, showFinalOutbound) }.orEmpty(),
                    testingGroups = uiState.testingGroups,
                    testingNodes = uiState.testingNodes,
                    onDismiss = { popupGroupName = null },
                    onTestDelay = { groupName -> viewModel?.testGroupDelay(groupName) },
                    onUnfix = { groupName -> viewModel?.unfixProxy(groupName) },
                    onTestNodeDelay = onTestNodeDelay,
                    onSelect = onSelectProxy,
                )
            }
        }
    }
}

/**
 * 把 [holdDown] 映射成 [interactionSource] 上的按下态（miuix 的 `HoldDownObserver` 是 internal）。
 * `interactions` 无 replay，故两处不能漏：离开组合补 Release，indication 换实例（深浅色切换）重发。
 */
@Composable
private fun HoldDownEffect(holdDown: Boolean, interactionSource: MutableInteractionSource) {
    val current = remember { mutableStateOf<HoldDownInteraction.HoldDown?>(null) }
    val indication = LocalIndication.current
    LaunchedEffect(holdDown, interactionSource, indication) {
        current.value?.let {
            interactionSource.emit(HoldDownInteraction.Release(it))
            current.value = null
        }
        if (holdDown) {
            val interaction = HoldDownInteraction.HoldDown()
            current.value = interaction
            interactionSource.emit(interaction)
        }
    }
    DisposableEffect(interactionSource) {
        onDispose {
            current.value?.let { interactionSource.tryEmit(HoldDownInteraction.Release(it)) }
            current.value = null
        }
    }
}

@Composable
private fun ProxyGroupHeader(
    group: ProxyGroupUi,
    showIcon: Boolean,
    visibleNodes: List<String>,
    availableNodeCount: Int,
    nowText: String,
    isExpanded: Boolean,
    expandProgress: FloatState? = null,
    isTesting: Boolean,
    onTestDelay: () -> Unit,
    onUnfix: () -> Unit,
    onSelectNode: (String) -> Unit,
    onToggle: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    HoldDownEffect(isExpanded, interactionSource)
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggle()
                },
            )
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = if (isExpanded) 6.dp else 16.dp,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(
            visible = showIcon,
            enter = slideInHorizontally(tween(300)) { -it } +
                    expandHorizontally(tween(300), expandFrom = Alignment.Start),
            exit = slideOutHorizontally(tween(300)) { -it } +
                    shrinkHorizontally(tween(300), shrinkTowards = Alignment.Start),
            label = "groupIconVisibility",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupIcon(
                    icon = group.icon,
                    name = group.name,
                )
                Spacer(Modifier.width(12.dp))
            }
        }

        Text(
            text = group.name,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${group.type.uppercase()} · $availableNodeCount/${group.all.size}",
            fontSize = 11.sp,
            letterSpacing = 0.5.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(10.dp))
        val nowDelay = group.delays[group.now]
        val timeoutText = stringResource(R.string.proxy_timeout)
        val delayText = when {
            nowDelay == null -> "—"
            nowDelay < 0 -> timeoutText
            else -> "$nowDelay"
        }
        Box(
            modifier = Modifier
                .sizeIn(minWidth = 40.dp, minHeight = 20.dp)
                .squircleBackground(MiuixTheme.colorScheme.surface, 10.dp)
                .clickable(
                    enabled = !isTesting,
                    interactionSource = null,
                    indication = null,
                ) { onTestDelay() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    size = 12.dp,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = delayText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = StatusColors.delay(nowDelay),
                    maxLines = 1,
                )
            }
        }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 固定是组级属性，放当前节点行才能在收起态下解除固定
            if (group.isFixed) {
                IconButton(
                    onClick = onUnfix,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Unpin,
                        contentDescription = stringResource(R.string.proxy_unfix),
                        modifier = Modifier.size(14.dp),
                        tint = StatusColors.warning,
                    )
                }
            } else {
                Image(
                    imageVector = MiuixIcons.Basic.ArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
                )
            }
            Spacer(Modifier.width(4.dp))
            MarqueeText(
                text = nowText,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            val layoutDirection = LocalLayoutDirection.current
            Image(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier
                    .size(15.dp)
                    .graphicsLayer {
                        scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                        val p = expandProgress?.floatValue ?: if (isExpanded) 1f else 0f
                        rotationZ = lerp(0f, 90f, p)
                    },
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
            )
        }

        AnimatedVisibility(
            visible = !isExpanded && visibleNodes.isNotEmpty(),
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(220)),
            label = "groupPreviewVisibility",
        ) {
            ProxyGroupPreview(
                group = group,
                nodes = visibleNodes,
                onSelectNode = onSelectNode,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ProxyGroupPreview(
    group: ProxyGroupUi,
    nodes: List<String>,
    onSelectNode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val showDots = maxWidth > 16.dp * nodes.size
        if (showDots) {
            val gap = 4.dp
            val dotSize = 12.dp
            val columns = ((maxWidth + gap) / (dotSize + gap)).toInt().coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                nodes.chunked(columns).forEach { rowNodes ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        rowNodes.forEach { nodeName ->
                            val delay = group.delays[nodeName]
                            Box(
                                modifier = Modifier
                                    .size(dotSize)
                                    .squircleBackground(previewDelayColor(delay), 3.dp)
                                    .clickable(
                                        enabled = group.isSelectable,
                                        interactionSource = null,
                                        indication = null,
                                    ) { onSelectNode(nodeName) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (nodeName == group.now) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .squircleBackground(Color.White, 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val counts = remember(nodes, group.delays) {
                var good = 0
                var medium = 0
                var bad = 0
                var notConnected = 0
                nodes.forEach { nodeName ->
                    val delay = group.delays[nodeName]
                    when {
                        delay == null || delay < 0 -> notConnected++
                        delay < 200 -> good++
                        delay < 500 -> medium++
                        else -> bad++
                    }
                }
                listOf(good, medium, bad, notConnected)
            }
            val colors = listOf(
                StatusColors.healthy,
                StatusColors.warning,
                StatusColors.danger,
                StatusColors.neutral,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .squircleClip(4.dp),
            ) {
                counts.forEachIndexed { index, count ->
                    if (count > 0) {
                        Box(
                            modifier = Modifier
                                .weight(count.toFloat())
                                .height(8.dp)
                                .background(colors[index]),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun previewDelayColor(delay: Int?): Color = when {
    delay == null || delay < 0 -> StatusColors.neutral
    delay < 200 -> StatusColors.healthy
    delay < 500 -> StatusColors.warning
    else -> StatusColors.danger
}

/** 卡片单行文字：放得下时省略号截断，溢出后才走跑马灯，避免一屏卡片同时滚动。 */
@Composable
private fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit,
    fontWeight: FontWeight? = null,
    color: Color,
) {
    var textOverflows by remember(text) { mutableStateOf(false) }
    Text(
        text = text,
        modifier = modifier.then(
            if (textOverflows) {
                Modifier.basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = 1500,
                )
            } else {
                Modifier
            }
        ),
        fontSize = fontSize,
        fontWeight = fontWeight,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = if (textOverflows) TextOverflow.Clip else TextOverflow.Ellipsis,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && !textOverflows) {
                textOverflows = true
            }
        },
    )
}

@Composable
private fun ProxyGroupGridCard(
    group: ProxyGroupUi,
    showIcon: Boolean,
    availableNodeCount: Int,
    nowText: String,
    isTesting: Boolean,
    onTestDelay: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val nowDelay = group.delays[group.now]
    val timeoutText = stringResource(R.string.proxy_timeout)
    val delayText = when {
        nowDelay == null -> "—"
        nowDelay < 0 -> timeoutText
        else -> "$nowDelay"
    }

    val showCardIcon = showIcon && group.icon.isNotEmpty()

    Box(
        modifier = modifier
            .height(80.dp)
            .squircleSurface(color = MiuixTheme.colorScheme.surfaceContainer, cornerRadius = 16.dp)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 图标浮在右上角，不占首行高度；开关图标时三行文字位置保持不变（对齐 zashboard 移动端卡片）
            val iconReserve = if (showCardIcon) 38.dp else 0.dp
            MarqueeText(
                text = group.name,
                modifier = Modifier.padding(end = iconReserve),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = "${group.type.uppercase()} · $availableNodeCount/${group.all.size}",
                modifier = Modifier.padding(top = 3.dp, end = iconReserve),
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (group.isFixed) {
                    Icon(
                        imageVector = MiuixIcons.Pin,
                        contentDescription = stringResource(R.string.proxy_fixed),
                        modifier = Modifier.size(12.dp),
                        tint = StatusColors.warning,
                    )
                    Spacer(Modifier.width(4.dp))
                } else {
                    Image(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                MarqueeText(
                    text = nowText,
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .squircleBackground(StatusColors.delay(nowDelay).copy(alpha = 0.14f), 10.dp)
                        .clickable(
                            enabled = !isTesting,
                            interactionSource = null,
                            indication = null,
                        ) { onTestDelay() }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            size = 12.dp,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            text = delayText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = StatusColors.delay(nowDelay),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (showCardIcon) {
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                GroupIcon(
                    icon = group.icon,
                    name = group.name,
                )
            }
        }
    }
}

@Composable
private fun ProxyGroupNodesSheet(
    show: Boolean,
    group: ProxyGroupUi?,
    displayNodes: List<String>,
    sortOption: Int,
    singleColumn: Boolean,
    groupByProvider: Boolean,
    nowText: String,
    testingGroups: ImmutableSet<String>,
    testingNodes: ImmutableSet<String>,
    onDismiss: () -> Unit,
    onTestDelay: (String) -> Unit,
    onUnfix: (String) -> Unit,
    onTestNodeDelay: (String) -> Unit,
    onSelect: (String, String) -> Unit,
) {
    WindowBottomSheet(
        show = show,
        title = group?.name.orEmpty(),
        onDismissRequest = onDismiss,
        endAction = {
            val dismiss = LocalDismissState.current
            IconButton(onClick = { dismiss?.invoke() }) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.common_close),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
    ) {
        group?.let { currentGroup ->
        val sortedNodes = remember(currentGroup, displayNodes, sortOption) {
            sortNodes(displayNodes, currentGroup.delays, sortOption)
        }
        val columnCount = if (singleColumn) 1 else 2
        val rows = remember(sortedNodes, columnCount, currentGroup, groupByProvider) {
            if (groupByProvider) {
                providerSections(sortedNodes, currentGroup)
                    .flatMap { (_, sectionNodes) ->
                        sectionNodes.chunked(columnCount).map { it.toPersistentList() }
                    }
                    .toPersistentList()
            } else {
                sortedNodes.chunked(columnCount).map { it.toPersistentList() }.toPersistentList()
            }
        }
        val sheetItems = remember(rows, currentGroup, groupByProvider) {
            proxyDisplayItems(rows, currentGroup, groupByProvider)
        }
        val activeItemIndex = remember(currentGroup.name, sheetItems) {
            sheetItems.indexOfFirst { item ->
                item is ProxyDisplayItem.NodeRow && currentGroup.now in item.row
            }.takeIf { it >= 0 } ?: 0
        }
        val isTesting = currentGroup.name in testingGroups

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .sheetContentSafePadding()
                .sheetHeightTransition(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${currentGroup.type.uppercase()} · ${displayNodes.size}/${currentGroup.all.size}",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (currentGroup.now.isNotEmpty()) {
                        Text(
                            text = nowText,
                            modifier = Modifier.padding(top = 2.dp),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (currentGroup.isFixed) {
                    IconButton(
                        onClick = { onUnfix(currentGroup.name) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Unpin,
                            contentDescription = stringResource(R.string.proxy_unfix),
                            modifier = Modifier.size(17.dp),
                            tint = StatusColors.warning,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(
                    onClick = { onTestDelay(currentGroup.name) },
                    enabled = !isTesting,
                    modifier = Modifier.size(32.dp),
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            size = 16.dp,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = stringResource(R.string.proxy_test_group_delay),
                            modifier = Modifier.size(17.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            if (rows.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.proxy_no_nodes),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else {
                key(currentGroup.name) {
                    val sheetListState = rememberLazyListState(
                        initialFirstVisibleItemIndex = activeItemIndex,
                    )
                    LazyColumn(
                        state = sheetListState,
                        modifier = Modifier.verticalScrollbar(sheetListState)
                            .fillMaxWidth()
                            .heightIn(max = 520.dp),
                        contentPadding = PaddingValues(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = sheetItems,
                            key = { item ->
                                when (item) {
                                    is ProxyDisplayItem.ProviderHeader ->
                                        "${currentGroup.name}:provider:${item.provider}"

                                    is ProxyDisplayItem.NodeRow ->
                                        "${currentGroup.name}:${item.row.joinToString(",")}"
                                }
                            },
                        ) { item ->
                            when (item) {
                                is ProxyDisplayItem.ProviderHeader -> {
                                    Text(
                                        text = item.provider,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp, bottom = 2.dp),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MiuixTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                is ProxyDisplayItem.NodeRow -> {
                                    val row = item.row
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        row.forEach { proxyName ->
                                            ProxyNodeCard(
                                                modifier = Modifier.weight(1f),
                                                name = proxyName,
                                                type = currentGroup.nodeTypes[proxyName].orEmpty(),
                                                delay = currentGroup.delays[proxyName],
                                                isSelected = proxyName == currentGroup.now,
                                                isFixed = proxyName == currentGroup.fixed,
                                                isSelectable = currentGroup.isSelectable,
                                                isTesting = proxyName in testingNodes,
                                                onTestDelay = onTestNodeDelay,
                                                onClick = { selected -> onSelect(currentGroup.name, selected) },
                                            )
                                        }
                                        if (columnCount == 2 && row.size == 1) {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        } ?: Box(Modifier.fillMaxWidth())
    }
}

@Composable
private fun GroupIcon(
    icon: String,
    name: String,
) {
    if (icon.isNotEmpty()) {
        val iconVersion by IconLoader.iconVersion.collectAsStateWithLifecycle()
        var bitmap by remember(icon) { mutableStateOf<ImageBitmap?>(null) }

        LaunchedEffect(icon, iconVersion) {
            if (bitmap == null) {
                bitmap = IconLoader.loadIcon(icon)
            }
        }
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = name,
                modifier = Modifier
                    .size(30.dp)
                    .squircleClip(8.dp),
            )
        } else {
            DefaultGroupIcon(name)
        }
    } else {
        DefaultGroupIcon(name)
    }
}

@Composable
private fun DefaultGroupIcon(name: String) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .squircleBackground(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f), 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (name.isEmpty()) "" else {
                val first = name[0]
                if (first.isHighSurrogate() && name.length > 1 && name[1].isLowSurrogate()) {
                    name.substring(0, 2)
                } else {
                    first.toString()
                }
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.primary.copy(0.8f),
        )
    }
}

private val NodeRowHorizontalGap = 8.dp
private val NodeRowVerticalGap = 12.dp
private val NodeRowBottomPadding = 12.dp

// 顶底距由行内 Layout 承担，否则高度收缩后残留段底色
private val NodeRowTopPadding = 12.dp

// 恒定分行，单双列由 Layout 连续插值，保证同 item 内平滑过渡
@Composable
private fun ProxyNodeRow(
    row: ImmutableList<String>,
    group: ProxyGroupUi,
    singleColumnProgress: State<Float>,
    expandProgress: FloatState?,
    rowIndex: Int,
    rowCount: Int,
    testingNodes: ImmutableSet<String> = persistentSetOf(),
    onTestNodeDelay: (String) -> Unit = {},
    onSelect: (String) -> Unit,
) {
    Layout(
        content = {
            row.forEach { proxyName ->
                val isSelected = proxyName == group.now
                val delay = group.delays[proxyName]
                val nodeType = group.nodeTypes[proxyName] ?: ""

                ProxyNodeCard(
                    name = proxyName,
                    type = nodeType,
                    delay = delay,
                    isSelected = isSelected,
                    isFixed = proxyName == group.fixed,
                    isSelectable = group.isSelectable,
                    isTesting = proxyName in testingNodes,
                    onTestDelay = onTestNodeDelay,
                    onClick = onSelect,
                )
            }
        },
    ) { measurables, constraints ->
        // measure 阶段读进度，动画帧只重测不重组
        val fraction = singleColumnProgress.value
        val rowWidth = constraints.maxWidth
        val horizontalGap = NodeRowHorizontalGap.roundToPx()
        val verticalGap = NodeRowVerticalGap.roundToPx()
        val halfWidth = (rowWidth - horizontalGap) / 2
        val cellWidth = lerp(halfWidth, rowWidth, fraction)

        val cellConstraints = constraints.copy(minWidth = cellWidth, maxWidth = cellWidth)
        val first = if (measurables.isNotEmpty()) measurables[0].measure(cellConstraints) else null
        val second = if (measurables.size > 1) measurables[1].measure(cellConstraints) else null

        // 竖排基准取首项实际高度，两节点不等高时不留多余空隙
        val secondX = lerp(halfWidth + horizontalGap, 0, fraction)
        val secondY = lerp(0, (first?.height ?: 0) + verticalGap, fraction)
        val topPadding = if (rowIndex == 0) NodeRowTopPadding.roundToPx() else 0
        val contentHeight = topPadding + maxOf(
            first?.height ?: 0,
            if (second != null) secondY + second.height else 0,
        ) + NodeRowBottomPadding.roundToPx()

        val expand = expandProgress?.floatValue ?: 1f
        val visibleFraction = if (expand >= 1f) {
            1f
        } else if (expand <= 0f) {
            0f
        } else {
            val targetRows = rowCount * expand
            (targetRows - rowIndex.toFloat()).coerceIn(0f, 1f)
        }
        val rowHeight = (contentHeight * visibleFraction).roundToInt()

        // 满高测量顶对齐，超出部分由段裁切（卷起而非变形）
        layout(rowWidth, rowHeight) {
            if (visibleFraction > 0f) {
                first?.placeRelative(0, topPadding)
                second?.placeRelative(secondX, secondY + topPadding)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProxyNodeCard(
    modifier: Modifier = Modifier,
    name: String,
    type: String,
    delay: Int?,
    isSelected: Boolean,
    isFixed: Boolean,
    isSelectable: Boolean,
    isTesting: Boolean = false,
    onTestDelay: (String) -> Unit = {},
    onClick: (String) -> Unit,
) {
    val timeoutStr = stringResource(R.string.proxy_timeout)
    val delayText = when {
        delay == null -> null
        delay < 0 -> timeoutStr
        else -> "$delay"
    }
    val delayColor = StatusColors.delay(delay)
    val testNodeDelayLabel = stringResource(R.string.proxy_test_node_delay)

    // 固定与选中异色，提示该组不再自动切换
    val backgroundColor = when {
        isFixed -> StatusColors.fixedNodeContainer
        isSelected -> StatusColors.selectedNodeContainer
        else -> MiuixTheme.colorScheme.surface
    }

    Box(
        modifier = modifier
            .then(
                if (isSelectable) {
                    Modifier
                        .squircleSurface(color = backgroundColor, cornerRadius = 12.dp)
                        .combinedClickable(
                            onClick = { onClick(name) },
                            onLongClick = if (isTesting) null else ({ onTestDelay(name) }),
                        )
                } else {
                    Modifier
                        .squircleBackground(color = backgroundColor, cornerRadius = 12.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = if (isTesting) null else ({ onTestDelay(name) }),
                        )
                }
            )
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isFixed) {
                        Icon(
                            imageVector = MiuixIcons.Pin,
                            contentDescription = stringResource(R.string.proxy_fixed),
                            modifier = Modifier.size(11.dp),
                            tint = StatusColors.warning,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    // 节点名超出可用宽度时走跑马灯滚动（短名不挂动画，避免大量卡片同时开启无限动画拖慢滚动）
                    var textOverflows by remember(name) { mutableStateOf(false) }
                    Text(
                        text = name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        overflow = if (isSelected || textOverflows) TextOverflow.Clip else TextOverflow.Ellipsis,
                        onTextLayout = { result ->
                            if (result.hasVisualOverflow && !textOverflows) {
                                textOverflows = true
                            }
                        },
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(
                                if (isSelected || textOverflows) {
                                    Modifier.basicMarquee(
                                        iterations = Int.MAX_VALUE,
                                        initialDelayMillis = 1500,
                                    )
                                } else {
                                    Modifier
                                }
                            ),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 28.dp)
                        .clickable(
                            enabled = !isTesting,
                            interactionSource = null,
                            indication = null,
                            onClickLabel = testNodeDelayLabel,
                        ) { onTestDelay(name) },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    // 三态互斥，行高由节点名主导防抖动
                    when {
                        isTesting -> CircularProgressIndicator(
                            size = 12.dp,
                            strokeWidth = 2.dp,
                        )

                        delayText != null -> Text(
                            text = delayText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = delayColor,
                        )

                        else -> Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = testNodeDelayLabel,
                            modifier = Modifier.size(14.dp),
                            tint = StatusColors.neutral,
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            if (type.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .squircleBackground(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f), 3.dp)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = type,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

// 延迟排序超时与未测沉底，倒序仅翻转有效部分
private fun sortNodes(
    names: List<String>,
    delays: Map<String, Int>,
    sortOption: Int,
): List<String> {
    val key = sortOption / 2
    val reverse = sortOption % 2 != 0
    return when (key) {
        1 -> {
            val comparator = if (reverse) String.CASE_INSENSITIVE_ORDER.reversed() else String.CASE_INSENSITIVE_ORDER
            names.sortedWith(comparator)
        }

        2 -> {
            val valid = ArrayList<String>(names.size)
            val invalid = ArrayList<String>()
            for (name in names) {
                val d = delays[name]
                if (d != null && d > 0) valid.add(name) else invalid.add(name)
            }
            if (reverse) {
                valid.sortByDescending { delays[it] ?: Int.MAX_VALUE }
            } else {
                valid.sortBy { delays[it] ?: Int.MAX_VALUE }
            }
            valid.addAll(invalid)
            valid
        }

        else -> if (reverse) names.reversed() else names
    }
}
