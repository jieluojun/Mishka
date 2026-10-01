package top.yukonga.mishka.custom.forms

// 配置表单用的 YAML 引擎（Kotlin 侧实现）。
//
// 与 tools/forms/forms_model.py 一一对应——那边是规范 + 性质测试（71 项，PyYAML 真解析校验），
// 这边是 1:1 转写。改任何一边都必须同步另一边，并重跑：
//     python3 tools/forms/forms_props.py
//
// 设计要点（详见 FORMS.md）：
//   * 只解析表单需要的东西：块映射、块序列、`- key: v` 内联续行、flow 集合、多行标量（不透明）
//   * 锚点 / 别名 / `<<:` 作标记原样保留；序列/标量下面的路径一律拒绝编辑（canSet = false）
//   * 写回三层：手术式（只换值的字符区间）→ 块级（只重排该键）→ 拒绝（宁可不动）
//   * 保真：除编辑过的键，其它顶层块逐字节不变；改回原值可逐字节还原；CRLF 保留

/** 值渲染：标量 / flow / 块式。对应 forms_model.py 的 render_scalar / render_flow / render_block。 */
object YamlValue {

    private val LOOKS_NUM = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")
    private val LOOKS_BOOL = setOf(
        "true", "false", "yes", "no", "on", "off", "null", "~",
        "True", "False", "Null", "TRUE", "FALSE", "NULL",
    )
    private const val FORBIDDEN_START = "-?:,[]{}#&*!|>'\"%@`"
    private val PLAIN_SAFE = Regex("^[A-Za-z0-9_./@+=<>~^-]+$")

    /** YAML plain 标量是否还得加引号。 */
    fun needsQuote(s: String): Boolean {
        if (s.isEmpty()) return true
        if (s != s.trim()) return true
        if (s in LOOKS_BOOL || LOOKS_NUM.matches(s)) return true
        if (s[0] in FORBIDDEN_START) return true
        if (s.contains(": ") || s.endsWith(":")) return true
        if (s.contains(" #")) return true
        if (s.any { it.code < 0x20 }) return true
        return false
    }

    fun renderKey(key: String): String =
        if (PLAIN_SAFE.matches(key) && !needsQuote(key)) key
        else "'" + key.replace("'", "''") + "'"

    fun renderScalar(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> if (v) "true" else "false"
        is String -> if (needsQuote(v)) "'" + v.replace("'", "''") + "'" else v
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    /** 简单值渲染成 flow（短、无深层嵌套时可用），否则 null。 */
    fun renderFlow(v: Any?): String? = when (v) {
        is List<*> -> {
            val parts = v.map { renderFlow(it) }
            if (parts.any { it == null }) null
            else {
                val out = "[" + parts.joinToString(", ") + "]"
                if (out.length <= 160) out else null
            }
        }
        is Map<*, *> -> {
            val parts = ArrayList<String>()
            var ok = true
            for ((k, x) in v) {
                val xs = renderFlow(x)
                if (xs == null) { ok = false; break }
                parts.add("${renderKey(k.toString())}: $xs")
            }
            if (!ok) null
            else {
                val out = "{" + parts.joinToString(", ") + "}"
                if (out.length <= 160) out else null
            }
        }
        else -> renderScalar(v)
    }

    /** 把一个值按块式风格渲染进 out（缩进 indent 空格）。 */
    fun renderBlock(v: Any?, indent: Int, out: MutableList<String>) {
        val pad = " ".repeat(indent)
        when (v) {
            is Map<*, *> -> {
                if (v.isEmpty()) { out.add(pad + "{}"); return }
                for ((k, x) in v) {
                    val leaf = leafOf(x)
                    if (leaf != null) out.add("$pad${renderKey(k.toString())}: $leaf")
                    else {
                        out.add("$pad${renderKey(k.toString())}:")
                        renderBlock(x, indent + 2, out)
                    }
                }
            }
            is List<*> -> {
                if (v.isEmpty()) { out.add(pad + "[]"); return }
                for (x in v) {
                    val leaf = leafOf(x)
                    if (leaf != null) out.add("$pad- $leaf")
                    else if (x is Map<*, *> && x.isNotEmpty()) {
                        val sub = ArrayList<String>()
                        renderBlock(x, indent + 2, sub)
                        out.add("$pad- " + sub[0].trimStart())
                        for (i in 1 until sub.size) out.add(sub[i])
                    } else {
                        out.add("$pad-")
                        renderBlock(x, indent + 2, out)
                    }
                }
            }
            else -> out.add(pad + renderScalar(v))
        }
    }

    fun leafOf(v: Any?): String? =
        if (v is Map<*, *> || v is List<*>) renderFlow(v) else renderScalar(v)
}


// ---------------------------------------------------------------- 行工具（顶层）

private fun isBlankOrComment(line: String): Boolean {
    val t = line.trim()
    return t.isEmpty() || t.startsWith("#")
}

private fun indentOf(line: String): Int {
    var i = 0
    while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
    return i
}

/** 去掉行尾注释，返回 (内容, 注释)。 */
private fun stripComment(s: String): Pair<String, String> {
    var inSingle = false
    var inDouble = false
    var cut = -1
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (inSingle) { if (c == '\'') inSingle = false }
        else if (inDouble) { if (c == '"') inDouble = false }
        else when (c) {
            '\'' -> inSingle = true
            '"' -> inDouble = true
            '#' -> if (i == 0 || s[i - 1] == ' ' || s[i - 1] == '\t') cut = i
        }
        if (cut >= 0) break
        i++
    }
    if (cut < 0) return s.trimEnd() to ""
    val content = s.substring(0, cut).trimEnd()
    return content to s.substring(content.length)
}

