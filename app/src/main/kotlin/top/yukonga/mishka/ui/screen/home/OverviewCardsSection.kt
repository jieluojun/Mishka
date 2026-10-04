package top.yukonga.mishka.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.R
import top.yukonga.mishka.domain.model.SubscriptionInfo
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.util.FormatUtils
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.mishka.viewmodel.SpeedSnapshot
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import kotlin.math.roundToInt

/**
 * 「概览」分组第一行：实时网速（带流量折线图）+ 订阅用量（可点开代理集合弹窗）。
 * 本行的标题同时统辖 [systemCardsSection] 的第二行，四张卡共处一个分组。
 */
fun LazyListScope.overviewCardsSection(
    speed: SpeedSnapshot = SpeedSnapshot(),
    state: HomeUiState = HomeUiState(),
    onSpeedClick: () -> Unit = {},
    onSubscriptionClick: () -> Unit = {},
) {
    item(key = "overview_title") {
        SmallTitle(text = stringResource(R.string.home_overview))
    }
    item(key = "overview_cards") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 6.dp)
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SpeedCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                speed = speed,
                isRunning = state.isRunning,
                onClick = onSpeedClick,
            )
            SubscriptionCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                subscription = state.subscription,
                isRunning = state.isRunning,
                onClick = onSubscriptionClick,
            )
        }
    }
}

@Composable
private fun SpeedCard(
    modifier: Modifier = Modifier,
    speed: SpeedSnapshot,
    isRunning: Boolean,
    onClick: () -> Unit,
) {
    // 折线图作为整卡背景水印：insideMargin 交给内层 Column，曲线才能铺满卡片（圆角由 Card 自身的 squircle 裁）
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(0.dp),
        // 代理未运行时无连接可差分，禁用点击避免弹出空弹窗
        onClick = if (isRunning) onClick else null,
        showIndication = isRunning,
        pressFeedbackType = if (isRunning) PressFeedbackType.Sink else PressFeedbackType.None,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            TrafficSparkline(
                // matchParentSize 不参与测量，卡片高度仍由文字内容决定，同行的订阅卡不被拉高
                modifier = Modifier.matchParentSize(),
                history = speed.history,
                uploadColor = StatusColors.trafficUpload,
                downloadColor = StatusColors.trafficDownload,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.home_speed),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    BadgeLabel("NET")
                }
                InfoRow(stringResource(R.string.home_upload), speed.uploadSpeed, Modifier.padding(top = 8.dp))
                InfoRow(stringResource(R.string.home_download), speed.downloadSpeed, Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun SubscriptionCard(
    modifier: Modifier = Modifier,
    subscription: SubscriptionInfo?,
    isRunning: Boolean,
    onClick: () -> Unit,
) {
    val total = subscription?.Total?.coerceAtLeast(0) ?: 0
    val used = subscription?.let { (it.Upload + it.Download).coerceAtLeast(0) } ?: 0
    // total<=0 的语义见 HomeViewModel.activeSubscriptionInfo：不画用量水印、badge 退回 "SUB"
    val hasQuota = total > 0
    val progress = if (hasQuota) (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

    Card(
        modifier = modifier,
        insideMargin = PaddingValues(0.dp),
        // 代理未运行时 provider 流量无数据可查，禁用点击避免弹出空弹窗
        onClick = if (isRunning) onClick else null,
        showIndication = isRunning,
        pressFeedbackType = if (isRunning) PressFeedbackType.Sink else PressFeedbackType.None,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (hasQuota) {
                SubscriptionUsageBar(
                    modifier = Modifier.matchParentSize(),
                    progress = progress,
                    color = StatusColors.usage(progress),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.home_subscription),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    BadgeLabel(if (hasQuota) "${(progress * 100).roundToInt()}%" else "SUB")
                }
                InfoRow(
                    stringResource(R.string.home_used),
                    FormatUtils.formatBytes(used),
                    Modifier.padding(top = 8.dp)
                )
                InfoRow(
                    stringResource(R.string.home_total),
                    if (hasQuota) FormatUtils.formatBytes(total) else "--",
                    Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
