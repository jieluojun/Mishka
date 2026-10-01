package top.yukonga.mishka.custom.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.sheetContentSafePadding
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.scripta.editor.CodeEditorController
import top.yukonga.scripta.editor.text.TextPosition

/**
 * 配置表单面板（自定义功能，见 mishka-custom/FORMS.md）：mihomo_box「配置页」的 Android 版。
 *
 * 与锚点面板同一套约定：
 *  1. **只改被编辑的那个键**：写回走 [YamlPatch] 的三层策略（手术式 → 块级 → 拒绝），
 *     其它顶层块逐字节不动，锚点/别名/注释/CRLF 全部保留（由 tools/forms 的性质测试 + 双引擎对拍保证）。
 *  2. **改动先落在编辑器草稿里**：经 [CodeEditorController.replaceRange] 整篇替换，形成一个撤销单元；
 *     真正写盘仍走编辑器顶栏的保存（内核校验 + 失败回滚）。
 *  3. **不做的事**：路径落在序列/标量下面的字段一律拒绝（[YamlDoc.canSet]），宁可不动也不写坏配置。
 *
 * P1 覆盖：hub 13 格 + 全局配置 / DNS / 域名嗅探 / 入站（端口 + TUN + eBPF）/ NTP / 实验性 的
 * 布尔 / 下拉 / 文本 / 数字 / 列表 / 多行文本字段；其余类型（DNS 构建器、应用多选、规则集选择器…）
 * 在 P3 落地前先只读显示，点按会明确提示。
 */
