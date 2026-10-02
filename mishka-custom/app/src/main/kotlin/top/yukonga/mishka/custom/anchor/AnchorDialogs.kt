package top.yukonga.mishka.custom.anchor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.popup.WindowDropdownDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 锚点面板的四个子对话框：编辑定义块 / 编辑引用行 / 新建 / 改名，外加删除确认。
 *
 * 约定：对话框**自己不落地任何文本**——只把「用户意图」算成新的文本片段交回面板，
 * 由面板走同一条 [MishkaAnchorPanel] 落地通道（整篇替换 + 跳转 + 提示）。
 * 这样面板与编辑器永远只有一个写入点，撤销也是一步一个单元。
 */

private val CompactInsideMargin = DpSize(12.dp, 10.dp)

/** 新增参数/列表项的报错文案占位名。 */
private const val NewParamLabel = "新参数"

/**
 * 可视化表单的一行草稿：[seed] 为 null 表示这一行是新加的。
 * key/value 是 Compose state——输入即触发重组，校验与预览跟着一起更新。
 */
private class RowDraft(val seed: DefEntrySeed?, key: String, value: String) {
    var key by mutableStateOf(key)
    var value by mutableStateOf(value)
}

private data class RowsResult(val rows: List<DefRow>?, val error: String)

/**
 * 把表单草稿校验成可渲染的行。规则与参考实现一致：
 * 值按「单行标量或行内 flow」规范化（[YamlValue.normalize]），键名沿用原文（已有行不改名），
 * 嵌套多行的值不在这里改、原样回写（要改请切「文本」），空表直接拒绝（删整块请用卡片上的「删除」）。
 */
private fun resolveBlock(block: DefBlock, drafts: List<RowDraft>, scalar: String): RowsResult {
    if (block.kind == DefKind.Scalar) {
        val value = YamlValue.normalize(scalar)
            ?: return RowsResult(null, "这个值不是合法 YAML：引号或括号没闭合，或者裸文本里出现了 `: `")
        return RowsResult(listOf(DefRow(null, "", value)), "")
    }
    val out = mutableListOf<DefRow>()
    val seen = mutableSetOf<String>()
    for (draft in drafts) {
        val seed = draft.seed
        if (seed != null && seed.nested) {
            out += DefRow(seed, seed.keyRaw, seed.value)
            continue
        }
        val label = if (seed != null) seed.keyRaw else draft.key.ifBlank { NewParamLabel }
        val value = YamlValue.normalize(draft.value)
            ?: return RowsResult(null, "「$label」的值不是合法 YAML")
        if (block.kind == DefKind.Seq) {
            out += DefRow(seed, "", value)
            continue
        }
        val key = if (seed != null) {
            seed.keyRaw
        } else {
            YamlValue.normalizeKey(draft.key)
                ?: return RowsResult(null, "参数名「${draft.key}」不合法：不能为空，也不能含 : # & * ! | >")
        }
        if (!seen.add(key)) return RowsResult(null, "参数「$key」出现两次，YAML 会以后一个为准")
        out += DefRow(seed, key, value)
    }
    if (out.isEmpty()) return RowsResult(null, "至少保留一个参数；要整块删掉请用卡片上的「删除」")
    return RowsResult(out, "")
}

// ==================== 编辑定义块 ====================

