package top.yukonga.mishka.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max

/**
 * 给可上下滑动的区域在右侧画一条常显滚动条：内容没溢出时不画，溢出时用轨道 + 滑块提示
 * 「这里还能上下滑」。只是绘制提示，不拦截手势；放在 `verticalScroll` 之前或 LazyColumn 的
 * modifier 链里即可（画在滚动内容之上、且不随内容一起滚动）。
 */
@Composable
fun Modifier.verticalScrollbar(
    state: ScrollState,
    width: Dp = 3.dp,
    inset: Dp = 2.dp,
): Modifier {
    val trackColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.14f)
    val thumbColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.55f)
    return drawWithContent {
        drawContent()
        if (state.maxValue <= 0 || state.maxValue == Int.MAX_VALUE) return@drawWithContent
        val viewportHeight = size.height
        if (viewportHeight <= 0f) return@drawWithContent
        val contentHeight = viewportHeight + state.maxValue.toFloat()
        if (contentHeight <= viewportHeight) return@drawWithContent
        val verticalInset = 4.dp.toPx()
        val trackHeight = (viewportHeight - verticalInset * 2).coerceAtLeast(0f)
        if (trackHeight <= 0f) return@drawWithContent
        val thumbHeight = max(trackHeight * viewportHeight / contentHeight, 36.dp.toPx())
            .coerceAtMost(trackHeight)
        val maxScroll = contentHeight - viewportHeight
        val progress = if (maxScroll <= 0f) 0f else (state.value.toFloat() / maxScroll).coerceIn(0f, 1f)
        val thumbTop = verticalInset + (trackHeight - thumbHeight) * progress
        val barWidth = width.toPx()
        val x = size.width - barWidth - inset.toPx()
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(x, verticalInset),
            size = Size(barWidth, trackHeight),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(x, thumbTop),
            size = Size(barWidth, thumbHeight),
            cornerRadius = radius,
        )
    }
}

/**
 * `Column` 等普通滚动容器的一行写法：内部记住 [ScrollState] 并同时挂上滚动与右侧滚动条。
 * 只适合不需要从外部驱动滚动状态的场景；拖拽排序等要拿状态的容器仍用带 state 的两个版本。
 */
@Composable
fun Modifier.verticalScrollWithScrollbar(): Modifier {
    val state = rememberScrollState()
    return this.verticalScrollbar(state).verticalScroll(state)
}

/** [LazyListState] 版：用可见项的平均高度估算总高度，列表项高度不一致时滑块位置是近似值。 */
@Composable
fun Modifier.verticalScrollbar(
    state: LazyListState,
    width: Dp = 3.dp,
    inset: Dp = 2.dp,
): Modifier {
    val trackColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.14f)
    val thumbColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.55f)
    return drawWithContent {
        drawContent()
        val layoutInfo = state.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        if (visibleItems.isEmpty() || layoutInfo.totalItemsCount <= 0) return@drawWithContent
        val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
        if (viewportHeight <= 0f) return@drawWithContent
        val visibleHeight = visibleItems.sumOf { it.size }.toFloat()
        if (visibleHeight <= 0f) return@drawWithContent
        val averageItemHeight = visibleHeight / visibleItems.size
        if (averageItemHeight <= 0f) return@drawWithContent
        val contentHeight = averageItemHeight * layoutInfo.totalItemsCount
        if (contentHeight <= viewportHeight) return@drawWithContent
        val first = visibleItems.first()
        val scrolled = (first.index * averageItemHeight - first.offset).coerceAtLeast(0f)
        val verticalInset = 4.dp.toPx()
        val trackHeight = (viewportHeight - verticalInset * 2).coerceAtLeast(0f)
        if (trackHeight <= 0f) return@drawWithContent
        val thumbHeight = max(trackHeight * viewportHeight / contentHeight, 36.dp.toPx())
            .coerceAtMost(trackHeight)
        val maxScroll = contentHeight - viewportHeight
        val progress = if (maxScroll <= 0f) 0f else (scrolled / maxScroll).coerceIn(0f, 1f)
        val thumbTop = verticalInset + (trackHeight - thumbHeight) * progress
        val barWidth = width.toPx()
        val x = size.width - barWidth - inset.toPx()
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(x, verticalInset),
            size = Size(barWidth, trackHeight),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(x, thumbTop),
            size = Size(barWidth, thumbHeight),
            cornerRadius = radius,
        )
    }
}
