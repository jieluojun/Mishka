package top.yukonga.mishka.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import top.yukonga.mishka.R

/**
 * 把运行时长（秒数）按 i18n 字符串资源格式化。负数 → 空串（视作"尚未启动"）。
 * 依赖 `stringResource`，只能在 `@Composable` 上下文调用。
 */
@Composable
fun formatUptime(seconds: Long): String {
    if (seconds < 0) return ""
    return when {
        seconds < 60 -> stringResource(R.string.home_uptime_seconds, seconds.toInt())
        seconds < 3600 -> stringResource(R.string.home_uptime_minutes, (seconds / 60).toInt())
        seconds < 86400 -> stringResource(
            R.string.home_uptime_hours_minutes,
            (seconds / 3600).toInt(),
            ((seconds % 3600) / 60).toInt(),
        )

        else -> stringResource(
            R.string.home_uptime_days_hours,
            (seconds / 86400).toInt(),
            ((seconds % 86400) / 3600).toInt(),
        )
    }
}
