package top.yukonga.mishka.ui.screen.subscription

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import top.yukonga.mishka.R
import top.yukonga.mishka.data.repository.ImportProgress
import top.yukonga.mishka.data.repository.ImportStep

/**
 * 把 data 层的结构化 [ImportProgress] 映射为本地化文案。
 * 本地化归 UI 层：data 只发 [ImportStep] 语义步骤，不依赖字符串资源。
 */
@Composable
fun importStepLabel(p: ImportProgress): String = when (p.step) {
    ImportStep.Downloading -> stringResource(R.string.subscription_downloading)
    ImportStep.Prefetching ->
        if (p.providerName.isNotEmpty() && p.total > 0) {
            stringResource(R.string.subscription_updating_progress, p.providerName, p.current + 1, p.total)
        } else {
            stringResource(R.string.subscription_prefetching)
        }

    ImportStep.Validating -> stringResource(R.string.subscription_validating)
    ImportStep.Other -> p.rawLabel
}
