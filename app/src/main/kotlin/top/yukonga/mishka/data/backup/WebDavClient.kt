package top.yukonga.mishka.data.backup

import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSuccess
import io.ktor.util.cio.readChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

class WebDavException(message: String) : Exception(message)

/**
 * 极简 WebDAV 客户端：固定文件名覆盖式备份，只需 MKCOL / PUT / GET 三个动词，
 * 不做 PROPFIND 列目录（无多版本历史）。HttpClient 每次操作短生命周期构建、用完即 close。
 *
 * 重定向自行处理（[davRequest]）：Ktor 默认只对 GET/HEAD 跟随 3xx，而不少服务器
 * （Apache mod_dir、部分 NAS 网关）会把无尾斜杠的目录请求 301 到带斜杠版本，
 * MKCOL/PUT 收到 301 直接失败。集合 URL 一律带尾斜杠 + 同主机内跟随重定向双保险。
 */
class WebDavClient(
    baseUrl: String,
    username: String,
    password: String,
) {
    private val root = baseUrl.trim().trimEnd('/')

    // RFC 4918 集合 URL 以 '/' 结尾，避免服务器对无斜杠目录路径 canonicalize 重定向
    private val dirUrl = "$root/$BACKUP_DIR/"
    private val fileUrl = "$dirUrl$BACKUP_FILE"
    private val authorization = "Basic " + Base64.encodeToString(
        "$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP,
    )

    private fun buildClient() = HttpClient(OkHttp) {
        expectSuccess = false
        // 重定向统一走 davRequest 手动处理，禁用内置插件防止 GET 跟随时丢 Authorization
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 300_000
        }
    }

    /** 上传备份（覆盖写）。目录不存在时先 MKCOL 创建。 */
    suspend fun upload(file: File) {
        buildClient().use { client ->
            client.ensureDir()
            val resp = client.davRequest(fileUrl, HttpMethod.Put, payload = file)
            if (!resp.status.isSuccess()) {
                throw WebDavException("PUT failed: HTTP ${resp.status.value}")
            }
        }
    }

    /** 下载备份到 [target]；服务器上尚无备份（404）返回 false。 */
    suspend fun download(target: File): Boolean {
        buildClient().use { client ->
            val resp = client.davRequest(fileUrl, HttpMethod.Get)
            return when {
                resp.status == HttpStatusCode.NotFound -> false
                resp.status.isSuccess() -> {
                    // 备份可达数十 MB，落盘而非整包进堆
                    withContext(Dispatchers.IO) {
                        resp.bodyAsChannel().toInputStream().use { input ->
                            target.outputStream().use { input.copyTo(it) }
                        }
                    }
                    true
                }

                else -> throw WebDavException("GET failed: HTTP ${resp.status.value}")
            }
        }
    }

    /** 测试连接：确保备份目录可建/已存在。地址、凭据、权限任一有问题都会在此抛出。 */
    suspend fun testConnection() {
        buildClient().use { client ->
            client.ensureDir()
        }
    }

    private suspend fun HttpClient.ensureDir() {
        val resp = davRequest(dirUrl, HttpMethod("MKCOL"))
        // 201 = 新建成功；405 = 目录已存在（RFC 4918：MKCOL 对已存在资源必须 405）
        val ok = resp.status.isSuccess() || resp.status == HttpStatusCode.MethodNotAllowed
        if (!ok) {
            val hint = when (resp.status) {
                HttpStatusCode.Unauthorized -> "unauthorized"
                HttpStatusCode.Conflict -> "parent directory missing"
                else -> "HTTP ${resp.status.value}"
            }
            throw WebDavException("MKCOL failed: $hint")
        }
    }

    /**
     * 发请求并手动跟随 301/302/307/308（保持方法与 body）。仅限同主机——
     * Authorization 携带 Basic 凭据，跨主机跟随会把密码泄露给 Location 指向的第三方。
     */
    private suspend fun HttpClient.davRequest(
        url: String,
        httpMethod: HttpMethod,
        payload: File? = null,
    ): HttpResponse {
        var current = url
        var hops = 0
        while (true) {
            val resp = request(current) {
                method = httpMethod
                header(HttpHeaders.Authorization, authorization)
                if (payload != null) setBody(payload.zipContent())
            }
            if (resp.status.value !in REDIRECT_CODES || hops >= MAX_REDIRECTS) return resp
            val location = resp.headers[HttpHeaders.Location] ?: return resp
            val next = runCatching { URI(current).resolve(location) }.getOrNull() ?: return resp
            if (!next.carriesCredentialsSafelyFrom(URI(current))) return resp
            current = next.toString()
            hops++
        }
    }

    /**
     * 跟随重定向时是否仍可安全携带 Basic 凭据。跨主机会把密码送给第三方；同主机的
     * https→http 降级则是把它明文发出去。允许 http→https 升级，禁止反向。
     */
    /**
     * 以文件为 body：带 Content-Length（部分 WebDAV 服务端拒绝 chunked PUT），
     * 且重定向重发时可再读一遍。
     */
    private fun File.zipContent(): OutgoingContent = object : OutgoingContent.ReadChannelContent() {
        override val contentType = ContentType.Application.Zip
        override val contentLength = length()
        override fun readFrom(): ByteReadChannel = readChannel()
    }

    private fun URI.carriesCredentialsSafelyFrom(from: URI): Boolean {
        if (host == null || !host.equals(from.host, ignoreCase = true)) return false
        val fromHttps = from.scheme.equals("https", ignoreCase = true)
        val toHttps = scheme.equals("https", ignoreCase = true)
        return toHttps || !fromHttps
    }

    companion object {
        const val BACKUP_DIR = "Mishka"
        const val BACKUP_FILE = "mishka-backup.zip"
        private val REDIRECT_CODES = setOf(301, 302, 307, 308)
        private const val MAX_REDIRECTS = 3
    }
}
