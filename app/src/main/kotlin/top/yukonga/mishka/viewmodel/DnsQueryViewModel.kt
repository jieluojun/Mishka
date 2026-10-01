package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.yukonga.mishka.domain.model.DnsAnswer
import top.yukonga.mishka.domain.repository.MihomoRepository
import top.yukonga.mishka.util.describe

@Immutable
data class DnsQueryUiState(
    val queryName: String = "",
    val queryType: String = "A",
    val answers: ImmutableList<DnsAnswer> = persistentListOf(),
    val status: Int? = null,
    val isQuerying: Boolean = false,
    val error: String = "",
)

class DnsQueryViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DnsQueryUiState())
    val uiState: StateFlow<DnsQueryUiState> = _uiState.asStateFlow()

    private var repository: MihomoRepository? = null
    private var queryJob: Job? = null

    /**
     * mihomo 重启 / 切订阅时 manager 会 close 旧 client 并 emit 新 repo。in-flight 请求会因
     * client 关闭而抛异常，但协程本身不会被取消——不 cancel 就会拿旧连接的结果（或它的失败
     * 文案）覆盖新连接的 UI，并把 isQuerying 卡在 true。
     */
    fun setRepository(repo: MihomoRepository?) {
        if (repository === repo) return
        queryJob?.cancel()
        queryJob = null
        repository = repo
        _uiState.value = _uiState.value.copy(isQuerying = false)
    }

    fun setQueryName(name: String) {
        _uiState.value = _uiState.value.copy(queryName = name)
    }

    fun setQueryType(type: String) {
        _uiState.value = _uiState.value.copy(queryType = type)
    }

    fun queryDns() {
        val repo = repository ?: return
        val state = _uiState.value
        if (state.queryName.isBlank() || state.isQuerying) return

        _uiState.value = state.copy(isQuerying = true, error = "", answers = persistentListOf(), status = null)

        queryJob = viewModelScope.launch {
            val result = repo.queryDns(state.queryName, state.queryType)
            // 协程被 cancel 后 HTTP 响应仍可能已读完，二次校验 repo identity 再写 UI
            if (repository !== repo) return@launch
            result
                .onSuccess { response ->
                    _uiState.value = _uiState.value.copy(
                        answers = response.Answer.toPersistentList(),
                        status = response.Status,
                        isQuerying = false,
                    )
                }
                .onFailure {
                    // 只存原因，前缀文案由屏幕本地化；describe 兜住 Ktor 无参异常的 null message
                    _uiState.value = _uiState.value.copy(
                        isQuerying = false,
                        error = it.describe(),
                    )
                }
        }
    }

    fun flushDnsCache() {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.flushDnsCache()
        }
    }

    fun flushFakeIp() {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.flushFakeIp()
        }
    }
}
