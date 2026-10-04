package top.yukonga.mishka.service

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

private const val TAG = "LogTail"

/** 回读窗口上限。200 行 mihomo 日志约 30KB，留足余量仍远小于整文件。 */
private const val DEFAULT_TAIL_BYTES = 256L * 1024

/**
 * 从文件尾部回读最后 [maxLines] 行。
 *
 * **不能 `readText().lines().takeLast()`**——debug 级别的长会话日志可达数十 MB，
 * 为拿几行把整个文件读进堆，在前台服务里直接 OOM。ROOT 路径走 `su tail -n` 同理。
 */
internal fun File.readLastLines(maxLines: Int, maxBytes: Long = DEFAULT_TAIL_BYTES): String {
    if (!isFile) return ""
    return try {
        RandomAccessFile(this, "r").use { raf ->
            val length = raf.length()
            val from = (length - maxBytes).coerceAtLeast(0)
            raf.seek(from)
            val buffer = ByteArray((length - from).toInt())
            raf.readFully(buffer)
            val lines = String(buffer, Charsets.UTF_8).lineSequence()
            // 未到文件开头时窗口起点会切在半行（也可能是半个 UTF-8 字符）中间，丢掉首行
            val usable = if (from > 0) lines.drop(1) else lines
            usable.toList().takeLast(maxLines).joinToString("\n").trim()
        }
    } catch (e: Exception) {
        Log.w(TAG, "failed to tail $name", e)
        ""
    }
}