/** 扫描引号串，返回闭合引号后的位置。 */
private fun scanQuoted(s: String, from: Int): Int {
    val q = s[from]
    var i = from + 1
    while (i < s.length) {
        if (q == '\'' && s[i] == '\'') {
            if (i + 1 < s.length && s[i + 1] == '\'') { i += 2; continue }
            return i + 1
        }
        if (q == '"' && s[i] == '\\') { i += 2; continue }
        if (q == '"' && s[i] == '"') return i + 1
        i++
    }
    return s.length
}

// 键必须把「引号形态」排在通用形态之前：`'geosite:cn':` 里的冒号不能当成键值分隔符
private val KEY_RE = Regex(
    "^(?<indent>[ \\t]*)(?<key>(?:'[^']*')|(?:\"[^\"]*\")|(?:[^#:\\s][^:]*?))\\s*:(?<rest>.*)$"
)
private val SEQ_RE = Regex("^(?<indent>[ \\t]*)-(?<rest>\\s.*|)$")

/** 扫描一个 flow 值（可嵌套），返回结束位置（不含分隔逗号）。 */
private fun scanFlowValue(s: String, from: Int): Int {
    var depth = 0
    var i = from
    while (i < s.length) {
        val c = s[i]
        if (c == '\'' || c == '"') { i = scanQuoted(s, i); continue }
        if (c == '[' || c == '{') depth++
        else if (c == ']' || c == '}') { if (depth == 0) return i else depth-- }
        else if (c == ',' && depth == 0) return i
        i++
    }
    return i
}

/** 一个 YAML 节点。start/end 是行区间 [start, end)，列区间只在 inline 时有意义。 */
class YamlNode(
    var kind: Kind,
    var start: Int,
    var end: Int,
    var indent: Int = 0,
    var inline: Boolean = false,
    var valueStart: Int = 0,
    var valueEnd: Int = 0,
    var anchor: String? = null,
    var alias: String? = null,
    var merge: Boolean = false,
    var multi: Boolean = false,
    var flow: Boolean = false,
    var flowStart: Int = 0,
    var flowEnd: Int = 0,
) {
    enum class Kind { MAP, SEQ, SCALAR, RAW }

    val entries = ArrayList<YamlEntry>()
    val items = ArrayList<YamlNode>()
    /** flow 集合解析出来的嵌套节点（列区间指向同一行内），仅 flow = true 时有意义。 */
    val flowNodes = ArrayList<YamlNode>()
}

class YamlEntry(
    val key: String,
    val keyIndent: Int,
    val node: YamlNode?,
    val line: Int,
) {
    val isMerge: Boolean get() = key == "<<"
}


/** 行树解析器。放成类而不是局部函数，是因为几个解析函数互相递归（局部函数不能前向引用）。 */
private class YamlParser(private val lines: List<String>) {

    fun parseRoot(): YamlNode {
        val root = YamlNode(YamlNode.Kind.MAP, 0, lines.size)
        var i = 0
        while (i < lines.size) {
            if (isBlankOrComment(lines[i])) { i++; continue }
            val ind = indentOf(lines[i])
            val (node, after) = parseBlock(i, ind)
            if (node.kind == YamlNode.Kind.MAP) root.entries.addAll(node.entries)
            else root.entries.add(YamlEntry(if (node.kind == YamlNode.Kind.SEQ) "__seq__" else "__scalar__", 0, node, i))
            root.end = after
            i = if (after > i) after else i + 1
        }
        return root
    }