@Composable
fun MishkaConfigFormPanel(
    visible: Boolean,
    controller: CodeEditorController,
    fileName: String,
    onClose: () -> Unit,
) {
    val version = if (visible) controller.documentVersion else -1
    val text = remember(version) { if (visible) controller.getText() else "" }
    val doc = remember(version, text) { YamlDoc.parse(text) }
    var page by remember(version) { mutableStateOf<String?>(null) }

    fun apply(newText: String, toast: String, revealLine1: Int?) {
        if (newText == text) {
            showToast(toast)
            return
        }
        val lines = newText.split('\n')
        controller.replaceRange(
            TextPosition(0, 0),
            TextPosition(lines.lastIndex, lines[lines.lastIndex].length),
            newText,
        )
        if (revealLine1 != null) controller.jumpToLine((revealLine1 - 1).coerceAtLeast(0))
        showToast(toast)
    }

    fun setValue(path: String, value: Any?, label: String) {
        if (!doc.canSet(path)) {
            showToast("$label 不在可编辑位置（该路径下面是列表或纯值），请在编辑器里直接改", long = true)
            return
        }
        val out = YamlPatch.setValue(doc, path, value)
        val line = lineOfPath(out, path)
        apply(out.dump(), "已写入 $label", line)
    }

    fun clearValue(path: String, label: String) {
        val out = YamlPatch.removeKey(doc, path)
        apply(out.dump(), "已清空 $label", null)
    }

    WindowBottomSheet(
        show = visible,
        title = if (page == null) "配置表单" else pageTitle(page!!),
        onDismissRequest = onClose,
        startAction = if (page == null) null else {
            { IconButton(onClick = { page = null }) { Icon(MiuixIcons.Back, "返回", tint = MiuixTheme.colorScheme.onBackground) } }
        },
        endAction = {
            val dismiss = LocalDismissState.current
            IconButton(onClick = { dismiss?.invoke() }) {
                Icon(MiuixIcons.Close, "关闭", tint = MiuixTheme.colorScheme.onBackground)
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .sheetContentSafePadding(),
        ) {
            val current = page
            if (current == null) {
                FormHub(
                    doc = doc,
                    fileName = fileName,
                    onOpen = { page = it },
                )
            } else {
                FormPage(
                    key = current,
                    doc = doc,
                    onSet = ::setValue,
                    onClear = ::clearValue,
                    onUnsupported = { showToast("$it 的专用编辑器在 P3 落地，先用编辑器直接改这一段原文", long = true) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- hub

private class HubEntry(val title: String, val key: String?, val countPath: String?, val countUnit: String)

private val HUB: List<HubEntry> = listOf(
    HubEntry("全局配置", "general", null, ""),
    HubEntry("DNS", "dns", "dns.enable", ""),
    HubEntry("域名嗅探", "sniff", "sniffer.enable", ""),
    HubEntry("入站", "inbound", "listeners", " 个监听器"),
    HubEntry("出站代理", null, "proxies", " 个节点"),
    HubEntry("代理集合", null, "proxy-providers", " 个订阅"),
    HubEntry("代理组", null, "proxy-groups", " 个代理组"),
    HubEntry("路由规则", null, "rules", " 条规则"),
    HubEntry("规则集合", null, "rule-providers", " 个规则集"),
    HubEntry("子规则", null, "sub-rules", " 组子规则"),
    HubEntry("流量隧道", null, "tunnels", " 条隧道"),
    HubEntry("NTP", "ntp", "ntp.enable", ""),
    HubEntry("实验性配置", "experimental", "experimental", ""),
)

@Composable
private fun FormHub(doc: YamlDoc, fileName: String, onOpen: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = "$fileName · 改动先落编辑器草稿，顶栏保存才写盘",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
        items(HUB) { entry ->
            val count = entry.countPath?.let { countOf(doc, it) }
            val sub = buildString {
                append(entry.countUnit.trimStart())
                if (isNotEmpty() && count != null) append(" · ")
                if (count != null) {
                    when {
                        entry.key == "dns" -> append(if (count > 0) "已启用" else "未启用")
                        entry.key == "sniff" -> append(if (count > 0) "已启用" else "未启用")
                        entry.key == "ntp" -> append(if (count > 0) "已启用" else "未启用")
                        entry.key == "experimental" -> append("$count 项已开")
                        entry.key == "inbound" -> append("$count 个监听器")
                        else -> append("$count${entry.countUnit}")
                    }
                }
                if (entry.key == null) append("（P2）")
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CardDefaults.shape),
                colors = CardDefaults.defaultColors(),
                onClick = {
                    val key = entry.key
                    if (key == null) {
                        showToast("「${entry.title}」的界面在 P2 落地（表单引擎已就绪）", long = true)
                    } else {
                        onOpen(key)
                    }
                },
            ) {
                BasicComponent(
                    title = entry.title,
                    summary = sub,
                    endActions = {
                        Text(
                            text = if (entry.key == null) "P2" else "编辑",
                            fontSize = 13.sp,
                            color = if (entry.key == null) MiuixTheme.colorScheme.onSurfaceVariantSummary
                            else MiuixTheme.colorScheme.primary,
                        )
                    },
                )
            }
        }
    }
}

private fun pageTitle(key: String): String = when (key) {
    "general" -> "全局配置"
    "dns" -> "DNS"
    "sniff" -> "域名嗅探"
    "inbound" -> "入站"
    "ntp" -> "NTP"
    "experimental" -> "实验性配置"
    else -> key
}

private fun sectionsOf(key: String): List<FormSection> = when (key) {
    "general" -> GENERAL_SECTIONS
    "dns" -> DNS_SECTIONS
    "sniff" -> SNIFF_SECTIONS
    "inbound" -> INBOUND_PORTS + TUN_SECTIONS + EBPF_SECTIONS
    "ntp" -> NTP_SECTIONS
    "experimental" -> EXPERIMENTAL_SECTIONS
    else -> emptyList()
}

// ---------------------------------------------------------------- 分区页

@Composable
private fun FormPage(
    key: String,
    doc: YamlDoc,
    onSet: (String, Any?, String) -> Unit,
    onClear: (String, String) -> Unit,
    onUnsupported: (String) -> Unit,
) {
    val sections = remember(key) { sectionsOf(key) }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (section in sections) {
            item(key = "h-${section.title}") {
                Text(
                    text = section.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
                )
            }
            items(section.fields) { row ->
                when (row) {
                    is FormCustomRow -> CustomRow(row, onUnsupported)
                    is FormField -> FieldRow(row, doc, onSet, onClear, onUnsupported)
                }
            }
        }
    }
}

@Composable
private fun CustomRow(row: FormCustomRow, onUnsupported: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(CardDefaults.shape),
        colors = CardDefaults.defaultColors(),
    ) {
        BasicComponent(
            title = "专门控件：${row.kind}",
            summary = "P3 实现（eBPF 角色开关等由代码定制的行）",
            onClick = { onUnsupported(row.kind) },
        )
    }
}

@Composable
private fun FieldRow(
    field: FormField,
    doc: YamlDoc,
    onSet: (String, Any?, String) -> Unit,
    onClear: (String, String) -> Unit,
    onUnsupported: (String) -> Unit,
) {
    val editable = doc.canSet(field.path)
    var dialog by remember(field.path) { mutableStateOf(false) }
    val summary = FormValues.describe(doc, field)

    Card(
        modifier = Modifier.fillMaxWidth().clip(CardDefaults.shape),
        colors = CardDefaults.defaultColors(),
    ) {
        BasicComponent(
            title = field.label,
            summary = buildString {
                if (summary.isNotEmpty()) append(summary)
                field.desc?.let { if (isNotEmpty()) append(" · "); append(it) }
                if (!editable) { if (isNotEmpty()) append(" · "); append("该路径下面是列表/纯值，不能在这里改") }
                field.tag?.let { if (isNotEmpty()) append(" · "); append(it) }
            },
            endActions = {
                when (field.type) {
                    FormFieldType.BOOL -> {
                        val cur = FormValues.readBool(doc, field.path)
                        Switch(
                            checked = cur == true,
                            onCheckedChange = { onSet(field.path, it, field.label) },
                            enabled = editable,
                        )
                    }
                    FormFieldType.SELECT, FormFieldType.TEXT, FormFieldType.NUMBER,
                    FormFieldType.PASSWORD, FormFieldType.TEXTAREA,
                    FormFieldType.LIST, FormFieldType.NUMLIST, FormFieldType.USERLIST,
                    -> {
                        TextButton(
                            text = "编辑",
                            onClick = { if (editable) dialog = true else onUnsupported(field.label) },
                        )
                    }
                    else -> {
                        Text(
                            text = "P3",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            },
            onClick = {
                when (field.type) {
                    FormFieldType.BOOL -> Unit
                    FormFieldType.SELECT, FormFieldType.TEXT, FormFieldType.NUMBER,
                    FormFieldType.PASSWORD, FormFieldType.TEXTAREA,
                    FormFieldType.LIST, FormFieldType.NUMLIST, FormFieldType.USERLIST,
                    -> if (editable) dialog = true else onUnsupported(field.label)
                    else -> onUnsupported(field.label)
                }
            },
        )
    }

    if (dialog) {
        when (field.type) {
            FormFieldType.SELECT -> SelectDialog(
                field = field,
                current = FormValues.readRaw(doc, field.path),
                onDismiss = { dialog = false },
                onPick = { value ->
                    dialog = false
                    if (value == null) onClear(field.path, field.label)
                    else onSet(field.path, value, field.label)
                },
            )
            FormFieldType.LIST, FormFieldType.NUMLIST, FormFieldType.USERLIST -> ListDialog(
                field = field,
                current = FormValues.readList(doc, field.path),
                onDismiss = { dialog = false },
                onConfirm = { items ->
                    dialog = false
                    if (items.isEmpty() && field.optional) onClear(field.path, field.label)
                    else onSet(field.path, items, field.label)
                },
            )
            FormFieldType.TEXTAREA -> TextValueDialog(
                field = field,
                current = FormValues.readRaw(doc, field.path).orEmpty(),
                multiLine = true,
                onDismiss = { dialog = false },
                onConfirm = { v ->
                    dialog = false
                    if (v.isBlank() && field.optional) onClear(field.path, field.label)
                    else onSet(field.path, v, field.label)
                },
            )
            else -> TextValueDialog(
                field = field,
                current = FormValues.readRaw(doc, field.path).orEmpty(),
                multiLine = false,
                onDismiss = { dialog = false },
                onConfirm = { v ->
                    dialog = false
                    if (v.isBlank() && field.optional) onClear(field.path, field.label)
                    else onSet(field.path, if (field.type == FormFieldType.NUMBER) (v.toDoubleOrNull() ?: v) else v, field.label)
                },
            )
        }
    }
}

// ---------------------------------------------------------------- 弹层

@Composable
private fun SelectDialog(
    field: FormField,
    current: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    if (field.options.isEmpty()) {
        // 选项是运行时算的（代理名 / 策略名）→ P2 起从模型里现取，这里先给文本输入
        TextValueDialog(field, current.orEmpty(), false, onDismiss) { v ->
            onPick(v.ifBlank { null })
        }
        return
    }
    WindowDialog(
        show = true,
        title = field.label,
        summary = "`${field.path}`" + (field.desc?.let { " · $it" } ?: ""),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            for (option in field.options) {
                BasicComponent(
                    title = option.label,
                    summary = option.value,
                    endActions = {
                        if (option.value == current) {
                            Text("当前", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
                        }
                    },
                    onClick = { onPick(option.value) },
                )
            }
            if (field.allowEmpty) {
                BasicComponent(
                    title = "（未设置）",
                    summary = "删掉这个键，回到内核默认",
                    onClick = { onPick(null) },
                )
            }
        }
    }
}

@Composable
private fun TextValueDialog(
    field: FormField,
    current: String,
    multiLine: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(current) }
    WindowDialog(
        show = true,
        title = field.label,
        summary = "`${field.path}`" + (field.hint?.let { " · $it" } ?: ""),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = if (multiLine) "值（可多行）" else "值",
            )
            if (multiLine) {
                Text(
                    text = "多行内容会按 | 块写入（YAML 里保留换行）",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            field.desc?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "取消", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                TextButton(text = "确定", onClick = { onConfirm(draft.trim()) })
            }
        }
    }
}

@Composable
private fun ListDialog(
    field: FormField,
    current: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val items = remember { mutableStateListOf<String>().also { it.addAll(current) } }
    var draft by remember { mutableStateOf("") }
    WindowDialog(
        show = true,
        title = field.label,
        summary = "`${field.path}` · ${items.size} 项" + (field.hint?.let { " · $it" } ?: ""),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                items.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = (index + 1).toString().padStart(2),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.width(28.dp),
                        )
                        Text(
                            text = item,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(text = "上移", minWidth = 0.dp, minHeight = 0.dp, onClick = {
                            if (index > 0) {
                                val v = items.removeAt(index)
                                items.add(index - 1, v)
                            }
                        })
                        TextButton(text = "删", minWidth = 0.dp, minHeight = 0.dp, onClick = { items.removeAt(index) })
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    label = field.hint ?: "新增一项",
                )
                Spacer(Modifier.width(8.dp))
                TextButton(text = "添加", onClick = {
                    val v = draft.trim()
                    if (v.isNotEmpty()) {
                        items.add(v)
                        draft = ""
                    }
                })
            }
            field.desc?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "取消", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                TextButton(text = "确定", onClick = { onConfirm(items.toList()) })
            }
        }
    }
}

