package top.yukonga.mishka.data.repository

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import top.yukonga.mishka.domain.model.ConfigurationOverride
import top.yukonga.mishka.platform.ProfileFileManager

/**
 * 用户 override 设置的持久化存储，路径 `files/mihomo/override.user.json`。
 *
 * `encodeDefaults = false` + `explicitNulls = false` 保证 JSON 只包含显式设置的字段，
 * 未提及的字段 mihomo 端 `json.Decode` 不动 RawConfig 原值。
 *
 * **内存 [state] 是权威值**：本类是进程级单例（Koin `dataModule`），Service / ProfileWorker
 * 一并注入同一实例，故 [load] 直接返回内存值而非重新读盘，写盘只服务于下次冷启动。
 */
class OverrideJsonStore(
    private val fileManager: ProfileFileManager,
    private val scope: CoroutineScope,
) {

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val _state = MutableStateFlow(readFromDisk())
    val state: StateFlow<ConfigurationOverride> = _state.asStateFlow()

    private val writeLock = Mutex()

    fun load(): ConfigurationOverride = _state.value

    fun save(override: ConfigurationOverride) {
        _state.value = override
        persist(override)
    }

    /** 变换 → 更新 [state] → 异步落盘。调用方全在主线程的 onClick 上。 */
    fun update(transform: (ConfigurationOverride) -> ConfigurationOverride) {
        persist(_state.updateAndGet(transform))
    }

    /**
     * 串行落盘。排队期间若已有更新的快照，本次直接放弃——否则旧快照会覆盖新值，
     * 这也是并发 `update{}` 丢更新的来源。
     */
    private fun persist(override: ConfigurationOverride) {
        scope.launch(Dispatchers.IO) {
            writeLock.withLock {
                if (_state.value !== override) return@withLock
                runCatching { fileManager.writeMihomoFile(FILE_NAME, json.encodeToString(override)) }
                    .onFailure { Log.e(TAG, "failed to persist $FILE_NAME", it) }
            }
        }
    }

    private fun readFromDisk(): ConfigurationOverride {
        val text = fileManager.readMihomoFile(FILE_NAME) ?: return ConfigurationOverride()
        if (text.isBlank()) return ConfigurationOverride()
        return runCatching { json.decodeFromString<ConfigurationOverride>(text) }
            .getOrElse { e ->
                // 直接退回默认值会让下一次写盘把空配置持久化，用户 override 就此永久消失。
                // 原文挪到 .bak 保住，本次以默认值继续——拒绝写入只会让设置页静默失灵
                Log.e(TAG, "failed to parse $FILE_NAME, backing up to $FILE_NAME.bak", e)
                fileManager.backupMihomoFile(FILE_NAME)
                ConfigurationOverride()
            }
    }

    companion object {
        const val FILE_NAME = "override.user.json"
        private const val TAG = "OverrideJsonStore"
    }
}
