package top.yukonga.mishka.data.api

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.yukonga.mishka.data.repository.MihomoRepositoryImpl
import top.yukonga.mishka.domain.repository.MihomoRepository
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyServiceStatus
import top.yukonga.mishka.platform.ProxyState

/**
 * 全 app 共享 mihomo 客户端的生命周期管理器。
 *
 * - 订阅 [ProxyServiceBridge.state]：[ProxyState.Running] 时构造新 [MihomoRepository]，
 *   其他状态置 null；切换前同步 close 旧实例
 * - 暴露 [repository]：所有消费方（ViewModel / Service）只 collect 这一份，禁止自建 client
 * - 不做 endpoint 比对：每次 Running 都 close + new；attach 重连复用同 endpoint 多一次
 *   重建代价 < 50ms，远小于状态机出 race 的回归成本
 *
 * 使用方式：由 `dataModule` 以 `single` 提供（scope 取同模块的 application 级 CoroutineScope）。
 * `MishkaApplication.connectionManager` 只是给 Service 侧的取图入口，**任何地方都不要自己 new**。
 */
class MihomoConnectionManager(scope: CoroutineScope) {

    private val _repository = MutableStateFlow<MihomoRepository?>(null)
    val repository: StateFlow<MihomoRepository?> = _repository.asStateFlow()

    private val mutex = Mutex()
    private var current: MihomoRepository? = null

    init {
        scope.launch {
            // 副作用包在 collect 内部：抛出去这条流就永久终结，代理再启动也拿不到 repository。
            // 不能用 .catch——它是终结型的，捕获后同样不再重订阅
            ProxyServiceBridge.state.collect { status ->
                runCatching { applyStatus(status) }
                    .onFailure { Log.e(TAG, "failed to apply proxy status ${status.state}", it) }
            }
        }
    }

    private suspend fun applyStatus(status: ProxyServiceStatus) {
        val next: MihomoRepository? = when (status.state) {
            ProxyState.Running -> buildRepository(status)
            else -> null
        }
        replace(next)
    }

    /**
     * 原子替换：先持有 mutex，再切换字段与 StateFlow，最后 close 旧实例。
     * StateFlow 发射在 close 之前，确保下游 collector 立刻看到新 repo；旧 repo 的 close
     * 只影响其内部 HttpClient（消费方拿到新 repo 后会重订阅，不会用到旧 repo 的 flow）。
     */
    private suspend fun replace(next: MihomoRepository?) {
        mutex.withLock {
            val old = current
            current = next
            _repository.value = next
            old?.close()
        }
    }

    private fun buildRepository(status: ProxyServiceStatus): MihomoRepository {
        val client = MihomoApiClient(
            baseUrl = "http://${status.externalController}",
            secret = status.secret,
        )
        val ws = MihomoWebSocket(client)
        return MihomoRepositoryImpl(client, ws)
    }

    private companion object {
        const val TAG = "MihomoConnectionManager"
    }
}
