package com.example.aihot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aihot.data.AiConfigStore
import com.example.aihot.data.RundownAiArticle
import com.example.aihot.data.RundownAiRepository
import com.example.aihot.data.ShortContentException
import com.example.aihot.data.TranslationRepository
import com.example.aihot.data.source.RundownAiArchiveRepository
import com.example.aihot.data.source.RundownAiSource
import com.example.aihot.data.source.SourceMode
import com.example.aihot.ui.more.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Rundown AI ViewModel。
 *
 * 与 [StormzhangAiNewsViewModel] 同构的双模式源:实时([liveRepo],jsoup 直抓首页 HTML)
 * 与归档([archiveRepo],gitcode 快照)按用户 [SourceMode] 切换。
 *
 * 整体翻译:newsletter 标题(title)与副标题(subtitle)是英文,翻译开关开且配置
 * 就绪时,标题行出现「译」按钮(复用 [InlineTranslateButton] / [TranslatedText],
 * UI 与其他源一致)。翻译状态以 article.slug 为 key(slug 唯一,刷新后状态保留,
 * 避免重复请求)。
 *
 * 单按钮整体翻译:title 与 subtitle 合并为一段文本,一次 API 调用拿到整体译文,
 * UI 把译文块整体显示在 subtitle 之后。合并而非分开两次请求,既省一次网络往返,
 * 译文也天然连贯(对齐 [ProductHuntViewModel] 的实现套路)。
 */
class RundownAiViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)

    // 两个 Repository 都持有,按当前 sourceMode 动态选用(避免切换后旧 repo 固化)。
    private val liveRepo: RundownAiRepository = RundownAiRepository(cacheDir = application.cacheDir)
    private val archiveRepo: RundownAiSource = RundownAiArchiveRepository()

    private val _sourceMode = MutableStateFlow(
        runCatching { settingsStore.currentSourceModeSync() }.getOrDefault(SourceMode.LIVE)
    )
    val sourceMode: StateFlow<SourceMode> = _sourceMode.asStateFlow()

    /** 按当前 sourceMode 取对应 Repository。 */
    private fun currentRepo(): RundownAiSource =
        if (_sourceMode.value == SourceMode.ARCHIVE) archiveRepo else liveRepo

    private val translationRepo = TranslationRepository(application)
    private val configStore = AiConfigStore(application)

    private val _state = MutableStateFlow<UiState<List<RundownAiArticle>>>(UiState.Loading)
    val state: StateFlow<UiState<List<RundownAiArticle>>> = _state.asStateFlow()

    /**
     * 上次成功拿到数据时的「数据落盘时刻」。命中缓存秒回时也是缓存写入时刻
     * —— 这正是「上次刷新时间」的语义。null 表示尚未成功过。
     */
    private val _lastRefreshAt = MutableStateFlow<Long?>(null)
    val lastRefreshAt: StateFlow<Long?> = _lastRefreshAt.asStateFlow()

    /** 手动刷新进行中(下拉刷新转圈 + 防重复)。 */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** 翻译配置流(UI 订阅以决定是否显示「译」按钮)。 */
    val configFlow = configStore.configFlow

    /** 整体译文翻译状态(slug → state),与 ProductHuntViewModel.translationStates 同源。 */
    private val _translationStates = MutableStateFlow<Map<String, TranslationState>>(emptyMap())
    val translationStates: StateFlow<Map<String, TranslationState>> = _translationStates.asStateFlow()

    init {
        // 订阅数据源设置:设置页一改即更新,后续 refresh 自动用新源(不自动重抓)。
        viewModelScope.launch {
            settingsStore.prefsFlow.map { it.sourceMode }.collect { _sourceMode.value = it }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { currentRepo().fetch() }
                .onSuccess { result ->
                    _state.value =
                        if (result.articles.isEmpty()) UiState.Error("无内容") else UiState.Success(result.articles)
                    _lastRefreshAt.value = result.fetchedAt
                }
                .onFailure { _state.value = UiState.Error(it.message ?: "未知错误") }
        }
    }

    /**
     * 强制刷新:忽略缓存真打网络(用户下拉刷新)。
     *
     * 失败处理:若当前已有数据(Success),保留旧数据不切 Error,用户至少能看旧列表;
     * 若当前无数据,则与 [refresh] 一样设 Error 态。
     * 刷新中([isRefreshing]为 true)忽略重复触发。
     */
    fun forceRefresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            runCatching { currentRepo().forceRefresh() }
                .onSuccess { result ->
                    _state.value =
                        if (result.articles.isEmpty()) UiState.Error("无内容") else UiState.Success(result.articles)
                    _lastRefreshAt.value = result.fetchedAt
                }
                .onFailure {
                    if (_state.value !is UiState.Success) {
                        _state.value = UiState.Error(it.message ?: "未知错误")
                    }
                }
            _isRefreshing.value = false
        }
    }

    /**
     * 翻译文章(title + subtitle 整体),列表页单按钮触发。
     * 合并为一段文本一次翻译;两者都为空时不翻译。
     */
    fun translateArticle(article: RundownAiArticle) {
        val key = article.slug
        if (key.isBlank()) return
        val current = _translationStates.value[key]
        if (current is TranslationState.Loading) return
        val text = buildString {
            if (article.title.isNotBlank()) append(article.title.trim())
            if (article.subtitle.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(article.subtitle.trim())
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
                        TranslationState.Error(it.message ?: "翻译失败")
                    }
                }
            )
    }
}