    fun scanQuoted(s: String, from: Int): Int {
        val q = s[from]
        var i = from + 1
        while (i < s.length) {
            if (q == '\'' && s[i] == '\'') {
                if (i + 1 < s.length && s[i + 1] == '\'') { i += 2; continue }
                return i + 1
            }
            if (q == '"' && s[i] == '\\') { i += 2; continue }
            if (q == '"' && s[i] == '"') return i + 1
            i++
        }
        return s.length
    }

    fun scanFlowValue(s: String, from: Int): Int {
        var depth = 0
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c == '\'' || c == '"') { i = scanQuoted(s, i); continue }
            if (c == '[' || c == '{') depth++
            else if (c == ']' || c == '}') { if (depth == 0) return i else depth-- }
            else if (c == ',' && depth == 0) return i
            i++
        }
        return i
    }

    /** 解析 flow 映射，返回 (条目列区间列表, 闭合位置 + 1)。 */
    fun parseFlowEntries(s: String, start: Int): Pair<List<Triple<String, Int, Int>>, Int> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var i = start + 1
        while (i < s.length) {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == ',')) i++
            if (i >= s.length || s[i] == '}') return out to (i + 1)
            val keyStart = i
            if (s[i] == '\'' || s[i] == '"') i = scanQuoted(s, i)
            else while (i < s.length && s[i] != ':' && s[i] != ',' && s[i] != '}') i++
            val keyRaw = s.substring(keyStart, i).trim()
            val key = if (keyRaw.length >= 2 && (keyRaw[0] == '\'' || keyRaw[0] == '"') &&
                keyRaw.last() == keyRaw[0]
            ) keyRaw.substring(1, keyRaw.length - 1) else keyRaw
            while (i < s.length && s[i] != ':') { if (s[i] == '}') return out to (i + 1); i++ }
            i++
            while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
            val valStart = i
            i = scanFlowValue(s, i)
            out.add(Triple(key, valStart, i))
        }
        return out to (i + 1)
    }

    fun scalarNode(lineNo: Int, col: Int, endCol: Int): YamlNode {
        val line = lines[lineNo]
        val raw = line.substring(col.coerceIn(0, line.length), endCol.coerceIn(0, line.length))
        val (content, _) = stripComment(raw)
        val bodyTrim = content.trim()
        val valueStart = col + (raw.length - raw.trimStart().length)
        val n = YamlNode(YamlNode.Kind.SCALAR, lineNo, lineNo + 1, inline = true,
            valueStart = valueStart, valueEnd = col + content.length)
        var body = bodyTrim
        val am = Regex("^&(\\S+)\\s*(.*)$").find(body)
        if (am != null) {
            n.anchor = am.groupValues[1]
            body = am.groupValues[2].trim()
            if (body.isNotEmpty()) {
                val idx = line.indexOf(body, n.valueStart)
                if (idx >= 0) { n.valueStart = idx; n.valueEnd = idx + body.length }
            }
        }
        val tm = Regex("^!!\\S+\\s*(.*)$").find(body)
        if (tm != null) {
            body = tm.groupValues[1].trim()
            if (body.isNotEmpty()) {
                val idx = line.indexOf(body, n.valueStart)
                if (idx >= 0) { n.valueStart = idx; n.valueEnd = idx + body.length }
            }
        }
        when {
            body.matches(Regex("^\\*[^\\s,{}\\[\\]]+.*")) -> { n.kind = YamlNode.Kind.RAW; n.alias = body.trimStart('*').trim() }
            body.startsWith("{") -> {
                try {
                    val (spans, close) = parseFlowEntries(line, n.valueStart)
                    n.kind = YamlNode.Kind.MAP
                    n.flow = true
                    n.flowStart = n.valueStart
                    n.flowEnd = close
                    for ((k, vs, ve) in spans) {
                        n.entries.add(YamlEntry(k, n.valueStart, scalarNode(lineNo, vs, ve), lineNo))
                    }
                } catch (_: Exception) { n.kind = YamlNode.Kind.RAW }
            }
            body.startsWith("[") -> {
                try {
                    var i = n.valueStart + 1
                    val items = ArrayList<YamlNode>()
                    while (i < line.length) {
                        while (i < line.length && (line[i] == ' ' || line[i] == ',')) i++
                        if (i >= line.length || line[i] == ']') break
                        val a = i
                        i = scanFlowValue(line, i)
                        items.add(scalarNode(lineNo, a, i))
                    }
                    n.kind = YamlNode.Kind.SEQ
                    n.flow = true
                    n.flowStart = n.valueStart
                    n.flowEnd = (i + 1).coerceAtMost(line.length)
                    n.items.addAll(items)
                } catch (_: Exception) { n.kind = YamlNode.Kind.RAW }
            }
            body.startsWith("|") || body.startsWith(">") -> {
                n.kind = YamlNode.Kind.RAW
                n.multi = true
                var j = lineNo + 1
                val base = indentOf(line)
                while (j < lines.size) {
                    if (lines[j].isBlank()) { j++; continue }
                    if (indentOf(lines[j]) > base) { j++; continue }
                    break
                }
                n.end = j
            }
        }
        return n
    }

    fun parseBlock(from: Int, indent: Int): Pair<YamlNode, Int> {
        var i = from
        var node: YamlNode? = null
        var lastEnd = from
        while (i < lines.size) {
            val line = lines[i]
            if (isBlankOrComment(line)) { i++; continue }
            val ind = indentOf(line)
            if (ind != indent) break
            val mSeq = SEQ_RE.find(line)
            val mKey = if (mSeq == null) KEY_RE.find(line) else null
            if (mSeq != null) {
                if (node == null) node = YamlNode(YamlNode.Kind.SEQ, i, i, indent = indent)
                val (item, after) = parseSeqItem(i, indent)
                node.items.add(item)
                i = after; lastEnd = after; node.end = after
            } else if (mKey != null) {
                if (node == null) node = YamlNode(YamlNode.Kind.MAP, i, i, indent = indent)
                val (entry, after) = parseMapEntry(i, indent, null)
                node.entries.add(entry)
                i = after; lastEnd = after; node.end = after
            } else {
                if (node == null) {
                    val n = YamlNode(YamlNode.Kind.SCALAR, i, i + 1, indent = indent,
                        valueStart = ind, valueEnd = line.length)
                    i++; lastEnd = i
                    return n to lastEnd
                }
                break
            }
        }
        if (node == null) return YamlNode(YamlNode.Kind.MAP, lastEnd, lastEnd, indent = indent) to lastEnd
        return node to lastEnd
    }

    fun parseSeqItem(at: Int, indent: Int): Pair<YamlNode, Int> {
        val line = lines[at]
        val m = SEQ_RE.find(line)!!
        val rest = m.groups["rest"]!!.value
        val contentCol = indent + 1 + (if (rest.isNotBlank()) rest.length - rest.trimStart().length else 0)
        if (rest.isBlank()) {
            var nxt = at + 1
            while (nxt < lines.size && isBlankOrComment(lines[nxt])) nxt++
            if (nxt < lines.size && indentOf(lines[nxt]) > indent) {
                return parseBlock(nxt, indentOf(lines[nxt]))
            }
            return YamlNode(YamlNode.Kind.SCALAR, at, at + 1, indent = indent,
                valueStart = indent, valueEnd = line.length) to (at + 1)
        }
        val body = rest.trim()
        val mKey = KEY_RE.find(body)
        if (mKey != null) {
            val subIndent = contentCol
            val node = YamlNode(YamlNode.Kind.MAP, at, at + 1, indent = subIndent)
            val (entry, after) = parseMapEntry(at, subIndent, contentCol)
            node.entries.add(entry)
            var j = after
            while (j < lines.size) {
                if (isBlankOrComment(lines[j])) { j++; continue }
                if (indentOf(lines[j]) != subIndent) break
                if (SEQ_RE.find(lines[j]) != null) break
                if (KEY_RE.find(lines[j]) == null) break
                val (e2, j2) = parseMapEntry(j, subIndent, null)
                node.entries.add(e2)
                j = j2
                node.end = j
            }
            return node to node.end
        }
        return scalarNode(at, contentCol, line.length) to (at + 1)
    }

    fun parseMapEntry(at: Int, indent: Int, sliceFrom: Int?): Pair<YamlEntry, Int> {
        val line = lines[at]
        val subject = if (sliceFrom == null) line else line.substring(sliceFrom)
        val m = KEY_RE.find(subject)!!
        val prefixLen = sliceFrom ?: 0
        val keyRaw = m.groups["key"]!!.value.trim()
        val key = if (keyRaw.length >= 2 && (keyRaw[0] == '\'' || keyRaw[0] == '"') &&
            keyRaw.last() == keyRaw[0]
        ) keyRaw.substring(1, keyRaw.length - 1) else keyRaw
        val rest = m.groups["rest"]!!.value
        val restStart = prefixLen + m.groups["rest"]!!.range.first
        val (content, _) = stripComment(rest)
        var stripped = content.trim()
        var anchor: String? = null
        while (true) {
            val am = Regex("^&(\\S+)\\s*(.*)$").find(stripped)
            val tm = Regex("^!!\\S+\\s*(.*)$").find(stripped)
            if (am != null) {
                anchor = am.groupValues[1]
                stripped = am.groupValues[2].trim()
            } else if (tm != null) {
                stripped = tm.groupValues[1].trim()
            } else break
        }
        if (stripped.isEmpty()) {
            var j = at + 1
            while (j < lines.size && isBlankOrComment(lines[j])) j++
            if (j < lines.size && indentOf(lines[j]) > indent) {
                val (inner, after) = parseBlock(j, indentOf(lines[j]))
                inner.anchor = anchor
                return YamlEntry(key, indent, inner, at) to after
            }
            val empty = YamlNode(YamlNode.Kind.SCALAR, at, at + 1, indent = indent,
                valueStart = line.length, valueEnd = line.length, anchor = anchor)
            return YamlEntry(key, indent, empty, at) to (at + 1)
        }
        val valCol = restStart + (rest.length - rest.trimStart().length)
        val n = scalarNode(at, valCol, line.length)
        return YamlEntry(key, indent, n, at) to (at + 1)
    }
}