// ---------------------------------------------------------------- 取值 / 计数

/**
 * 从文档里读表单需要的值。全部走 [YamlDoc] 的行模型（不改文本、不建副本），
 * 与写回侧的路径语义严格一致——读得到的路径才写得进。
 */
object FormValues {

    fun readRaw(doc: YamlDoc, path: String): String? {
        val n = doc.get(path) ?: return null
        if (n.kind == YamlNode.Kind.MAP || n.kind == YamlNode.Kind.SEQ) {
            val text = doc.lines[n.start]
            return if (n.flow) text.substring(n.flowStart, n.flowEnd.coerceAtMost(text.length)) else null
        }
        val line = doc.lines[n.start]
        val raw = line.substring(
            n.valueStart.coerceAtLeast(0).coerceAtMost(line.length),
            n.valueEnd.coerceAtLeast(0).coerceAtMost(line.length),
        )
        return unquote(raw.trim())
    }

    fun readBool(doc: YamlDoc, path: String): Boolean? = when (readRaw(doc, path)?.lowercase()) {
        "true", "yes", "on" -> true
        "false", "no", "off" -> false
        else -> null
    }

    /** 块序列或 flow 序列里的标量项；不是序列则 null。 */
    fun readList(doc: YamlDoc, path: String): List<String> {
        val n = doc.get(path) ?: return emptyList()
        if (n.kind != YamlNode.Kind.SEQ) return emptyList()
        return n.items.mapNotNull { item ->
            if (item.inline) {
                val line = doc.lines[item.start]
                unquote(
                    line.subSequence(
                        item.valueStart.coerceAtLeast(0).coerceAtMost(line.length),
                        item.valueEnd.coerceAtLeast(0).coerceAtMost(line.length),
                    ).toString().trim(),
                )
            } else null
        }
    }

