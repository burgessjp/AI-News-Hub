package com.example.aihot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aihot.data.SummaryRepository
import com.example.aihot.data.SourceSummary
import com.example.aihot.data.TranslationConfigStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * AI 摘要 ViewModel —— 聚合 4 个归档源的 AI 中文摘要。
 *
 * 范式对齐 [HuggingFacePapersViewModel]:AndroidViewModel 拿 cacheDir 注入 Repository +
 * TranslationConfigStore;复用用户的翻译服务配置(baseUrl/apiKey/model)做摘要生成。
 *
 * 状态模型:4 个源各自独立的 [UiState]<[SourceSummary]>,并发生成、互不影响
 * (一个源失败不拖累其余)。整体状态 [allConfigReady] 由 UI 决定是否显示「去配置」引导。
 *
 * 触发时机:
 *  - 进入页面 init 自动 loadAll()(配置就绪时)
 *  - [refresh]:用户下拉刷新,强制重跑(命中缓存秒回,不浪费 token)
 *  - 配置变化:UI 侧监听 [configFlow] 决定是否显示重试按钮,本 VM 不自动监听
 *    (避免用户在设置页改一半就触发请求)
 */
class SummaryViewModel(application: Application) : AndroidViewModel(application) {

    private val summaryRepo = SummaryRepository(application.cacheDir)
    private val configStore = TranslationConfigStore(application)

    /** 翻译服务配置流(UI 订阅以决定显示摘要 vs 「去配置」引导)。 */
    val configFlow = configStore.configFlow

    /** 当前配置是否就绪 —— UI 进入页时先读一次,决定是否 loadAll。 */
    private val _configReady = MutableStateFlow(false)
    val configReady: StateFlow<Boolean> = _configReady.asStateFlow()

    // 4 源各自独立状态,key = source(对齐 SummaryRepository.SOURCE_KEYS)
    private val _states = MutableStateFlow<Map<String, UiState<SourceSummary>>>(emptyMap())
    val states: StateFlow<Map<String, UiState<SourceSummary>>> = _states.asStateFlow()

    /**
     * 刷新进行中(下拉刷新转圈 + 防重复)。任一源在跑即为 true。
     */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        // 读一次配置快照决定首屏:就绪则 loadAll,未就绪等用户点「生成」/「重试」
        viewModelScope.launch {
            val ready = configStore.configFlow.first().isReady
            _configReady.value = ready
            if (ready) loadAll()
        }
    }

    /**
     * 并发生成 4 个源的摘要。每源独立 try/catch,失败只置该源 Error。
     * 命中缓存秒回,未命中打 API(各自带 per-key 锁,见 [SummaryRepository])。
     */
    fun loadAll() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        // 先把 4 源都置 Loading(让 UI 显骨架)
        val loading = SummaryRepository.SOURCE_KEYS.associateWith { UiState.Loading as UiState<SourceSummary> }
        _states.value = loading
        viewModelScope.launch {
            val config = configStore.configFlow.first()
            if (!config.isReady) {
                // 配置被中途清掉了:整体置 Error 引导去配置
                _configReady.value = false
                SummaryRepository.SOURCE_KEYS.forEach { key ->
                    _states.value = _states.value + (key to UiState.Error(SummaryRepository.CONFIG_MISSING))
                }
                _isRefreshing.value = false
                return@launch
            }
            _configReady.value = true
            // 4 源并发,各自独立失败
            SummaryRepository.SOURCE_KEYS.map { key ->
                async {
                    val result = summaryRepo.summarize(key, config)
                    val state: UiState<SourceSummary> = result.fold(
                        onSuccess = { UiState.Success(it) },
                        onFailure = { UiState.Error(it.message ?: "生成失败") }
                    )
                    _states.value = _states.value + (key to state)
                }
            }.awaitAll()
            _isRefreshing.value = false
        }
    }

    /** 下拉刷新:等同 loadAll(命中缓存秒回,不重复打 API)。 */
    fun refresh() = loadAll()

    /** 单源重试:仅重跑失败的源(避免成功源重打 API)。 */
    fun retry(source: String) {
        viewModelScope.launch {
            val config = configStore.configFlow.first()
            if (!config.isReady) {
                _configReady.value = false
                _states.value = _states.value + (source to UiState.Error(SummaryRepository.CONFIG_MISSING))
                return@launch
            }
            _states.value = _states.value + (source to UiState.Loading as UiState<SourceSummary>)
            val result = summaryRepo.summarize(source, config)
            val state: UiState<SourceSummary> = result.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.message ?: "生成失败") }
            )
            _states.value = _states.value + (source to state)
        }
    }
}
