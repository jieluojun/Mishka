// 字段整理对拍用的 CLI：读文件 → ConfigTidy.tidy → 原样写 stdout（不做任何换行加工）。
import top.yukonga.mishka.custom.forms.ConfigTidy
import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("用法: tidy <config.yaml>")
        return
    }
    val text = File(args[0]).readText()
    System.out.write(ConfigTidy.tidy(text).toByteArray(Charsets.UTF_8))
    System.out.flush()
}