    fun count(doc: YamlDoc, path: String): Int {
        val n = doc.get(path) ?: return 0
        return when (n.kind) {
            YamlNode.Kind.MAP -> n.entries.size
            YamlNode.Kind.SEQ -> n.items.size
            else -> 1
        }
    }

    /** 字段当前状态的一行摘要（列表显示 N 项、布尔显示 开/关/未设置）。 */
    fun describe(doc: YamlDoc, field: FormField): String {
        val n = doc.get(field.path)
        if (n == null || doc.isNullText(n)) return "未设置"
        return when (field.type) {
            FormFieldType.BOOL -> if (readBool(doc, field.path) == true) "开" else "关"
            FormFieldType.LIST, FormFieldType.NUMLIST, FormFieldType.USERLIST ->
                "${readList(doc, field.path).size} 项"
            FormFieldType.SELECT -> readRaw(doc, field.path)?.let { v ->
                field.options.firstOrNull { it.value == v }?.label ?: v
            } ?: "未设置"
            FormFieldType.MAPTEXT, FormFieldType.MAPLIST, FormFieldType.HEADERS ->
                "${count(doc, field.path)} 项"
            else -> readRaw(doc, field.path).orEmpty().take(60)
        }
    }

    private fun unquote(s: String): String {
        if (s.length >= 2 && (s[0] == '\'' || s[0] == '"') && s.last() == s[0]) {
            return s.substring(1, s.length - 1).replace("''", "'")
        }
        return s
    }
}

private fun countOf(doc: YamlDoc, path: String): Int {
    val n = doc.get(path) ?: return 0
    return when (n.kind) {
        YamlNode.Kind.MAP -> n.entries.size
        YamlNode.Kind.SEQ -> n.items.size
        else -> if (FormValues.readBool(doc, path) == true) 1 else 0
    }
}

/** 写入后定位到该键所在行（1 起），找不到就返回 null。 */
private fun lineOfPath(doc: YamlDoc, path: String): Int? {
    val parts = YamlDoc.splitPath(path)
    var node: YamlNode? = doc.root
    var entry: YamlEntry? = null
    for (part in parts) {
        val n = node ?: break
        if (n.kind == YamlNode.Kind.MAP && n.flow) {
            return n.start + 1
        }
        entry = doc.findEntry(n, part) ?: return null
        node = entry.node
    }
    return entry?.line?.plus(1)
}