/** 文档：行数组 + 根节点。行内容一律不含行尾符；eol 单独记。 */
class YamlDoc private constructor(
    val eol: String,
    val lines: MutableList<String>,
    val hasBom: Boolean,
    val root: YamlNode,
) {
    fun dump(): String = (if (hasBom) "\uFEFF" else "") + lines.joinToString(eol)

    // ---------------------------------------------------------------- 查询

    fun findEntry(node: YamlNode?, key: String): YamlEntry? {
        if (node == null || node.kind != YamlNode.Kind.MAP) return null
        for (e in node.entries) if (e.key == key && !e.isMerge) return e
        return null
    }

    fun get(path: String): YamlNode? {
        var cur: YamlNode? = root
        for (part in splitPath(path)) {
            cur = findEntry(cur, part)?.node ?: return null
        }
        return cur
    }

    fun resolveParent(path: List<String>, allowNull: Boolean = false): YamlNode? {
        if (path.isEmpty()) return if (root.kind == YamlNode.Kind.MAP) root else null
        var cur: YamlNode? = root
        for (part in path) {
            val n = cur ?: return null
            if (n.kind != YamlNode.Kind.MAP) return null
            val e = findEntry(n, part) ?: return null
            cur = e.node ?: return null
        }
        val n = cur ?: return null
        if (n.kind == YamlNode.Kind.MAP) return n
        return if (allowNull && isNullText(n)) n else null
    }

    /** 表单能不能编辑这个路径：父级必须能沿着映射走通（序列/标量下不编辑）。 */
    fun canSet(path: String): Boolean {
        val parts = splitPath(path)
        val parentPath = parts.dropLast(1)
        if (resolveParent(parentPath) != null) return true
        var cur: YamlNode? = root
        for (part in parentPath) {
            val n = cur ?: return false
            if (n.kind != YamlNode.Kind.MAP) return false
            val e = findEntry(n, part) ?: return true    // 后面的层级都可以新建
            cur = e.node
        }
        return cur != null && cur.kind == YamlNode.Kind.MAP
    }

    // ---------------------------------------------------------------- 内部工具

    fun isBlankOrComment(line: String): Boolean = isBlankOrComment(line)

    fun indentOf(line: String): Int = indentOf(line)

    fun isNullValue(n: YamlNode?): Boolean = when {
        n == null -> true
        n.kind == YamlNode.Kind.MAP -> n.entries.isEmpty()
        n.kind == YamlNode.Kind.SEQ -> n.items.isEmpty()
        n.kind == YamlNode.Kind.SCALAR && !n.multi -> n.valueStart >= n.valueEnd
        else -> false
    }

    fun isNullText(n: YamlNode?): Boolean {
        if (isNullValue(n)) return true
        if (n != null && n.kind == YamlNode.Kind.SCALAR) {
            val t = lines[n.start].substring(
                n.valueStart.coerceAtLeast(0).coerceAtMost(lines[n.start].length),
                n.valueEnd.coerceAtLeast(0).coerceAtMost(lines[n.start].length),
            ).trim()
            return t == "null" || t == "~" || t == "Null" || t == "NULL"
        }
        return false
    }

    /** 一个键（含其值）占的行区间 [start, end)。 */
    fun lineBounds(entry: YamlEntry): Pair<Int, Int> {
        val n = entry.node ?: return entry.line to (entry.line + 1)
        if (n.inline || entry.line == n.start) return entry.line to (entry.line + 1)
        return entry.line to n.end
    }

    /** 从 index 行往后，找到缩进 <= indent 的第一行（跳过空白/注释）。 */
    fun blockEndAfter(index: Int, indent: Int): Int {
        var j = index
        while (j < lines.size) {
            val line = lines[j]
            if (isBlankOrComment(line)) { j++; continue }
            if (indentOf(line) <= indent) break
            j++
        }
        return j
    }

    fun childEnd(parent: YamlNode): Int {
        if (parent.entries.isEmpty()) return if (parent.start < lines.size) parent.start + 1 else lines.size
        val last = parent.entries.last()
        return lineBounds(last).second
    }

    companion object {

        fun splitPath(path: String): List<String> {
            val out = ArrayList<String>()
            var buf = StringBuilder()
            var quote: Char? = null
            for (ch in path) {
                if (quote != null) {
                    if (ch == quote) quote = null else buf.append(ch)
                } else if (ch == '\'' || ch == '"') {
                    quote = ch
                } else if (ch == '.') {
                    out.add(buf.toString()); buf = StringBuilder()
                } else buf.append(ch)
            }
            if (buf.isNotEmpty()) out.add(buf.toString())
            return out
        }

        fun parse(text: String): YamlDoc {
            val eol = if (text.contains("\r\n")) "\r\n" else "\n"
            val hasBom = text.startsWith("\uFEFF")
            val body = if (hasBom) text.substring(1) else text
            val lines = ArrayList<String>(body.split(if (eol == "\r\n") "\r\n" else "\n"))
            val root = YamlParser(lines).parseRoot()
            return YamlDoc(eol, lines, hasBom, root)
        }
    }
}

