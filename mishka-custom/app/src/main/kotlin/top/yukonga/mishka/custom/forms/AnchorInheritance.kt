package top.yukonga.mishka.custom.forms

/**
 * 条目级（一级）/ 字段级（二级）锚点的现状读取与行级手术，对齐 mihomo_box `anchorSection`
 * （pages-flow.js）的语义子集：
 *  - 一级 = 条目键行（映射的 `名字:` / 序列的 `- `）块内的 `<<: *name` 合并继承：换绑 / 清除 / 新挂；
 *  - 二级 = 条目内子字段的 `<<: *name` 合并与 `键: *name` 整体引用：挂 / 换绑 / 摘除；
 *  - `&定义` 动作已下线（与参考实现一致）：存量字段级 `&名` 只允许摘除，不新挂、不改名。
 *
 * 现状全部从 [YamlDoc] 行模型读（`<<` 合并键、alias、anchor 都在模型里标好），手术只重写目标行，
 * 其余行逐字不动；形态拆不动（flow 单行条目、多合并来源）返回 null 由 UI 提示走编辑器。
 */

/** 条目的一级继承现状。[mergeLines] 为 `<<:` 行号（1 基）；[multi] = 多来源，UI 锁定不让改。 */
internal data class EntryMergeState(
    val merges: List<String>,
    val mergeLines: List<Int>,
    val multi: Boolean,
)

/** 条目内一个本地子字段的二级锚点现状。[absent] = 源码没有这个字段的本地行。 */
internal data class FieldAnchorState(
    val key: String,
    val line1: Int,
    val defAnchor: String?,
    val aliasRef: String?,
    val merges: List<String>,
    val multi: Boolean,
    val isMap: Boolean,
    val absent: Boolean = false,
)

/** 全文锚点定义：名字 / 定义行（1 基）/ 是否为映射（`<<:` 只能指向映射）。 */
internal data class AnchorDefInfo(val name: String, val line1: Int, val isMap: Boolean)

internal object AnchorInheritance {

    // ==================== 现状读取 ====================

    fun entryMerges(doc: YamlDoc, base: YPath): EntryMergeState {
        val n = doc.get(base) ?: return EntryMergeState(emptyList(), emptyList(), false)
        val merges = ArrayList<String>()
        val lines = ArrayList<Int>()
        if (n.kind == YamlNode.Kind.MAP || n.kind == YamlNode.Kind.SEQ) {
            for (e in n.entries) {
                if (!e.isMerge) continue
                collectAliases(e.node, merges)
                lines.add(e.line + 1)
            }
        }
        return EntryMergeState(merges, lines, merges.size > 1 || lines.size > 1)
    }

    private fun collectAliases(node: YamlNode?, out: MutableList<String>) {
        when {
            node == null -> Unit
            node.kind == YamlNode.Kind.SEQ -> node.items.forEach { it.alias?.let(out::add) }
            else -> node.alias?.let(out::add)
        }
    }

    /** 条目内本地子字段的二级现状；flow 单行条目拆不动，返回空（UI 提示走一级或文本）。 */
    fun fieldStates(doc: YamlDoc, base: YPath): List<FieldAnchorState> {
        val n = doc.get(base) ?: return emptyList()
        if (n.kind != YamlNode.Kind.MAP || n.flow) return emptyList()
        return n.entries.filter { !it.isMerge }.map { e ->
            val v = e.node
            val merges = ArrayList<String>()
            var multi = false
            if (v != null && v.kind == YamlNode.Kind.MAP && !v.flow) {
                val ms = v.entries.filter { it.isMerge }
                ms.forEach { m -> collectAliases(m.node, merges) }
                multi = ms.size > 1
            }
            FieldAnchorState(
                key = e.key,
                line1 = e.line + 1,
                defAnchor = v?.anchor,
                aliasRef = v?.takeIf { it.kind == YamlNode.Kind.RAW && !it.multi }?.alias,
                merges = merges,
                multi = multi,
                isMap = v?.kind == YamlNode.Kind.MAP && !v.flow,
            )
        }
    }