@Composable
internal fun AnchorDefEditDialog(
    block: DefBlock,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    val original = remember(block) { block.lines.joinToString("\n") }
    var textMode by remember(block) { mutableStateOf(!block.visual) }
    var headerValue by remember(block) { mutableStateOf(block.headerValue) }
    var textDraft by remember(block) { mutableStateOf(original) }
    val drafts = remember(block) {
        mutableStateListOf<RowDraft>().apply {
            block.entries.forEach { add(RowDraft(it, it.keyRaw, it.value)) }
        }
    }

    val result = if (textMode) RowsResult(null, "") else resolveBlock(block, drafts, headerValue)
    val rendered = result.rows?.let { AnchorBlock.render(block, it) }
    val textTrimmed = textDraft.trim('\n')
    val canApply = if (textMode) {
        textTrimmed.isNotEmpty() && textTrimmed != original
    } else {
        result.error.isEmpty() && rendered != null && rendered != original
    }
    val anchorGone = textMode && !textDraft.contains("&" + block.name)

    WindowDialog(
        show = true,
        title = "编辑 &${block.name}",
        summary = "${block.path} · L${block.startLine1} 起 ${block.lineCount} 行 · ${describeKind(block)}",
        onDismissRequest = onDismiss,
    ) {
        if (block.visual) {
            ModeSwitch(textMode = textMode, onMode = { textMode = it })
            Spacer(Modifier.height(8.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when {
                textMode -> {
                    TextField(
                        value = textDraft,
                        onValueChange = { textDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "整块原文（含 &${block.name}）",
                        insideMargin = CompactInsideMargin,
                        textStyle = MonoTextStyle,
                        singleLine = false,
                        minLines = 6,
                        maxLines = 14,
                    )
                    if (anchorGone) {
                        DialogWarn("这一段里已经没有 &${block.name}：应用后其它引用会变成悬空别名（mihomo 拒绝加载），要用卡片上的「删除」来摘锚点。")
                    } else if (block.kind == DefKind.Unknown) {
                        DialogHint("这块的形态（flow 根 / 混合层级）不适合逐项编辑，先在这里改原文最稳。")
                    }
                }

                block.kind == DefKind.Scalar -> {
                    DialogHint("这个锚点直接挂在标量值上：`${block.keyRaw}: &${block.name} <值>`。")
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = headerValue,
                        onValueChange = { headerValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "值",
                        insideMargin = CompactInsideMargin,
                        textStyle = MonoTextStyle,
                        singleLine = true,
                    )
                }

                else -> {
                    DialogHint(
                        if (block.kind == DefKind.Map) {
                            "键名沿用原文（要改键名用卡片上的「改名」）；值支持单行标量与 `[..]` / `{..}`。"
                        } else {
                            "每行一个列表项；值支持单行标量与 `[..]` / `{..}`。"
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    drafts.forEach { draft ->
                        val nested = draft.seed?.nested == true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (nested) {
                                Text(
                                    text = "嵌套块（${(draft.seed?.endIdx ?: 0) - (draft.seed?.startIdx ?: 0) + 1} 行）· 请切「文本」编辑",
                                    modifier = Modifier.weight(1f),
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            } else {
                                if (draft.seed == null) {
                                    TextField(
                                        value = draft.key,
                                        onValueChange = { draft.key = it },
                                        modifier = Modifier.weight(1f),
                                        label = "参数名",
                                        insideMargin = CompactInsideMargin,
                                        textStyle = MonoTextStyle,
                                        singleLine = true,
                                    )
                                } else {
                                    Text(
                                        text = draft.key,
                                        modifier = Modifier.weight(1f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                TextField(
                                    value = draft.value,
                                    onValueChange = { draft.value = it },
                                    modifier = Modifier.weight(1.4f),
                                    label = if (block.kind == DefKind.Seq) "值" else "YAML 值",
                                    insideMargin = CompactInsideMargin,
                                    textStyle = MonoTextStyle,
                                    singleLine = true,
                                )
                            }
                            IconButton(
                                onClick = { drafts.remove(draft) },
                                minWidth = 32.dp,
                                minHeight = 32.dp,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Delete,
                                    contentDescription = "删除这一行",
                                    tint = StatusColors.danger,
                                )
                            }
                        }
                    }
                    TextButton(
                        text = if (block.kind == DefKind.Map) "＋ 添加参数" else "＋ 添加列表项",
                        onClick = { drafts.add(RowDraft(seed = null, key = "", value = "")) },
                        minWidth = 0.dp,
                        minHeight = 36.dp,
                        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        textStyle = MiuixTheme.textStyles.button.copy(fontSize = 14.sp),
                    )
                }
            }
            if (!textMode && block.visual) {
                if (result.error.isNotEmpty()) DialogWarn(result.error)
                rendered?.let { CodePreview(caption = "应用后写回这一段：", text = it) }
            }
        }
        DialogActions(
            confirmText = "应用",
            confirmEnabled = canApply,
            onCancel = onDismiss,
            onConfirm = { onApply(if (textMode) textTrimmed else rendered.orEmpty()) },
        )
    }
}

private fun describeKind(block: DefBlock): String = when (block.kind) {
    DefKind.Map -> if (block.flowInline) "行内 flow 映射：${block.entries.size} 项（键 / 值分开编辑）" else "映射：${block.entries.size} 项"
    DefKind.Seq -> if (block.flowInline) "行内 flow 序列：${block.entries.size} 项" else "序列：${block.entries.size} 项"
    DefKind.Scalar -> "标量值"
    DefKind.Unknown -> "复杂形态"
}

// ==================== 编辑引用行 ====================

@Composable
internal fun AnchorRefEditDialog(
    anchor: String,
    ref: AnchorRefLoc,
    names: List<String>,
    candidates: List<String>,
    onDismiss: () -> Unit,
    onRebind: (String?) -> Unit,
    onApplyText: (String) -> Unit,
) {
    val shape = remember(ref) { AnchorEdit.parseRefLine(ref.text) }
    var textMode by remember(ref) { mutableStateOf(shape == null) }
    var picked by remember(ref) { mutableStateOf<String?>(anchor) }
    var picker by remember(ref) { mutableStateOf(false) }
    var textDraft by remember(ref) { mutableStateOf(ref.text) }

    val isMerge = shape?.key == null
    val known = anchor in names
    // 候选表由面板算好（定义在前 + 合并目标必须是映射）；这里只负责提示为什么列表可能是空的。
    val noCandidateReason = when {
        candidates.isNotEmpty() -> ""
        isMerge -> "没有可继承的锚点：`<<:` 只能指向**定义在这一行之前**的映射锚点——YAML 没有前向别名，也不能把 `<<:` 挂到标量/序列上。"
        else -> "这一行之前还没有任何 `&定义`：YAML 的别名只能引用前面定义过的锚点（要新建请回面板点「新建」）。"
    }
    val canApply = if (textMode) textDraft.trimEnd() != ref.text else picked != anchor

    WindowDialog(
        show = true,
        title = "编辑引用 L${ref.line}",
        summary = "${ref.path} · " + (if (isMerge) "合并继承 `<<:`" else "字段引用 `*`") +
            if (known) "" else "（当前是悬空引用，换绑到已有锚点即可修好）",
        onDismissRequest = onDismiss,
    ) {
        if (shape != null) {
            ModeSwitch(textMode = textMode, onMode = { textMode = it })
            Spacer(Modifier.height(8.dp))
        }
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            if (textMode) {
                TextField(
                    value = textDraft,
                    onValueChange = { textDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "整行原文",
                    insideMargin = CompactInsideMargin,
                    textStyle = MonoTextStyle,
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                )
                DialogHint(
                    if (shape == null) {
                        "这一行的形态不适合按钮改绑（可能一次引用多个锚点，或写法特殊），在这里直接改原文。"
                    } else {
                        "整行都会被替换掉，注意别把同一行的其它内容（比如 `- ` 前缀）丢了。"
                    },
                )
            } else {
                DialogHint(if (isMerge) "把 `<<:` 改绑到别的锚点，或改成「不继承」（等于删掉这一行）。" else "把这个字段的值引用改绑到别的锚点。")
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = (if (isMerge) "<<: " else "${shape?.key ?: ""}: ") + (picked?.let { "*$it" } ?: "（不继承）"),
                        modifier = Modifier.weight(1f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        text = "选择",
                        onClick = { picker = true },
                        minWidth = 0.dp,
                        minHeight = 34.dp,
                        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        textStyle = MiuixTheme.textStyles.button.copy(fontSize = 14.sp),
                    )
                }
                if (!known) {
                    DialogHint("提示：`*$anchor` 现在没有对应的 `&$anchor` 定义，换绑到下面列表里的锚点即可修好。")
                }
                if (noCandidateReason.isNotEmpty()) DialogHint(noCandidateReason)
                CodePreview(caption = "当前行：", text = ref.text)
            }
        }
        DialogActions(
            confirmText = "应用",
            confirmEnabled = canApply,
            onCancel = onDismiss,
            onConfirm = { if (textMode) onApplyText(textDraft.trimEnd()) else onRebind(picked) },
        )
    }

    if (picker) {
        AnchorPickerDialog(
            current = picked,
            names = candidates,
            allowInheritOff = isMerge,
            onDismiss = { picker = false },
            onPick = {
                picked = it
                picker = false
            },
        )
    }
}

/** 选锚点：已有锚点清单 + （合并行才有的）「不继承」。 */
@Composable
private fun AnchorPickerDialog(
    current: String?,
    names: List<String>,
    allowInheritOff: Boolean,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val currentOnPick by rememberUpdatedState(onPick)
    val options = remember(names, allowInheritOff) {
        buildList {
            if (allowInheritOff) add(null to "(不继承)")
            names.forEach { add(it to "*$it") }
        }
    }
    val entry = remember(options, current) {
        DropdownEntry(
            options.map { (value, label) ->
                DropdownItem(
                    text = label,
                    selected = value == current,
                    onClick = { currentOnPick(value) },
                )
            },
        )
    }
    WindowDropdownDialog(
        entry = entry,
        title = "选择锚点",
        dialogButtonString = stringResource(R.string.common_cancel),
        show = true,
        onDismiss = onDismiss,
        onDismissFinished = {},
        dropdownColors = DropdownDefaults.dialogDropdownColors(),
    )
}

// ==================== 新建锚点 ====================

@Composable
internal fun AnchorCreateDialog(
    existingNames: List<String>,
    existingTopKeys: List<String>,
    onDismiss: () -> Unit,
    onApply: (topKey: String, anchorName: String, rows: List<DefRow>) -> Unit,
) {
    var topKey by remember { mutableStateOf("") }
    var anchorName by remember { mutableStateOf("") }
    val drafts = remember { mutableStateListOf(RowDraft(seed = null, key = "", value = "")) }

    val key = YamlValue.normalizeTopKey(topKey)
    val nameOk = AnchorScan.isValidName(anchorName) && anchorName !in existingNames
    val error = when {
        anchorName.isNotEmpty() && !AnchorScan.isValidName(anchorName) ->
            "锚点名只能用字母/下划线开头，可含 . - _（YAML 锚点语法的常见写法）"

        anchorName.isNotEmpty() && anchorName in existingNames -> "&$anchorName 已存在：直接给已有锚点「改名/编辑」更合适"
        topKey.isEmpty() -> ""
        key == null -> "顶层条目名不能含空格或 : # & * 等 YAML 特殊符号"
        key in existingTopKeys -> "配置里已有顶层键「$key」，换一个名字（内核会以后一个为准）"
        else -> ""
    }
    val prepared = if (key != null && nameOk) {
        val built = resolveBlock(CreateTemplate, drafts, "")
        built.error.ifEmpty { AnchorBlock.buildTopBlock(key, anchorName, built.rows.orEmpty()) }
    } else {
        ""
    }
    val filled = drafts.any { it.key.isNotBlank() || it.value.isNotBlank() }
    val canApply = error.isEmpty() && key != null && nameOk && filled && prepared.isNotEmpty()

    WindowDialog(
        show = true,
        title = "新建锚点",
        summary = "建一个顶层定义块并插到文件头，别处就能用 `<<: *名字` 继承它",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            TextField(
                value = topKey,
                onValueChange = { topKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = "顶层条目名（如 proxies-b2）",
                insideMargin = CompactInsideMargin,
                textStyle = MonoTextStyle,
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = anchorName,
                onValueChange = { anchorName = it },
                modifier = Modifier.fillMaxWidth(),
                label = "锚点名（如 pub）",
                insideMargin = CompactInsideMargin,
                textStyle = MonoTextStyle,
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            DialogHint("内容参数：值支持单行标量与 `[..]` / `{..}`；含 `:` 的文本要加引号。")
            Spacer(Modifier.height(6.dp))
            drafts.forEach { draft ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextField(
                        value = draft.key,
                        onValueChange = { draft.key = it },
                        modifier = Modifier.weight(1f),
                        label = "参数名",
                        insideMargin = CompactInsideMargin,
                        textStyle = MonoTextStyle,
                        singleLine = true,
                    )
                    TextField(
                        value = draft.value,
                        onValueChange = { draft.value = it },
                        modifier = Modifier.weight(1.3f),
                        label = "值",
                        insideMargin = CompactInsideMargin,
                        textStyle = MonoTextStyle,
                        singleLine = true,
                    )
                    IconButton(
                        onClick = { drafts.remove(draft) },
                        minWidth = 32.dp,
                        minHeight = 32.dp,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = "删除这一行",
                            tint = StatusColors.danger,
                        )
                    }
                }
            }
            TextButton(
                text = "＋ 添加参数",
                onClick = { drafts.add(RowDraft(seed = null, key = "", value = "")) },
                minWidth = 0.dp,
                minHeight = 36.dp,
                insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                textStyle = MiuixTheme.textStyles.button.copy(fontSize = 14.sp),
            )
            if (error.isNotEmpty()) DialogWarn(error)
            if (prepared.isNotEmpty()) CodePreview(caption = "确认后插到文件头：", text = prepared)
        }
        DialogActions(
            confirmText = "新建",
            confirmEnabled = canApply,
            onCancel = onDismiss,
            onConfirm = {
                val built = resolveBlock(CreateTemplate, drafts, "")
                if (built.rows != null && key != null) onApply(key, anchorName, built.rows)
            },
        )
    }
}

/** 新建时的校验模板：只要不是 Scalar/Unknown，形状与新建块一致（顶层键 + 缩进两格的映射）。 */
private val CreateTemplate = DefBlock(
    name = "",
    keyDisplay = "",
    keyRaw = "",
    indent = 0,
    dash = false,
    kind = DefKind.Map,
    headerValue = "",
    headerComment = "",
    entries = emptyList(),
    lines = emptyList(),
    startLine1 = 1,
    path = "",
)

// ==================== 重命名 ====================

@Composable
internal fun AnchorRenameDialog(
    anchor: AnchorInfo,
    defLineRaw: String,
    knownNames: List<String>,
    existingTopKeys: List<String>,
    onDismiss: () -> Unit,
    onApply: (newName: String?, newKeyRaw: String?) -> Unit,
) {
    val shape = remember(defLineRaw) { AnchorEdit.parseDefLine(defLineRaw) }
    var newName by remember(anchor, defLineRaw) { mutableStateOf(anchor.name) }
    var newKey by remember(anchor, defLineRaw) { mutableStateOf(shape?.keyRaw.orEmpty()) }

    val nameChanged = newName != anchor.name
    val keyChanged = shape != null && newKey.isNotBlank() && newKey != shape.keyRaw
    val nameError = when {
        !nameChanged -> ""
        !AnchorScan.isValidName(newName) -> "锚点名只能用字母/下划线开头，可含 . - _"
        newName in knownNames -> "&$newName 已被其它锚点占用"
        else -> ""
    }
    val bareKey = newKey.removeSurrounding("\"").removeSurrounding("'")
    val keyError = when {
        !keyChanged -> ""
        YamlValue.normalizeKey(bareKey) == null -> "条目名不能含 : # & * 等 YAML 符号"
        bareKey in existingTopKeys -> "顶层键「$bareKey」已存在，改完会出现重复键"
        else -> ""
    }
    val canApply = (nameChanged || keyChanged) && nameError.isEmpty() && keyError.isEmpty()

    WindowDialog(
        show = true,
        title = "重命名 &${anchor.name}",
        summary = "改名会级联到 ${anchor.refs.size} 处引用（`<<:` 与 `*值` 一起改）；条目名只改定义行的键",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            TextField(
                value = newName,
                onValueChange = { newName = it },
                modifier = Modifier.fillMaxWidth(),
                label = "新锚点名（当前 ${anchor.name}）",
                insideMargin = CompactInsideMargin,
                textStyle = MonoTextStyle,
                singleLine = true,
            )
            if (nameError.isNotEmpty()) DialogWarn(nameError)
            Spacer(Modifier.height(10.dp))
            if (shape == null) {
                DialogHint("这个定义行的形态解析不出顶层键（可能是序列项或 flow 根），条目名请在编辑器里改。")
            } else {
                TextField(
                    value = newKey,
                    onValueChange = { newKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "条目名 / 顶层键（当前 ${shape.keyRaw}）",
                    insideMargin = CompactInsideMargin,
                    textStyle = MonoTextStyle,
                    singleLine = true,
                )
                if (keyError.isNotEmpty()) DialogWarn(keyError)
            }
            CodePreview(caption = "定义行：", text = defLineRaw)
        }
        DialogActions(
            confirmText = "改名",
            confirmEnabled = canApply,
            onCancel = onDismiss,
            onConfirm = {
                onApply(
                    if (nameChanged) newName else null,
                    if (keyChanged) newKey else null,
                )
            },
        )
    }
}

// ==================== 删除定义 ====================

@Composable
internal fun AnchorDropDialog(
    anchor: AnchorInfo,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val def = anchor.primaryDef
    val shape = remember(def) { def?.let { AnchorEdit.parseDefLine(it.text) } }
    val keepBlock = shape != null && shape.indent == 0 && !shape.dash && shape.key in AnchorEdit.MIHOMO_TOP_KEYS
    val blocked = anchor.refs.isNotEmpty()

    WindowDialog(
        show = true,
        title = "删除锚点 &${anchor.name}",
        summary = if (blocked) {
            "还有 ${anchor.refs.size} 处引用：先把它们改绑到别的锚点，或者删掉这些引用行（卡片上每行都有「编辑 / 删行」）。"
        } else if (keepBlock) {
            "「${shape?.key}」是 mihomo 直接读取的配置段，删锚点时只摘掉 &${anchor.name}，块体保留给内核。"
        } else {
            "这个顶层条目是为挂 &${anchor.name} 而建的容器，删除时整块一起清掉，不留 `键: 值` 残块。"
        },
        onDismissRequest = onDismiss,
    ) {
        if (def != null) {
            CodePreview(caption = "将处理的定义行（L${def.line}）：", text = def.text)
        }
        DialogActions(
            confirmText = stringResource(R.string.common_delete),
            confirmEnabled = !blocked,
            onCancel = onDismiss,
            onConfirm = onConfirm,
        )
    }
}

// ==================== 共用小组件 ====================

private val MonoTextStyle: TextStyle
    @Composable get() = MiuixTheme.textStyles.main.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )

@Composable
private fun DialogActions(
    confirmText: String,
    confirmEnabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(
            text = stringResource(R.string.common_cancel),
            modifier = Modifier.weight(1f),
            onClick = onCancel,
        )
        TextButton(
            text = confirmText,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
            enabled = confirmEnabled,
            onClick = onConfirm,
        )
    }
}

/** 可视化 / 文本 两种编辑方式切换；形态拆不动时只能走文本。 */
@Composable
private fun ModeSwitch(textMode: Boolean, onMode: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            text = "可视化",
            onClick = { onMode(false) },
            colors = if (!textMode) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
            minWidth = 0.dp,
            minHeight = 34.dp,
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            textStyle = MiuixTheme.textStyles.button.copy(fontSize = 14.sp),
        )
        TextButton(
            text = "文本",
            onClick = { onMode(true) },
            colors = if (textMode) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
            minWidth = 0.dp,
            minHeight = 34.dp,
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            textStyle = MiuixTheme.textStyles.button.copy(fontSize = 14.sp),
        )
    }
}

@Composable
private fun DialogHint(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun DialogWarn(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        fontSize = 12.sp,
        color = StatusColors.danger,
    )
}

/** 等宽预览块：应用后会写回去的那段文本，逐字展示。 */
@Composable
private fun CodePreview(caption: String, text: String) {
    Spacer(Modifier.height(10.dp))
    Text(
        text = caption,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
    Spacer(Modifier.height(4.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp, max = 140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
        )
    }
}
