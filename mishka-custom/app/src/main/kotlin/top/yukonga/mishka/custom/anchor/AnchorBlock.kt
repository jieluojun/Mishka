package top.yukonga.mishka.custom.anchor

import androidx.compose.runtime.Immutable

/**
 * 定义块的可视化模型：把 `键: &锚点` 带的那一块 YAML 拆成「参数行 / 列表项」，
 * 编辑后仍在**原文行**上做替换（未改动的行逐字保留），不重新 dump 整块。
 *
 * 与 mihomo_box 的差别：那边用 js-yaml 的节点范围做保真替换，Kotlin 侧没有 YAML 依赖，
 * 因此只支持能安全拆解的两种形态——映射（键值对）与序列（逐项）——嵌套多行的值不在这里改，
 * 提示走「文本」模式（整块原文编辑）。这条边界是刻意画的：拆不动的形态宁可让用户看原文，
 * 也不能让面板猜错层级把配置写坏。
 */

@Immutable
enum class DefKind { Map, Seq, Scalar, Unknown }

@Immutable
data class DefEntrySeed(
    val keyRaw: String,
    val value: String,
    val comment: String,
    val indent: String,
    val startIdx: Int,
    val endIdx: Int,
    val nested: Boolean,
)

@Immutable
data class DefBlock(
    val name: String,
    val keyDisplay: String,
    val keyRaw: String,
    val indent: Int,
    val dash: Boolean,
    val kind: DefKind,
    val headerValue: String,
    val headerComment: String,
    val entries: List<DefEntrySeed>,
    val lines: List<String>,
    val startLine1: Int,
    val path: String,
) {
    /** 能否用可视化表单编辑（Scalar 单值也走一个值框，故一并算可视化）。 */
    val visual: Boolean get() = kind != DefKind.Unknown
    val lineCount: Int get() = lines.size
}

/** 一行可编辑内容。[seed] 为 null 表示新增行。 */
@Immutable
data class DefRow(
    val seed: DefEntrySeed?,
    val key: String,
    val value: String,
)

object AnchorBlock {

    private val SeqItemRegex = Regex("^(\\s*)-\\s*(.*)$")
    private val MapEntryRegex = Regex("^(\\s*)([^:#][^:]*?)\\s*:\\s*(.*)$")

    fun parse(lines: List<String>, startIndex: Int, endIndex: Int, name: String): DefBlock? {
        val header = lines.getOrNull(startIndex) ?: return null
        val shape = AnchorEdit.parseDefLine(header) ?: return null
        if (shape.name != name) return null
        val path = AnchorScan.pathOf(lines, startIndex)
        val blockLines = lines.subList(startIndex, endIndex + 1).toList()
        val headerComment = AnchorScan.trailingComment(header)
        val bodyStart = 1
        val bodyEnd = blockLines.lastIndex

        val headerValue = AnchorScan.withoutComment(shape.value).trim()
        if (headerValue.isNotEmpty()) {
            return DefBlock(
                name = name, keyDisplay = shape.key, keyRaw = shape.keyRaw, indent = shape.indent, dash = shape.dash,
                kind = DefKind.Scalar, headerValue = headerValue, headerComment = headerComment,
                entries = emptyList(), lines = blockLines, startLine1 = startIndex + 1, path = path,
            )
        }

        val nonBlank = (bodyStart..bodyEnd).filter { blockLines[it].isNotBlank() }
        if (nonBlank.isEmpty()) {
            return DefBlock(
                name = name, keyDisplay = shape.key, keyRaw = shape.keyRaw, indent = shape.indent, dash = shape.dash,
                kind = DefKind.Scalar, headerValue = "", headerComment = headerComment,
                entries = emptyList(), lines = blockLines, startLine1 = startIndex + 1, path = path,
            )
        }

        val firstBody = blockLines[nonBlank.first()]
        // 缩进后的 flow 根（`k: &u` + 缩进一行的 `{a: 1}`）必须先判定 Unknown：否则 MapEntryRegex 会
        // 把 `{a` 当成键名、把 `1}` 当成值，逐项编辑就成了对着 flow 容器瞎猜。
        val flowRoot = firstBody.trimStart()
        val kind = when {
            SeqItemRegex.matches(firstBody) -> DefKind.Seq
            flowRoot.startsWith("{") || flowRoot.startsWith("[") -> DefKind.Unknown
            MapEntryRegex.matches(firstBody) -> DefKind.Map
            else -> DefKind.Unknown
        }
        if (kind == DefKind.Unknown) {
            return DefBlock(
                name = name, keyDisplay = shape.key, keyRaw = shape.keyRaw, indent = shape.indent, dash = shape.dash,
                kind = DefKind.Unknown, headerValue = "", headerComment = headerComment,
                entries = emptyList(), lines = blockLines, startLine1 = startIndex + 1, path = path,
            )
        }

        val entries = mutableListOf<DefEntrySeed>()
        var i = bodyStart
        while (i <= bodyEnd) {
            val line = blockLines[i]
            if (line.isBlank() || AnchorScan.indentOf(line) <= shape.indent) {
                i++
                continue
            }
            val m = if (kind == DefKind.Seq) SeqItemRegex.find(line) else MapEntryRegex.find(line)
            if (m == null) {
                i++
                continue
            }
            var end = i
            var j = i + 1
            while (j <= bodyEnd) {
                val next = blockLines[j]
                if (next.isBlank()) {
                    j++
                    continue
                }
                if (AnchorScan.indentOf(next) > AnchorScan.indentOf(line)) {
                    end = j
                    j++
                } else {
                    break
                }
            }
            val rawValue = if (kind == DefKind.Seq) m.groupValues[2].trim() else m.groupValues[3].trim()
            entries += DefEntrySeed(
                keyRaw = if (kind == DefKind.Seq) "" else m.groupValues[2].trim(),
                value = AnchorScan.withoutComment(rawValue).trim(),
                comment = AnchorScan.trailingComment(line),
                indent = m.groupValues[1],
                startIdx = i,
                endIdx = end,
                nested = end > i,
            )
            i = end + 1
        }
        return DefBlock(
            name = name, keyDisplay = shape.key, keyRaw = shape.keyRaw, indent = shape.indent, dash = shape.dash,
            kind = kind, headerValue = "", headerComment = headerComment,
            entries = entries, lines = blockLines, startLine1 = startIndex + 1, path = path,
        )
    }