    /** 全文 `&定义` 清单（递归走节点树）：候选列表与「定义在引用行之前」硬规则用它。 */
    fun anchorDefs(doc: YamlDoc): List<AnchorDefInfo> {
        val out = ArrayList<AnchorDefInfo>()
        fun walk(n: YamlNode?) {
            if (n == null) return
            n.anchor?.let { out.add(AnchorDefInfo(it, n.start + 1, n.kind == YamlNode.Kind.MAP && !n.flow)) }
            n.entries.forEach { walk(it.node) }
            n.items.forEach { walk(it) }
        }
        walk(doc.root)
        return out
    }

    // ==================== 行级手术 ====================

    /** 行尾裸注释（含 `#`），无则空串。本地复刻一份，避免 forms 包依赖 anchor 包（离线对拍要单编本包）。 */
    private fun trailingComment(line: String): String {
        var quote: Char? = null
        for (i in line.indices) {
            val c = line[i]
            if (quote != null) {
                if (c == quote) quote = null
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '#' -> if (i == 0 || line[i - 1].isWhitespace()) return line.substring(i).trimEnd()
            }
        }
        return ""
    }

    private fun indentOf(line: String): Int = line.takeWhile { it == ' ' || it == '\t' }.length

    /** 条目块子行的缩进：首个子键 / 首个序列项的缩进，没有就按表头缩进 + 2。 */
    private fun childIndent(n: YamlNode): Int {
        val head = indentOfLine(n)
        val first = n.entries.firstOrNull()?.keyIndent ?: n.items.firstOrNull()?.indent
        return first ?: head + 2
    }

    /** 表头行（映射键行 / dash 行）的缩进。 */
    private fun indentOfLine(n: YamlNode): Int = n.indent

    /**
     * 表头行下标（0 基）：序列项 = dash 行；flow = 自身行；块映射的 [YamlNode.start] 是第一个子行，
     * 键行在它前一行。
     */
    fun headLine0(n: YamlNode): Int = when {
        n.dash >= 0 -> n.dash
        n.flow -> n.start
        else -> n.start - 1
    }

    private fun MutableList<String>.replaceAt(index: Int, line: String) { this[index] = line }

    /**
     * 一级继承换绑 / 清除 / 新挂。[name] 为 null = 不继承（删 `<<:` 行）。
     * 多来源、flow 条目返回 null（拒绝）。dash 行内联 `<<`（`- <<: *a`）只替换段、不动 dash。
     */
    fun setEntryMerge(doc: YamlDoc, base: YPath, name: String?): String? {
        val n = doc.get(base) ?: return null
        if (n.flow) return null
        val st = entryMerges(doc, base)
        if (st.multi) return null
        val lines = doc.lines.toMutableList()
        if (st.mergeLines.isNotEmpty()) {
            val li = st.mergeLines.first() - 1
            val raw = lines[li]
            val trimmed = raw.trimStart()
            val comment = trailingComment(raw)
            val tail = if (comment.isEmpty()) "" else " " + comment
            if (trimmed.startsWith("<<")) {
                if (name == null) lines.removeAt(li)
                else lines.replaceAt(li, " ".repeat(indentOf(raw)) + "<<: *" + name + tail)
            } else {
                // dash 行内联（`- <<: *a` / `- &x <<: *a`）：只摘 / 换 `<<:` 段
                val re = Regex("<<\\s*:\\s*\\*[^\\s]+")
                if (name == null) {
                    val stripped = re.replace(raw, "").replace(Regex("\\s+$"), "")
                    lines.replaceAt(li, stripped)
                } else {
                    lines.replaceAt(li, re.replace(raw, "<<: *" + name))
                }
            }
            return lines.joinToString("\n")
        }
        if (name == null) return null
        val head = headLine0(n)
        lines.add(head + 1, " ".repeat(childIndent(n)) + "<<: *" + name)
        return lines.joinToString("\n")
    }