/** 写回：手术式优先，块级次之，路径不可编辑则原样返回。对应 forms_model.py 的 set_value / remove_key。 */
object YamlPatch {

    fun setValue(doc: YamlDoc, path: String, value: Any?): YamlDoc {
        val parts = YamlDoc.splitPath(path)
        val parentPath = parts.dropLast(1)
        val key = parts.last()
        var parent = doc.resolveParent(parentPath)

        if (parent != null && parent.flow) return flowSet(doc, parent, key, value)
        if (parent != null) {
            val entry = doc.findEntry(parent, key)
            if (entry?.node != null && entry.node!!.flow) {
                val rendered = YamlValue.renderFlow(value) ?: return doc
                val lines = ArrayList(doc.lines)
                val n = entry.node!!
                val line = lines[n.start]
                lines[n.start] = line.substring(0, n.flowStart) + rendered +
                    line.substring(n.flowEnd.coerceAtMost(line.length))
                return reparse(doc, lines)
            }
            if (entry?.node != null && entry.node!!.inline &&
                (entry.node!!.kind == YamlNode.Kind.SCALAR || entry.node!!.kind == YamlNode.Kind.RAW)
            ) {
                val n = entry.node!!
                val leaf = if (value is Map<*, *> || value is List<*>) null else YamlValue.renderScalar(value)
                if (leaf != null) {
                    val lines = ArrayList(doc.lines)
                    val line = lines[n.start]
                    val before = line.substring(0, n.valueStart)
                    val after = line.substring(n.valueEnd.coerceAtMost(line.length))
                    val prefix = if (n.anchor != null) "&${n.anchor} " else ""
                    lines[n.start] = before + prefix + leaf + after
                    return reparse(doc, lines)
                }
                return replaceEntryBlock(doc, entry, value)
            }
            if (entry != null) return replaceEntryBlock(doc, entry, value)
        }
        val inserted = insertKey(doc, parentPath, key, value, asEmpty = false)
        return inserted ?: doc
    }

