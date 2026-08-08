package com.peng.ainewshub.ui
import com.peng.ainewshub.ui.i18n.localized
import com.peng.ainewshub.R

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.data.AiConfigStore
import com.peng.ainewshub.data.OpenAiAnthropicNews
import com.peng.ainewshub.data.ShortContentException
import com.peng.ainewshub.data.TranslationRepository
import com.peng.ainewshub.data.source.OpenAiAnthropicNewsArchiveRepository
import com.peng.ainewshub.data.source.OpenAiAnthropicNewsSource
import com.peng.ainewshub.data.source.SourceMode
import com.peng.ainewshub.ui.more.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * OpenAI x Anthropic 厂商动态 ViewModel。
 *
 * 与 [ProductHuntViewModel] 同构,**不接实时源**:两家均无稳定公开 API(Anthropic
 * 无官方 RSS),App 端不直连。故 [SourceMode] LIVE 与 ARCHIVE 都走归档([archiveRepo])——
 * 与 Product Hunt 同理,本源是「只归档」。[sourceMode] 仍订阅设置,仅为顶栏角标一致。
 *
 * 整体翻译:标题多为英文,翻译开关开且配置就绪时,标题行出现「译」按钮。翻译状态以
 * 文章 url 为 key(url 唯一,刷新后状态保留,避免重复请求)。
 *
 * 单按钮整体翻译:title + summary 合并为一段文本,一次 API 调用拿到整体译文,
 * 合并而非分开两次请求,既省一次网络往返,译文也天然连贯。
 */
class OpenAiAnthropicNewsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)

    // 只归档:无 liveRepo,两种 SourceMode 都用归档(两家无稳定公开 API)。
    private val archiveRepo: OpenAiAnthropicNewsSource = OpenAiAnthropicNewsArchiveRepository()

    // 占位初值:init 协程首帧即纠正为真实设置(替代原构造期 runBlocking 同步读)
    private val _sourceMode = MutableStateFlow(SourceMode.LIVE)
    val sourceMode: StateFlow<SourceMode> = _sourceMode.asStateFlow()

    private val translationRepo = TranslationRepository(application)
    private val configStore = AiConfigStore(application)

    private val _state = MutableStateFlow<UiState<List<OpenAiAnthropicNews>>>(UiState.Loading)
    val state: StateFlow<UiState<List<OpenAiAnthropicNews>>> = _state.asStateFlow()

    /**
     * 上次成功拿到数据时的「数据落盘时刻」。归档快照的 fetched_at_ms。
     */
    private val _lastRefreshAt = MutableStateFlow<Long?>(null)
    val lastRefreshAt: StateFlow<Long?> = _lastRefreshAt.asStateFlow()

    /**
     * 手动刷新进行中(下拉刷新转圈 + 防重复)。
     */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** 翻译配置流(UI 订阅以决定是否显示「译」按钮)。 */
    val configFlow = configStore.configFlow

    /** 整体译文翻译状态(url → state)。 */
    private val _translationStates = MutableStateFlow<Map<String, TranslationState>>(emptyMap())
    val translationStates: StateFlow<Map<String, TranslationState>> = _translationStates.asStateFlow()

    init {
        viewModelScope.launch {
            // 订阅数据源设置:仅用于顶栏角标一致(本源实际都走归档);
            // 首帧取真实值后再首发加载(替代原构造期 runBlocking 同步读)。
            _sourceMode.value = settingsStore.currentSourceMode()
            refresh()
            settingsStore.prefsFlow.map { it.sourceMode }.collect { _sourceMode.value = it }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { archiveRepo.fetch() }
                .onSuccess { result ->
                    _state.value =
                        if (result.articles.isEmpty()) UiState.Error(getApplication<Application>().localized().getString(R.string.common_empty_today), ErrorKind.NoData) else UiState.Success(result.articles)
                    _lastRefreshAt.value = result.fetchedAt
                }
                .onFailure { _state.value = it.toUiError(getApplication<Application>().localized()) }
        }
    }

    /**
     * 强制刷新:忽略缓存真打网络(用户下拉刷新)。归档源 fetch==forceRefresh,
     * 这里仍单独设 isRefreshing 状态供下拉转圈。
     */
    fun forceRefresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            runCatching { archiveRepo.forceRefresh() }
                .onSuccess { result ->
                    _state.value =
                        if (result.articles.isEmpty()) UiState.Error(getApplication<Application>().localized().getString(R.string.common_empty_today), ErrorKind.NoData) else UiState.Success(result.articles)
                    _lastRefreshAt.value = result.fetchedAt
                }
                .onFailure {
                    if (_state.value !is UiState.Success) {
                        _state.value = it.toUiError(getApplication<Application>().localized())
                    }
                }
            _isRefreshing.value = false
        }
    }

    /**
     * 翻译文章(title + summary 整体),列表页单按钮触发。
     * 合并为一段文本一次翻译;两者都为空时不翻译。
     */
    fun translateArticle(article: OpenAiAnthropicNews) {
        val key = article.url
        val current = _translationStates.value[key]
        if (current is TranslationState.Loading) return
        val text = buildString {
            if (article.title.isNotBlank()) append(article.title.trim())
            if (article.summary.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(article.summary.trim())
            }
        }
        if (text.isBlank()) return
        _translationStates.value = _translationStates.value + (key to TranslationState.Loading)
        viewModelScope.launch {
            val outcome = doTranslate(text)
            _translationStates.value = _translationStates.value + (key to outcome)
        }
    }

    private suspend fun doTranslate(text: String): TranslationState {
        val config = configStore.configFlow.first()
        if (!config.isReady) return TranslationState.Error(TranslationState.CONFIG_MISSING)
        return runCatching { translationRepo.translate(text, config).getOrThrow() }
            .fold(
                onSuccess = { TranslationState.Success(it) },
                onFailure = {
                    if (it is ShortContentException) {
                        TranslationState.Error(TranslationState.TOO_SHORT)
                    } else {
                        TranslationState.Error(it.toUiError(getApplication<Application>().localized()).message)
                    }
                }
            )
    }
}