    /**
     * 二级整体引用 `键: *name`。[name] 为 null = 清除引用，用 [restore] 回填本地值
     * （展开视图里该字段的生效标量；不是标量时调用方传 null → 拒绝，宁不动）。
     */
    fun setFieldAlias(doc: YamlDoc, base: YPath, key: String, name: String?, restore: String?): String? {
        val n = doc.get(base) ?: return null
        if (n.kind != YamlNode.Kind.MAP || n.flow) return null
        val e = doc.findEntry(n, key)
        val lines = doc.lines.toMutableList()
        val ci = childIndent(n)
        if (e == null) {
            if (name == null) return null
            val head = headLine0(n)
            lines.add(head + 1, " ".repeat(ci) + key + ": *" + name)
            return lines.joinToString("\n")
        }
        val li = e.line
        val raw = lines[li]
        val comment = trailingComment(raw)
        val tail = if (comment.isEmpty()) "" else " " + comment
        val v = e.node
        val blockEnd = v?.end ?: li + 1
        if (name != null) {
            // 块式子行一起删：整段换成 `键: *name`
            repeat(blockEnd - 1 - li) { lines.removeAt(li + 1) }
            lines.replaceAt(li, " ".repeat(e.keyIndent) + key + ": *" + name + tail)
        } else {
            if (restore == null) return null
            repeat(blockEnd - 1 - li) { lines.removeAt(li + 1) }
            lines.replaceAt(li, " ".repeat(e.keyIndent) + key + ": " + restore + tail)
        }
        return lines.joinToString("\n")
    }

    /**
     * 二级合并继承：字段块内挂 / 换绑 / 摘 `<<: *name`。字段不存在时新建 `键:` + `<<:` 两行；
     * 字段是标量 / 别名（合并无意义）返回 null。
     */
    fun setFieldMerge(doc: YamlDoc, base: YPath, key: String, name: String?): String? {
        val n = doc.get(base) ?: return null
        if (n.kind != YamlNode.Kind.MAP || n.flow) return null
        val e = doc.findEntry(n, key)
        val lines = doc.lines.toMutableList()
        val ci = childIndent(n)
        if (e == null) {
            if (name == null) return null
            val head = headLine0(n)
            lines.add(head + 1, " ".repeat(ci) + key + ":")
            lines.add(head + 2, " ".repeat(ci + 2) + "<<: *" + name)
            return lines.joinToString("\n")
        }
        val v = e.node
        val emptyValue = v == null || doc.isNullText(v)
        if (!emptyValue && !(v.kind == YamlNode.Kind.MAP && !v.flow)) return null
        val merges = ArrayList<String>()
        val mergeLines = ArrayList<Int>()
        if (v != null && v.kind == YamlNode.Kind.MAP) {
            for (m in v.entries) {
                if (!m.isMerge) continue
                collectAliases(m.node, merges)
                mergeLines.add(m.line + 1)
            }
        }
        if (merges.size > 1 || mergeLines.size > 1) return null
        if (mergeLines.isNotEmpty()) {
            val li = mergeLines.first() - 1
            val raw = lines[li]
            val comment = trailingComment(raw)
            val tail = if (comment.isEmpty()) "" else " " + comment
            if (name == null) lines.removeAt(li)
            else lines.replaceAt(li, " ".repeat(indentOf(raw)) + "<<: *" + name + tail)
            return lines.joinToString("\n")
        }
        if (name == null) return null
        val fieldIndent = e.keyIndent
        lines.add(e.line + 1, " ".repeat(fieldIndent + 2) + "<<: *" + name)
        return lines.joinToString("\n")
    }

    /** 摘除存量字段级 `&名`（定义动作下线后的唯一出口）：`键: &x 值` → `键: 值`。 */
    fun removeFieldDef(doc: YamlDoc, base: YPath, key: String): String? {
        val n = doc.get(base) ?: return null
        if (n.kind != YamlNode.Kind.MAP || n.flow) return null
        val e = doc.findEntry(n, key) ?: return null
        val v = e.node ?: return null
        val def = v.anchor ?: return null
        val li = e.line
        val lines = doc.lines.toMutableList()
        val raw = lines[li]
        val token = "&" + def
        val at = raw.indexOf(token)
        if (at < 0) return null
        var tailStart = at + token.length
        while (tailStart < raw.length && raw[tailStart] == ' ') tailStart++
        val head = raw.substring(0, at).trimEnd(' ')
        val tail = raw.substring(tailStart)
        lines[li] = if (tail.isEmpty()) head else head + " " + tail
        return lines.joinToString("\n")
    }
}
