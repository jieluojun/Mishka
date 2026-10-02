// 引用检查 / 改名同步对拍用的命令行壳：Kotlin 版 ConfigRefs（config-references.js 的移植）和 RenameSync
// 与参考实现的 JS 在同一批 YAML 上逐行对拍。驱动脚本：tools/forms/refs_diff.mjs
//
// 编译（只依赖标准库）：
//   kotlinc <forms 目录>/YamlEngine.kt FormSpecs.kt FormSpecsP2.kt FlowText.kt FormValues.kt ConfigRefs.kt \
//           tools/forms/kotlin/ConfigRefsCli.kt -include-runtime -d /tmp/refs.jar
//
// 用法：
//   java -jar /tmp/refs.jar refs <yaml 文件> <kind> <name>        → 一行一条：REF <位置> / ISSUE <位置> / ERR <原因>
//   java -jar /tmp/refs.jar plain <yaml 文件>                     → 展开后的 JSON（别名 / 合并键解开）
//   java -jar /tmp/refs.jar rename <yaml 文件> <kind> <old> <new> → 同步后的整篇 YAML（kind：proxies / proxy-groups /
//                                                                   proxy-providers / rule-providers），被引擎拒绝则输出 REFUSED
import top.yukonga.mishka.custom.forms.BatchOp
import top.yukonga.mishka.custom.forms.ConfigRefs
import top.yukonga.mishka.custom.forms.PlainYaml
import top.yukonga.mishka.custom.forms.RenameSync
import top.yukonga.mishka.custom.forms.YamlDoc
import top.yukonga.mishka.custom.forms.YamlPatch
import java.io.File

private fun json(v: Any?): String = when (v) {
    null -> "null"
    is Boolean -> v.toString()
    is Long, is Int, is Double -> v.toString()
    is String -> buildString {
        append('"')
        for (c in v) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }
    is Map<*, *> -> v.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
    is List<*> -> v.joinToString(",", "[", "]") { json(it) }
    else -> json(v.toString())
}

private fun indexOf(doc: YamlDoc, kind: String, name: String): Int {
    val seq = doc.get(listOf(kind)) ?: return -1
    return seq.items.indexOfFirst { item ->
        top.yukonga.mishka.custom.forms.FormValues.scalarText(doc, doc.findEntry(item, "name")?.node) == name
    }
}

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: error("用法见文件头")
    val doc = YamlDoc.parse(File(args[1]).readText())
    when (mode) {
        "plain" -> println(json(PlainYaml.toPlain(doc)))
        "refs" -> {
            val d = ConfigRefs.deletionState(doc, args[2], args[3])
            if (d.error != null) println("ERR " + d.error)
            for (r in d.references) println("REF $r")
            for (r in d.issues) println("ISSUE $r")
        }
        "rename" -> {
            val kind = args[2]; val old = args[3]; val new = args[4]
            val plan = when (kind) {
                "proxies" -> RenameSync.proxy(doc, listOf("proxies"), indexOf(doc, "proxies", old), old, new)
                "proxy-groups" -> RenameSync.group(doc, indexOf(doc, "proxy-groups", old), old, new)
                "proxy-providers" -> RenameSync.provider(doc, old, new)
                "rule-providers" -> RenameSync.ruleProvider(doc, old, new)
                else -> error("kind?")
            }
            if (plan.blocked != null) { println("REFUSED " + plan.blocked); return }
            var cur = doc
            for (op in plan.ops) {
                val next = when (op) {
                    is BatchOp.Set -> YamlPatch.setValue(cur, op.path, op.value)
                    is BatchOp.Remove -> if (cur.get(op.path) == null) cur else YamlPatch.removeKey(cur, op.path)
                    is BatchOp.SetItem -> YamlPatch.setItem(cur, op.seqPath, op.index, op.value)
                    is BatchOp.Rename -> YamlPatch.renameKey(cur, op.path, op.newKey)
                }
                if (next === cur && op !is BatchOp.Remove) { println("REFUSED"); return }
                cur = next
            }
            println("SYNCED ${plan.synced}")
            print(cur.dump())
        }
        else -> error("未知模式 $mode")
    }
}