    fun removeKey(doc: YamlDoc, path: String): YamlDoc {
        val parts = YamlDoc.splitPath(path)
        val parentPath = parts.dropLast(1)
        val key = parts.last()
        val parent = doc.resolveParent(parentPath) ?: return doc
        if (parent.flow) {
            val lines = ArrayList(doc.lines)
            val line = lines[parent.start]
            val span = line.substring(parent.flowStart, parent.flowEnd.coerceAtMost(line.length))
            lines[parent.start] = line.substring(0, parent.flowStart) + flowRemove(span, key) +
                line.substring(parent.flowEnd.coerceAtMost(line.length))
            return reparse(doc, lines)
        }
        val entry = doc.findEntry(parent, key) ?: return doc
        val (start, end) = doc.lineBounds(entry)
        val lines = ArrayList(doc.lines)
        repeat((end - start).coerceAtLeast(0)) { if (start < lines.size) lines.removeAt(start) }
        return reparse(doc, lines)
    }

    // ---------------------------------------------------------------- 内部

    fun flowSet(doc: YamlDoc, parent: YamlNode, key: String, value: Any?): YamlDoc {
        val rendered = if (value is Map<*, *> || value is List<*>) YamlValue.renderFlow(value)
        else YamlValue.renderScalar(value)
        val lines = ArrayList(doc.lines)
        val line = lines[parent.start]
        val span = line.substring(parent.flowStart, parent.flowEnd.coerceAtMost(line.length))
        val newFlow = flowSetText(span, key, rendered ?: YamlValue.renderScalar(value.toString()))
        lines[parent.start] = line.substring(0, parent.flowStart) + newFlow +
            line.substring(parent.flowEnd.coerceAtMost(line.length))
        return reparse(doc, lines)
    }

