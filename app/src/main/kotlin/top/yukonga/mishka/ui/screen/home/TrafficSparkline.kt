package top.yukonga.mishka.ui.screen.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import top.yukonga.mishka.viewmodel.HomeViewModel.Companion.TRAFFIC_HISTORY_CAPACITY
import top.yukonga.mishka.viewmodel.TrafficHistory

/**
 * 上传 / 下载双线流量折线图，作为速度卡片的背景水印铺满整卡（曲线只画在下部区域，不遮挡文字）。
 *
 * 两条曲线共用同一纵轴：窗口内最大速率归一化到绘图区顶部，并对该上限做插值动画，
 * 避免尖峰过后曲线整体骤然缩放。新采样点从右边界外滑入，整条曲线在一个采样周期内匀速左移一格，
 * 让 1Hz 的离散数据呈现为连续流动。
 *
 * 高频量（滑入进度 / 纵轴上限）一律在 draw 阶段读取，动画帧只触发重绘、不触发重组。
 */
@Composable
internal fun TrafficSparkline(
    modifier: Modifier = Modifier,
    history: TrafficHistory,
    uploadColor: Color,
    downloadColor: Color,
) {
    val up = history.up
    val down = history.down
    if (up.size < 2) return

    // 窗口峰值即纵轴上限；低于 MinScaleBytes 时不再放大，避免空闲期几十 B/s 的噪声铺满整个绘图区
    val targetScale = remember(history.seq) {
        var peak = 0L
        for (value in up) if (value > peak) peak = value
        for (value in down) if (value > peak) peak = value
        peak.toFloat().coerceAtLeast(MinScaleBytes)
    }

    // 追踪 seq 这个绝对量，使绘制用的 history 与动画值天然同步：新点已进入 composition 但动画尚未启动时
    // scroll 仍停在 seq-1，算出的进度恰好是「新点在右边界外」；上一轮未跑完被打断也从当前值续走，两者都不跳
    val scroll = remember { Animatable(history.seq.toFloat()) }
    val scale = remember { Animatable(targetScale) }

    LaunchedEffect(history.seq) {
        scroll.animateTo(history.seq.toFloat(), tween(durationMillis = ScrollAnimationMillis, easing = LinearEasing))
    }
    LaunchedEffect(targetScale) {
        scale.animateTo(targetScale, tween(durationMillis = ScaleAnimationMillis))
    }

    val upLine = remember { Path() }
    val upFill = remember { Path() }
    val downLine = remember { Path() }
    val downFill = remember { Path() }

    Spacer(
        modifier = modifier
            .clipToBounds()
            .drawWithCache {
                val baseline = size.height
                val chartTop = size.height * (1f - WatermarkHeightFraction)
                // 分母取 capacity-2：窗口填满后最老的点滑入起点恰在 x=0、终点滑出到 x=-step，左边缘始终有内容；
                // 取 capacity-1 则左端在 0..step 之间往复，每个采样周期闪出一道空白
                val step = size.width / (TRAFFIC_HISTORY_CAPACITY - 2)
                val strokeWidth = WatermarkStrokeWidth.toPx()
                val uploadFillBrush = fillBrush(uploadColor, chartTop, baseline)
                val downloadFillBrush = fillBrush(downloadColor, chartTop, baseline)
                val seq = history.seq
                onDrawBehind {
                    val progress = (1f - (seq - scroll.value)).coerceIn(0f, 1f)
                    val maxValue = scale.value
                    // 下载在下层：其覆盖面积通常更大，压在上传曲线之下
                    drawSeries(
                        downLine,
                        downFill,
                        down,
                        maxValue,
                        step,
                        progress,
                        chartTop,
                        baseline,
                        downloadColor,
                        downloadFillBrush,
                        strokeWidth
                    )
                    drawSeries(upLine, upFill, up, maxValue, step, progress, chartTop, baseline, uploadColor, uploadFillBrush, strokeWidth)
                }
            },
    )
}

private fun fillBrush(color: Color, chartTop: Float, baseline: Float): Brush = Brush.verticalGradient(
    colors = listOf(color.copy(alpha = WatermarkFillAlpha), Color.Transparent),
    startY = chartTop,
    endY = baseline,
)

/**
 * 以 Catmull-Rom 样条转三次贝塞尔绘制平滑曲线 + 渐变填充。
 *
 * [progress] 为本轮滑入进度（0 = 新点尚在右边界外，1 = 新点抵达右边界）；
 * 控制点纵坐标钳制在绘图区内，防止样条在陡峭段过冲穿出上下边界。
 */
private fun DrawScope.drawSeries(
    linePath: Path,
    fillPath: Path,
    values: List<Long>,
    maxValue: Float,
    step: Float,
    progress: Float,
    chartTop: Float,
    baseline: Float,
    color: Color,
    fillBrush: Brush,
    strokeWidth: Float,
) {
    val count = values.size
    if (count < 2) return
    val range = baseline - chartTop
    val offset = (1f - progress) * step
    fun x(index: Int) = size.width - (count - 1 - index) * step + offset
    fun y(index: Int) = baseline - (values[index] / maxValue).coerceIn(0f, 1f) * range

    linePath.reset()
    linePath.moveTo(x(0), y(0))
    for (i in 0 until count - 1) {
        val x0 = x(i)
        val y0 = y(i)
        val x1 = x(i + 1)
        val y1 = y(i + 1)
        val prev = (i - 1).coerceAtLeast(0)
        val next = (i + 2).coerceAtMost(count - 1)
        linePath.cubicTo(
            x0 + (x1 - x(prev)) / 6f,
            (y0 + (y1 - y(prev)) / 6f).coerceIn(chartTop, baseline),
            x1 - (x(next) - x0) / 6f,
            (y1 - (y(next) - y0) / 6f).coerceIn(chartTop, baseline),
            x1,
            y1,
        )
    }

    fillPath.reset()
    fillPath.addPath(linePath)
    fillPath.lineTo(x(count - 1), baseline)
    fillPath.lineTo(x(0), baseline)
    fillPath.close()

    drawPath(fillPath, fillBrush)
    drawPath(
        path = linePath,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** 纵轴上限下界 32 KB/s */
private const val MinScaleBytes = 32f * 1024f

/**
 * 单格滑入时长，略长于 mihomo `/traffic` 的 1Hz 推送周期。
 * 取 1100ms 让动画总是在跑完前被下一帧数据接上（稳态恒落后约 0.1 格），
 * 从而吸收推送抖动；取整 1000ms 则每周期末尾会有一小段静止等待。
 */
private const val ScrollAnimationMillis = 1100

private const val ScaleAnimationMillis = 600
