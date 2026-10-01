#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <signal.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <android/log.h>

#define TAG "ProcessHelper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/**
 * fork + exec 启动子进程，不关闭继承的 fd。
 * Android 的 ProcessBuilder 会在 fork 后关闭所有非标准 fd，
 * 这个函数绕过该限制，让子进程能继承 VPN 的 TUN fd。
 *
 * 返回子进程 PID，失败返回 -1。
 */
JNIEXPORT jint JNICALL
Java_top_yukonga_mishka_service_ProcessHelper_nativeForkExec(
        JNIEnv *env, jclass clazz,
        jstring jBinary, jobjectArray jArgs, jstring jWorkDir, jstring jLogFile) {

    const char *binary = (*env)->GetStringUTFChars(env, jBinary, NULL);
    const char *workDir = (*env)->GetStringUTFChars(env, jWorkDir, NULL);
    const char *logFile = jLogFile ? (*env)->GetStringUTFChars(env, jLogFile, NULL) : NULL;
    char **argv = NULL;
    int result = -1;
    int argc = 0;

    if (binary == NULL || workDir == NULL || (jLogFile != NULL && logFile == NULL)) {
        LOGE("GetStringUTFChars returned NULL");
        goto cleanup;
    }

    argc = (*env)->GetArrayLength(env, jArgs);
    // argv: [binary, args..., NULL]
    argv = (char **) calloc(argc + 2, sizeof(char *));
    if (argv == NULL) {
        LOGE("calloc for argv failed");
        goto cleanup;
    }
    argv[0] = strdup(binary);
    if (argv[0] == NULL) {
        LOGE("strdup binary failed");
        goto cleanup;
    }
    for (int i = 0; i < argc; i++) {
        jstring jArg = (jstring) (*env)->GetObjectArrayElement(env, jArgs, i);
        if (jArg == NULL) {
            LOGE("null arg at %d", i);
            goto cleanup;
        }
        const char *arg = (*env)->GetStringUTFChars(env, jArg, NULL);
        if (arg != NULL) {
            argv[i + 1] = strdup(arg);
            (*env)->ReleaseStringUTFChars(env, jArg, arg);
        }
        // 局部引用要显式释放：JNI 只保证 512 个 slot，argv 随新增 CLI flag 增长会突然溢出
        (*env)->DeleteLocalRef(env, jArg);
        if (argv[i + 1] == NULL) {
            LOGE("strdup arg %d failed", i);
            goto cleanup;
        }
    }

    LOGI("fork+exec: %s, workDir=%s, logFile=%s", binary, workDir, logFile ? logFile : "(null)");

    pid_t pid = fork();

    if (pid == 0) {
        // 子进程：脱离会话组，不关闭任何 fd，直接 exec。
        // fork 与 exec 之间只能调 async-signal-safe 函数——__android_log_print 要取锁，
        // 在这里调用可能死锁，**不要**往下面任何分支加日志
        setsid();

        if (chdir(workDir) != 0) {
            _exit(126);
        }

        if (logFile) {
            int logFd = open(logFile, O_WRONLY | O_CREAT | O_TRUNC, 0644);
            if (logFd >= 0) {
                dup2(logFd, STDOUT_FILENO);
                dup2(logFd, STDERR_FILENO);
                close(logFd);
            }
        } else {
            dup2(STDERR_FILENO, STDOUT_FILENO);
        }

        execv(binary, argv);
        _exit(127);
    }

    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
    } else {
        LOGI("child pid=%d", pid);
        result = pid;
    }

    cleanup:
    if (argv != NULL) {
        for (int i = 0; argv[i] != NULL; i++) {
            free(argv[i]);
        }
        free(argv);
    }
    if (binary != NULL) (*env)->ReleaseStringUTFChars(env, jBinary, binary);
    if (workDir != NULL) (*env)->ReleaseStringUTFChars(env, jWorkDir, workDir);
    if (logFile != NULL) (*env)->ReleaseStringUTFChars(env, jLogFile, logFile);

    return result;
}

/**
 * waitpid 的 EINTR 重试包装。裸 waitpid 被信号打断时返回 -1 而 status 保持初值 0，
 * WIFEXITED(0) 为真、WEXITSTATUS(0) 为 0，会把「被打断」误报成「正常退出」。
 */
static pid_t waitpid_eintr(pid_t pid, int *status, int options) {
    pid_t r;
    do {
        r = waitpid(pid, status, options);
    } while (r < 0 && errno == EINTR);
    return r;
}

/**
 * 向子进程发送信号：force 为 SIGKILL，否则 SIGTERM。
 */
JNIEXPORT void JNICALL
Java_top_yukonga_mishka_service_ProcessHelper_nativeKill(
        JNIEnv *env, jclass clazz, jint pid, jboolean force) {
    if (pid > 0) {
        int sig = force ? SIGKILL : SIGTERM;
        LOGI("killing pid=%d sig=%d", pid, sig);
        kill((pid_t) pid, sig);
    }
}

/**
 * 判活兼收割。**不能用 /proc 判活**：mihomo 是本进程 fork 的亲生子，退出后进入僵尸态，
 * 而僵尸的 /proc/<pid> 依然存在——除非恰好有别的线程 waitpid(-1) 把它收走，
 * 否则崩溃检测永远认为它还活着。WNOHANG 顺手把僵尸收掉。
 */
JNIEXPORT jboolean JNICALL
Java_top_yukonga_mishka_service_ProcessHelper_nativeIsAlive(
        JNIEnv *env, jclass clazz, jint pid) {
    if (pid <= 0) return JNI_FALSE;
    int status = 0;
    // 0：仍在运行；pid：刚收割掉；-1(ECHILD)：非本进程子进程或已被别处收割
    return waitpid_eintr((pid_t) pid, &status, WNOHANG) == 0 ? JNI_TRUE : JNI_FALSE;
}

/**
 * 等待子进程结束，最多 timeoutMs。返回退出码；被信号终止返回 128+signo；超时或非本进程
 * 子进程返回 -1。**不能无限期等**：调用点在 Service.onDestroy 的主线程上，而 mihomo 收
 * SIGTERM 后要关 TUN、断全部连接，卡住就是 ANR。
 */
JNIEXPORT jint JNICALL
Java_top_yukonga_mishka_service_ProcessHelper_nativeWaitpid(
        JNIEnv *env, jclass clazz, jint pid, jint timeoutMs) {
    if (pid <= 0) return -1;
    const long stepUs = 10 * 1000;
    long remainingUs = (long) timeoutMs * 1000;
    for (;;) {
        int status = 0;
        pid_t r = waitpid_eintr((pid_t) pid, &status, WNOHANG);
        if (r == (pid_t) pid) {
            if (WIFEXITED(status)) return WEXITSTATUS(status);
            if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
            return -1;
        }
        if (r < 0) return -1;
        if (remainingUs <= 0) {
            LOGE("waitpid pid=%d timed out after %dms", pid, timeoutMs);
            return -1;
        }
        usleep(stepUs);
        remainingUs -= stepUs;
    }
}
