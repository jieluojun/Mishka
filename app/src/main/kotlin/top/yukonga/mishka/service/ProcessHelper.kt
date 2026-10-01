package top.yukonga.mishka.service

/**
 * JNI 辅助：使用 fork+exec 启动子进程，不关闭继承的 fd。
 * 绕过 Android ProcessBuilder 强制关闭非标准 fd 的安全机制。
 */
object ProcessHelper {

    init {
        System.loadLibrary("mishka")
    }

    /**
     * fork+exec 启动进程，子进程继承所有 fd。
     * @param logFile 日志文件路径，子进程的 stdout/stderr 将重定向到此文件；null 则不捕获
     * @return 子进程 PID，失败返回 -1
     */
    external fun nativeForkExec(binary: String, args: Array<String>, workDir: String, logFile: String? = null): Int

    /** 发送信号：[force] 为 SIGKILL，否则 SIGTERM */
    external fun nativeKill(pid: Int, force: Boolean)

    /**
     * 判活。走 waitpid(WNOHANG) 而非 `/proc`——mihomo 是本进程 fork 的亲生子，
     * 退出后成僵尸，僵尸的 `/proc/<pid>` 仍在。顺带收割。
     */
    external fun nativeIsAlive(pid: Int): Boolean

    /** 等待子进程结束，最多 [timeoutMs]。返回退出码；被信号终止返回 128+signo；超时返回 -1 */
    external fun nativeWaitpid(pid: Int, timeoutMs: Int): Int
}
