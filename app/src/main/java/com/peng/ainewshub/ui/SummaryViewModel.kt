package com.peng.ainewshub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.data.SourceSummary
import com.peng.ainewshub.data.SummaryRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * AI 摘要 ViewModel —— 聚合 7 个归档源的 AI 中文摘要。
 *
 * 摘要正文由数据流水线在抓取时预生成,写入快照顶层 `ai_summary` 字段(见
 * [SummaryRepository]);App 端不再运行时调用 AI API,也不再依赖任何 AI 服务配置。
 *
 * 状态模型:7 个源各自独立的 [UiState]<[SourceSummary]>,并发拉取、互不影响
 * (一个源失败不拖累其余)。
 *
 * 触发时机:
 *  - 进入页面 init 自动 loadAll()
 *  - [refresh]:用户下拉刷新,强制重拉(归档 CDN + index.json 2 分钟 TTL,重复拉开销小)
 */
class SummaryViewModel(application: Application) : AndroidViewModel(application) {

    private val summaryRepo = SummaryRepository()

    // 7 源各自独立状态,key = source(对齐 SummaryRepository.SOURCE_KEYS)
    private val _states = MutableStateFlow<Map<String, UiState<SourceSummary>>>(emptyMap())
    val states: StateFlow<Map<String, UiState<SourceSummary>>> = _states.asStateFlow()

    /**
     * 刷新进行中(下拉刷新转圈 + 防重复)。任一源在跑即为 true。
     */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadAll()
    }

    /**
     * 并发拉取 7 个源的摘要。每源独立 try/catch,失败只置该源 Error。
     * 每源只一次网络(拉归档快照读 ai_summary 字段,见 [SummaryRepository])。
     */
    fun loadAll() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        // 先把 7 源都置 Loading(让 UI 显骨架)
        val loading = SummaryRepository.SOURCE_KEYS.associateWith { UiState.Loading as UiState<SourceSummary> }
        _states.value = loading
        viewModelScope.launch {
            // 7 源并发,各自独立失败
            SummaryRepository.SOURCE_KEYS.map { key ->
                async {
                    val result = summaryRepo.summarize(key)
                    val state: UiState<SourceSummary> = result.fold(
                        onSuccess = { UiState.Success(it) },
                        onFailure = { it.toUiError() }
                    )
                    _states.value = _states.value + (key to state)
                }
            }.awaitAll()
            _isRefreshing.value = false
        }
    }

    /** 下拉刷新:等同 loadAll。 */
    fun refresh() = loadAll()

    /** 单源重试:仅重拉失败的源。 */
    fun retry(source: String) {
        viewModelScope.launch {
            _states.value = _states.value + (source to UiState.Loading as UiState<SourceSummary>)
            val result = summaryRepo.summarize(source)
            val state: UiState<SourceSummary> = result.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { it.toUiError() }
            )
            _states.value = _states.value + (source to state)
        }
    }
}
