// 引擎对拍用的命令行壳：把「文本 + 操作序列」喂给 Kotlin 版的 YamlEngine，输出结果文本。
//
// 目的：Kotlin 引擎与 tools/forms/forms_model.py（规范 + 71 项 PyYAML 性质测试）逐字节对拍，
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
//   set \u0001 path \u0001 type \u0001 value
//   del \u0001 path
//   @@OUT
//   <引擎输出的 YAML 行…>
//   @@ENDOUT
//   @@ENDCASE
//
// type: s 字符串 / b 布尔 / i 整数 / l 字符串列表 / m 字符串映射 / n null
import top.yukonga.mishka.custom.forms.YamlDoc
import top.yukonga.mishka.custom.forms.YamlPatch
import java.io.File

private const val FS = '\u0001'
private const val FP = '\u0002'

private fun decodeValue(type: String, raw: String): Any? = when (type) {
    "s" -> raw
    "b" -> raw == "true"
    "i" -> raw.toIntOrNull() ?: raw.toDoubleOrNull() ?: 0
    "n" -> null
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
    val ops = ArrayList<Triple<String, String, Any?>>()
    var mode = ""
    while (i < lines.size) {
        val line = lines[i]
        when {
            line == "@@CASE" -> { inCase = true; text.clear(); ops.clear() }
            line == "@@TEXT" -> mode = "text"
            line == "@@ENDTEXT" -> mode = ""
            line == "@@OUT" -> {
                var cur = YamlDoc.parse(text.joinToString("\n"))
                for ((action, path, value) in ops) {
                    cur = when (action) {
                        "set" -> YamlPatch.setValue(cur, path, value)
                        "del" -> YamlPatch.removeKey(cur, path)
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
                    // 值里可能含 FS 分隔符（列表/映射），必须把第 4 段之后重新拼回来
                    "set" -> if (parts.size >= 4) {
                        val raw = parts.drop(3).joinToString(FS.toString())
                        ops.add(Triple("set", parts[1], decodeValue(parts[2], raw)))
                    }
                    "setnull" -> if (parts.size >= 2) ops.add(Triple("set", parts[1], null))
                    "del" -> if (parts.size >= 2) ops.add(Triple("del", parts[1], null))
                }
            }
        }
        i++
    }
    print(out.toString())
}
