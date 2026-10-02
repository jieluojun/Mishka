// 引擎对拍用的命令行壳：把「文本 + 操作序列」喂给 Kotlin 版的 YamlEngine，输出结果文本。
//
// 目的：Kotlin 引擎与 tools/forms/forms_model.py（规范 + PyYAML 性质测试）逐字节对拍，
// 保证两边等价。驱动脚本：tools/forms/engine_diff.py
//
// 编译（只依赖标准库，不需要 Android SDK）：
//   kotlinc mishka-custom/app/src/main/kotlin/top/yukonga/mishka/custom/forms/YamlEngine.kt \
//           mishka-custom/tools/forms/kotlin/FormEngineCli.kt -include-runtime -d /tmp/engine.jar
//   java -jar /tmp/engine.jar <用例文件>
//
// 用例文件格式（\u0001 分隔字段，\u0002 分隔 map 的键值对）：
//   @@CASE
//   @@TEXT
//   <YAML 原文行…>
//   @@ENDTEXT
//   set \u0001 path \u0001 type \u0001 value      改值 / 新增（path 可含 `[n]` 下标段）
//   del \u0001 path                              删键 / 删项
//   ins \u0001 seqpath \u0001 index \u0001 type \u0001 value   在序列第 index 项前插入
//   rmi \u0001 seqpath \u0001 index              删序列第 index 项
//   mov \u0001 seqpath \u0001 from \u0001 to       挪动序列项
//   ren \u0001 path \u0001 newkey                改键名
//   @@OUT
//   <引擎输出的 YAML 行…>
//   @@ENDOUT
//   @@ENDCASE
//
// type: s 字符串 / b 布尔 / i 整数 / l 字符串列表 / m 字符串映射 / n null / j JSON（嵌套值）
import top.yukonga.mishka.custom.forms.YamlDoc
import top.yukonga.mishka.custom.forms.YamlPatch
import java.io.File

private const val FS = '\u0001'
private const val FP = '\u0002'

/** 极小的 JSON 解析器（只给对拍用，嵌套的代理 / 隧道值靠它传过来）。 */
private class Json(private val s: String) {
    private var i = 0

    fun parse(): Any? {
        val v = value()
        ws()
        return v
    }

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun value(): Any? {
        ws()
        if (i >= s.length) return null
        return when (s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> num()
        }
    }

    private fun obj(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        i++
        ws()
        if (i < s.length && s[i] == '}') { i++; return out }
        while (i < s.length) {
            ws()
            val k = str()
            ws(); i++   // ':'
            out[k] = value()
            ws()
            if (i < s.length && s[i] == ',') { i++; continue }
            if (i < s.length && s[i] == '}') { i++; break }
            break
        }
        return out
    }

    private fun arr(): List<Any?> {
        val out = ArrayList<Any?>()
        i++
        ws()
        if (i < s.length && s[i] == ']') { i++; return out }
        while (i < s.length) {
            out.add(value())
            ws()
            if (i < s.length && s[i] == ',') { i++; continue }
            if (i < s.length && s[i] == ']') { i++; break }
            break
        }
        return out
    }

    private fun str(): String {
        val sb = StringBuilder()
        i++   // 开引号
        while (i < s.length && s[i] != '"') {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                i++
                when (val e = s[i]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
            } else sb.append(c)
            i++
        }
        i++   // 闭引号
        return sb.toString()
    }

    private fun num(): Any {
        val a = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        val t = s.substring(a, i)
        return t.toIntOrNull() ?: t.toLongOrNull() ?: t.toDoubleOrNull() ?: 0
    }
}

private fun decodeValue(type: String, raw: String): Any? = when (type) {
    "s" -> raw
    "b" -> raw == "true"
    "i" -> raw.toIntOrNull() ?: raw.toDoubleOrNull() ?: 0
    "n" -> null
    "j" -> Json(raw).parse()
    "l" -> if (raw.isEmpty()) emptyList<String>() else raw.split(FS).toList()
    "m" -> {
        if (raw.isEmpty()) emptyMap<String, String>()
        else raw.split(FS).associate { pair ->
            val cut = pair.indexOf(FP)
            pair.substring(0, cut) to pair.substring(cut + 1)
        }
    }
    else -> raw
}

private class Op(val action: String, val path: String, val a: Int, val b: Int, val value: Any?, val text: String)

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("用法：java -jar engine.jar <用例文件>")
        kotlin.system.exitProcess(2)
    }
    val lines = File(args[0]).readText(Charsets.UTF_8).split("\n")
    val out = StringBuilder()
    var i = 0
    var inCase = false
    val text = ArrayList<String>()
    val ops = ArrayList<Op>()
    var mode = ""
    while (i < lines.size) {
        val line = lines[i]
        when {
            line == "@@CASE" -> { inCase = true; text.clear(); ops.clear() }
            line == "@@TEXT" -> mode = "text"
            line == "@@ENDTEXT" -> mode = ""
            line == "@@OUT" -> {
                var cur = YamlDoc.parse(text.joinToString("\n"))
                for (op in ops) {
                    val seg = YamlDoc.splitPath(op.path)
                    cur = when (op.action) {
                        "set" -> YamlPatch.setValue(cur, seg, op.value)
                        "del" -> YamlPatch.removeKey(cur, seg)
                        "ins" -> YamlPatch.insertItem(cur, seg, op.a, op.value)
                        "rmi" -> YamlPatch.removeItem(cur, seg, op.a)
                        "mov" -> YamlPatch.moveItem(cur, seg, op.a, op.b)
                        "ren" -> YamlPatch.renameKey(cur, seg, op.text)
                        else -> cur
                    }
                }
                out.append("@@OUT\n").append(cur.dump()).append("\n@@ENDOUT\n")
                mode = "out"
            }
            line == "@@ENDOUT" -> mode = ""
            line == "@@ENDCASE" -> { inCase = false; out.append("@@ENDCASE\n") }
            mode == "text" -> text.add(line)
            inCase && mode.isEmpty() && line.isNotEmpty() -> {
                val parts = line.split(FS)
                when (parts[0]) {
                    // 值里可能含 FS 分隔符（列表/映射），必须把值段之后重新拼回来
                    "set" -> if (parts.size >= 4) {
                        val raw = parts.drop(3).joinToString(FS.toString())
                        ops.add(Op("set", parts[1], 0, 0, decodeValue(parts[2], raw), ""))
                    }
                    "setnull" -> if (parts.size >= 2) ops.add(Op("set", parts[1], 0, 0, null, ""))
                    "del" -> if (parts.size >= 2) ops.add(Op("del", parts[1], 0, 0, null, ""))
                    "ins" -> if (parts.size >= 5) {
                        val raw = parts.drop(4).joinToString(FS.toString())
                        ops.add(Op("ins", parts[1], parts[2].toInt(), 0, decodeValue(parts[3], raw), ""))
                    }
                    "rmi" -> if (parts.size >= 3) ops.add(Op("rmi", parts[1], parts[2].toInt(), 0, null, ""))
                    "mov" -> if (parts.size >= 4) ops.add(Op("mov", parts[1], parts[2].toInt(), parts[3].toInt(), null, ""))
                    "ren" -> if (parts.size >= 3) ops.add(Op("ren", parts[1], 0, 0, null, parts.drop(2).joinToString(FS.toString())))
                }
            }
        }
        i++
    }
    print(out.toString())
}