    /** 在 flow 映射文本里设置 key：存在则替换值，不存在则追加。对应 flow_set。 */
    fun flowSetText(text: String, key: String, rendered: String): String {
        val spans = parseFlowSpans(text)
        for (s in spans) {
            if (s.key == key) {
                return text.substring(0, s.valStart) + rendered + text.substring(s.valEnd)
            }
        }
        val close = flowClose(text)
        val inner = text.substring(1, (close - 1).coerceAtLeast(1)).trimEnd()
        if (inner.isBlank()) return "{" + YamlValue.renderKey(key) + ": " + rendered + "}"
        val sep = if (inner.endsWith(",")) "" else ","
        return text.substring(0, close - 1) + sep + " " + YamlValue.renderKey(key) + ": " + rendered +
            text.substring(close - 1)
    }

    /** 删掉 flow 映射里的 key（连同一个分隔逗号）。对应 flow_remove。 */
    fun flowRemove(text: String, key: String): String {
        val spans = parseFlowSpans(text)
        val idx = spans.indexOfFirst { it.key == key }
        if (idx < 0) return text
        val s = spans[idx]
        var start = s.keyStart
        var end = s.valEnd
        var j = end
        while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
        if (j < text.length && text[j] == ',') {
            end = j + 1
            while (end < text.length && text[end] == ' ') end++
        } else {
            var k = start - 1
            while (k >= 0 && (text[k] == ' ' || text[k] == '\t')) k--
            if (k >= 0 && text[k] == ',') start = k
        }
        return text.substring(0, start) + text.substring(end)
    }

    /** flow 映射里的一段的列区间：键、键起点、值起点、值终点。 */
    private class FlowSpan(val key: String, val keyStart: Int, val valStart: Int, val valEnd: Int)

    /** 解析 flow 映射，返回每段的列区间（改值用 valStart/valEnd，删键用 keyStart/valEnd）。 */
    private fun parseFlowSpans(text: String): List<FlowSpan> {
        val out = ArrayList<FlowSpan>()
        var i = 1
        while (i < text.length) {
            while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\n' || text[i] == ',')) i++
            if (i >= text.length || text[i] == '}') break
            val keyStart = i
            if (text[i] == '\'' || text[i] == '"') i = scanQuoted(text, i)
            else while (i < text.length && text[i] != ':' && text[i] != ',' && text[i] != '}') i++
            val keyRaw = text.substring(keyStart, i).trim()
            val key = if (keyRaw.length >= 2 && (keyRaw[0] == '\'' || keyRaw[0] == '"') &&
                keyRaw.last() == keyRaw[0]
            ) keyRaw.substring(1, keyRaw.length - 1) else keyRaw
            while (i < text.length && text[i] != ':') { if (text[i] == '}') return out; i++ }
            i++
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
            val vs = i
            i = scanFlowValue(text, i)
            out.add(FlowSpan(key, keyStart, vs, i))
        }
        return out
    }