    /**
     * 按 [rows] 生成新的块文本：改过值的行整行重写（保留行尾注释），删掉的行连同其嵌套子行一起消失，
     * 其余行逐字保留，新增行追加在块尾。
     */
    fun render(block: DefBlock, rows: List<DefRow>): String {
        if (block.kind == DefKind.Scalar) {
            val value = rows.firstOrNull()?.value.orEmpty()
            return renderHeader(block, value)
        }
        if (block.kind == DefKind.Unknown) return block.lines.joinToString("\n")

        val out = ArrayList<String>(block.lines.size + 4)
        out += block.lines[0]
        var idx = 1
        while (idx < block.lines.size) {
            val seed = block.entries.firstOrNull { it.startIdx == idx }
            if (seed == null) {
                out += block.lines[idx]
                idx++
                continue
            }
            val row = rows.firstOrNull { it.seed === seed }
            when {
                row == null -> Unit // 删除：连同 startIdx..endIdx 一起跳过
                seed.nested || row.value == seed.value -> out.addAll(block.lines.subList(seed.startIdx, seed.endIdx + 1))
                else -> out += renderEntry(block, seed.keyRaw, row.value, seed.comment, seed.indent)
            }
            idx = seed.endIdx + 1
        }
        rows.filter { it.seed == null }.forEach { row ->
            out += renderEntry(block, row.key, row.value, "", defaultIndent(block))
        }
        return out.joinToString("\n")
    }

    private fun renderHeader(block: DefBlock, value: String): String {
        val sb = StringBuilder()
        sb.append(" ".repeat(block.indent))
        if (block.dash) sb.append("- ")
        sb.append(block.keyRaw).append(": &").append(block.name)
        if (value.isNotEmpty()) sb.append(' ').append(value)
        if (block.headerComment.isNotEmpty()) sb.append(' ').append(block.headerComment)
        return sb.toString()
    }

    private fun renderEntry(block: DefBlock, keyRaw: String, value: String, comment: String, indent: String): String {
        val sb = StringBuilder()
        sb.append(indent)
        if (block.kind == DefKind.Seq) {
            sb.append("-")
            if (value.isNotEmpty()) sb.append(' ').append(value)
        } else {
            sb.append(keyRaw).append(':')
            if (value.isNotEmpty()) sb.append(' ').append(value)
        }
        if (comment.isNotEmpty()) sb.append(' ').append(comment)
        return sb.toString()
    }

    private fun defaultIndent(block: DefBlock): String {
        val existing = block.entries.firstOrNull { it.indent.length > block.indent }?.indent
        return existing ?: " ".repeat(block.indent + 2)
    }

    /**
     * 新建顶层定义块：内容少且不长时压成一行 flow（mihomo_box 的 160 字符规则），
     * 否则退回块式——两者对 mihomo 等价，前者在人读的配置里更省行。
     */
    fun buildTopBlock(topKey: String, anchor: String, rows: List<DefRow>): String {
        val pairs = rows.map { it.key to it.value }
        val inline = pairs.joinToString(", ") { (key, value) -> if (value.isEmpty()) "$key:" else "$key: $value" }
        val oneLine = "$topKey: &$anchor {$inline}"
        if (oneLine.length <= 160 && pairs.isNotEmpty()) return oneLine
        val sb = StringBuilder()
        sb.append(topKey).append(": &").append(anchor)
        pairs.forEach { (key, value) ->
            sb.append('\n').append("  ").append(key).append(':')
            if (value.isNotEmpty()) sb.append(' ').append(value)
        }
        return sb.toString()
    }
}
