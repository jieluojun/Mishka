package top.yukonga.mishka.custom.forms

/** 文档内的一段路径：字符串段走映射键，Int 段走序列下标（与 [YamlDoc.splitPath] 的产物同型）。 */
internal typealias YPath = List<Any>

/**
 * 从文档里读表单需要的值。全部走 [YamlDoc] 的行模型（不改文本、不建副本），
 * 与写回侧的路径语义严格一致——读得到的路径才写得进。
 */
internal object FormValues {

    /** 行内标量 / 别名 / `|` 块的文本（去引号）；flow 集合给整段原文；块集合给 null。 */
    fun scalarText(doc: YamlDoc, n: YamlNode?): String? {
        if (n == null) return null
        val line = doc.lines[n.start]
        if (n.kind == YamlNode.Kind.MAP || n.kind == YamlNode.Kind.SEQ) {
            return if (n.flow) line.substring(n.flowStart, n.flowEnd.coerceAtMost(line.length)) else null
        }
        if (n.multi) {
            val body = doc.lines.subList((n.start + 1).coerceAtMost(doc.lines.size), n.end.coerceAtMost(doc.lines.size))
            val indent = body.filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length } ?: 0
            return body.joinToString("\n") { if (it.length >= indent) it.substring(indent) else it.trimStart() }.trimEnd()
        }
        if (n.alias != null) return "*" + n.alias
        val raw = line.substring(
            n.valueStart.coerceAtLeast(0).coerceAtMost(line.length),
            n.valueEnd.coerceAtLeast(0).coerceAtMost(line.length),
        )
        return unquote(raw.trim())
    }

    fun readRaw(doc: YamlDoc, path: YPath): String? = scalarText(doc, doc.get(path))

    /** 参考实现的 hasVal：键存在且不是 null / ~ / 空值。 */
    fun hasValue(doc: YamlDoc, path: YPath): Boolean {
        val n = doc.get(path) ?: return false
        return !doc.isNullText(n)
    }

    fun readBool(doc: YamlDoc, path: YPath): Boolean? = when (readRaw(doc, path)?.lowercase()) {
        "true", "yes", "on" -> true
        "false", "no", "off" -> false
        else -> null
    }

    /** 块序列或 flow 序列里的标量项；不是序列则空。 */
    fun readList(doc: YamlDoc, path: YPath): List<String> {
        val n = doc.get(path) ?: return emptyList()
        if (n.kind != YamlNode.Kind.SEQ) return emptyList()
        return n.items.mapNotNull { item ->
            if (item.kind == YamlNode.Kind.MAP || item.kind == YamlNode.Kind.SEQ) scalarText(doc, item)
            else if (item.inline || item.multi) scalarText(doc, item) else null
        }
    }

    /**
     * 列表字段的当前项：普通列表读序列；带 `join` 的字段（exclude-type 的 `A|B`）在 YAML 里是一个字符串，按分隔符拆开。
     * 字符串写法的普通列表（`network: tcp`）也容忍为单项，便于在表单里看见并改成列表。
     */
    fun readListField(doc: YamlDoc, field: FormField, path: YPath): List<String> {
        val sep = field.join
        if (sep != null) return readRaw(doc, path)?.split(sep)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val n = doc.get(path) ?: return emptyList()
        if (n.kind == YamlNode.Kind.SEQ) return readList(doc, path)
        if (n.kind == YamlNode.Kind.MAP) return emptyList()
        if (doc.isNullText(n)) return emptyList()
        return scalarText(doc, n)?.ifBlank { null }?.let { listOf(it) } ?: emptyList()
    }

    /** 映射的 键 → 值文本（列表值合成逗号分隔）。 */
    fun readMap(doc: YamlDoc, path: YPath): List<Pair<String, String>> {
        val n = doc.get(path) ?: return emptyList()
        if (n.kind != YamlNode.Kind.MAP) return emptyList()
        return n.entries.filter { !it.isMerge }.map { e ->
            val v = e.node
            val text = if (v != null && v.kind == YamlNode.Kind.SEQ) readList(doc, path + e.key).joinToString(", ")
            else scalarText(doc, v).orEmpty()
            e.key to text
        }
    }

    /** 映射列表（`- {pattern: a, target: b}`）→ 每项的 键 → 值文本。 */
    fun readMapList(doc: YamlDoc, path: YPath): List<Map<String, String>> {
        val n = doc.get(path) ?: return emptyList()
        if (n.kind != YamlNode.Kind.SEQ) return emptyList()
        return n.items.mapIndexed { i, item ->
            if (item.kind == YamlNode.Kind.MAP) readMap(doc, path + i).toMap() else emptyMap()
        }
    }

    fun count(doc: YamlDoc, path: YPath): Int {
        val n = doc.get(path) ?: return 0
        return when (n.kind) {
            YamlNode.Kind.MAP -> n.entries.size
            YamlNode.Kind.SEQ -> n.items.size
            else -> 1
        }
    }

    /** 字段当前状态的一行摘要（列表显示 N 项、布尔显示 开/关/未设置）。 */
    fun describe(doc: YamlDoc, field: FormField, path: YPath): String {
        val n = doc.get(path)
        if (n == null || doc.isNullText(n)) return "未设置"
        return when (field.type) {
            FormFieldType.BOOL -> if (readBool(doc, path) == true) "开" else "关"
            FormFieldType.LIST, FormFieldType.NUMLIST, FormFieldType.USERLIST, FormFieldType.PICKLIST -> {
                val items = readListField(doc, field, path)
                if (items.isEmpty()) "${count(doc, path)} 项" else "${items.size} 项：" + items.joinToString(", ").take(60)
            }
            FormFieldType.SELECT -> readRaw(doc, path)?.let { v ->
                field.options.firstOrNull { it.value == v }?.label ?: v
            } ?: "未设置"
            FormFieldType.MAPTEXT, FormFieldType.MAPLIST, FormFieldType.HEADERS -> "${count(doc, path)} 项"
            FormFieldType.PASSWORD -> readRaw(doc, path)?.let { if (it.isEmpty()) "" else "•".repeat(it.length.coerceAtMost(12)) }.orEmpty()
            else -> readRaw(doc, path).orEmpty().take(60)
        }
    }

    /** 文本框里的值按 YAML 直觉还原类型：true/false → 布尔，整数 / 小数 → 数字，其余字符串。 */
    fun typedScalar(v: String): Any? = when {
        v == "true" -> true
        v == "false" -> false
        INT_RE.matches(v) -> v.toLongOrNull() ?: v
        FLOAT_RE.matches(v) -> v.toDoubleOrNull() ?: v
        else -> v
    }

    private val INT_RE = Regex("^-?(0|[1-9][0-9]*)$")
    private val FLOAT_RE = Regex("^-?(0|[1-9][0-9]*)\\.[0-9]+$")

    private fun unquote(s: String): String {
        if (s.length >= 2 && (s[0] == '\'' || s[0] == '"') && s.last() == s[0]) {
            val inner = s.substring(1, s.length - 1)
            return if (s[0] == '\'') inner.replace("''", "'") else inner.replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return s
    }
}