    private fun flowClose(text: String): Int {
        var depth = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\'' || c == '"') { i = scanQuoted(text, i); continue }
            if (c == '{') depth++
            else if (c == '}') { depth--; if (depth == 0) return i + 1 }
            i++
        }
        return text.length
    }

    private fun scanQuoted(s: String, from: Int): Int {
        val q = s[from]
        var i = from + 1
        while (i < s.length) {
            if (q == '\'' && s[i] == '\'') {
                if (i + 1 < s.length && s[i + 1] == '\'') { i += 2; continue }
                return i + 1
            }
            if (q == '"' && s[i] == '\\') { i += 2; continue }
            if (q == '"' && s[i] == '"') return i + 1
            i++
        }
        return s.length
    }

    private fun scanFlowValue(s: String, from: Int): Int {
        var depth = 0
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c == '\'' || c == '"') { i = scanQuoted(s, i); continue }
            if (c == '[' || c == '{') depth++
            else if (c == ']' || c == '}') { if (depth == 0) return i else depth-- }
            else if (c == ',' && depth == 0) return i
            i++
        }
        return i
    }

    private fun replaceEntryBlock(doc: YamlDoc, entry: YamlEntry, value: Any?): YamlDoc {
        val (start, end) = doc.lineBounds(entry)
        val pad = " ".repeat(entry.keyIndent)
        val render = ArrayList<String>()
        render.add("$pad${YamlValue.renderKey(entry.key)}:")
        YamlValue.renderBlock(value, entry.keyIndent + 2, render)
        val lines = ArrayList(doc.lines)
        for (i in (end - 1) downTo start) if (i < lines.size) lines.removeAt(i)
        lines.addAll(start, render)
        return reparse(doc, lines)
    }

    /** 在 parentPath 下插入 key；父级缺失就按需新建（只建映射）。返回 null 表示路径不可编辑。 */
    private fun insertKey(doc: YamlDoc, parentPath: List<String>, key: String, value: Any?,
                          asEmpty: Boolean): YamlDoc? {
        var cur = doc
        for (i in 0..parentPath.size) {
            val prefix = parentPath.subList(0, i)
            if (prefix.isNotEmpty() && cur.resolveParent(prefix, allowNull = true) == null) {
                val built = insertKey(cur, prefix.subList(0, prefix.size - 1), prefix.last(), null, asEmpty = true)
                    ?: return null
                cur = built
            }
        }
        val where = keyLinePos(cur, parentPath) ?: return null
        val (at, indent) = where
        val lines = ArrayList(cur.lines)
        if (asEmpty) {
            lines.add(at, " ".repeat(indent) + YamlValue.renderKey(key) + ":")
            return reparse(cur, lines)
        }
        val leaf = if (value is Map<*, *> || value is List<*>) null else YamlValue.renderScalar(value)
        val render = ArrayList<String>()
        if (leaf != null) {
            render.add(" ".repeat(indent) + YamlValue.renderKey(key) + ": " + leaf)
        } else {
            render.add(" ".repeat(indent) + YamlValue.renderKey(key) + ":")
            if (value != null) YamlValue.renderBlock(value, indent + 2, render)
        }
        lines.addAll(at, render)
        return reparse(cur, lines)
    }

    /** 插入点：(行号, 缩进)。父级是空值键时插在它后面并多缩进两格。 */
    private fun keyLinePos(doc: YamlDoc, parentPath: List<String>): Pair<Int, Int>? {
        if (parentPath.isEmpty()) return doc.root.end to 0
        val parent = doc.resolveParent(parentPath, allowNull = true) ?: return null
        if (parent.kind == YamlNode.Kind.MAP && parent.entries.isNotEmpty()) {
            return doc.childEnd(parent) to parent.indent
        }
        if (parent.kind == YamlNode.Kind.MAP) return parent.start to parent.indent
        val grand = doc.resolveParent(parentPath.dropLast(1), allowNull = true) ?: return null
        val entry = doc.findEntry(grand, parentPath.last()) ?: return null
        return (entry.line + 1) to (entry.keyIndent + 2)
    }

    private fun reparse(doc: YamlDoc, lines: List<String>): YamlDoc {
        val tmp = YamlDoc.parse((if (doc.hasBom) "\uFEFF" else "") + lines.joinToString(doc.eol))
        return tmp
    }

    // ---------------------------------------------------------------- 便捷

    fun apply(text: String, ops: List<Pair<String, Any?>>): String {
        var doc = YamlDoc.parse(text)
        for ((path, value) in ops) doc = setValue(doc, path, value)
        return doc.dump()
    }

    fun remove(text: String, paths: List<String>): String {
        var doc = YamlDoc.parse(text)
        for (p in paths) doc = removeKey(doc, p)
        return doc.dump()
    }
}
