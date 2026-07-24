package com.peng.ainewshub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.data.HackerNewsRepository
import com.peng.ainewshub.data.HackerNewsStory
import com.peng.ainewshub.data.ShortContentException
import com.peng.ainewshub.data.AiConfigStore
import com.peng.ainewshub.data.TranslationRepository
import com.peng.ainewshub.data.source.HackerNewsArchiveRepository
import com.peng.ainewshub.data.source.HackerNewsSource
import com.peng.ainewshub.data.source.SourceMode
import com.peng.ainewshub.ui.more.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * HackerNews ViewModel。
 *
 * 与 [HotTopicsViewModel] 同构:精选 tab 顶部的装饰性模块,失败/为空时静默隐藏,
 * 不阻塞下方主列表的加载与展示。
 *
 * 继承 [AndroidViewModel] 以拿到 application.cacheDir 注入 [HackerNewsRepository],
 * 启用 4 小时文件缓存:进入页面命中缓存秒回,不打网络。
 *
 * 数据源模式([SourceMode]):订阅 [SettingsStore].prefsFlow,实时跟随设置变化
 * (设置页切换数据源后,本页下拉刷新立即用新模式,无需重进页面)。LIVE →
 * [liveRepo](实时,带缓存);ARCHIVE → [archiveRepo](gitcode 归档,无缓存)。
 * [currentRepo] 按当前 sourceMode 取,refresh/forceRefresh 调它。
 */
class HackerNewsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)

    // 两个 Repository 都持有,按当前 sourceMode 动态选用(避免切换后旧 repo 固化)。
    private val liveRepo: HackerNewsRepository = HackerNewsRepository(cacheDir = application.cacheDir)
    private val archiveRepo: HackerNewsSource = HackerNewsArchiveRepository()

    private val _sourceMode = MutableStateFlow(
        runCatching { settingsStore.currentSourceModeSync() }.getOrDefault(SourceMode.LIVE)
    )
    val sourceMode: StateFlow<SourceMode> = _sourceMode.asStateFlow()

    /** 按当前 sourceMode 取对应 Repository。 */
    private fun currentRepo(): HackerNewsSource =
        if (_sourceMode.value == SourceMode.ARCHIVE) archiveRepo else liveRepo

    private val translationRepo = TranslationRepository(application)
    private val configStore = AiConfigStore(application)

    private val _state = MutableStateFlow<UiState<List<HackerNewsStory>>>(UiState.Loading)
    val state: StateFlow<UiState<List<HackerNewsStory>>> = _state.asStateFlow()

    /**
     * 上次成功拿到数据时的「数据落盘时刻」(缓存写入或刚抓取)。
     *
     * 命中缓存秒回时也会更新为缓存写入时刻 —— 这正是「上次刷新时间」的语义:
     * 让用户知道这份数据有多旧,而非 ViewModel 何刻拿到。null 表示尚未成功过。
     */
    private val _lastRefreshAt = MutableStateFlow<Long?>(null)
    val lastRefreshAt: StateFlow<Long?> = _lastRefreshAt.asStateFlow()

    /**
     * 手动刷新进行中(顶栏刷新按钮转圈 + 防重复点击)。
     *
     * 仅 [forceRefresh] 置 true;[refresh](走缓存)不触发,避免进入页面就转圈。
     */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** 翻译配置流(UI 订阅以决定是否显示「译」按钮)。 */
    val configFlow = configStore.configFlow

    /** story 标题翻译状态(storyId → state)。与 HackerNewsCommentsViewModel 同源。 */
    private val _titleStates = MutableStateFlow<Map<Long, TranslationState>>(emptyMap())
    val titleStates: StateFlow<Map<Long, TranslationState>> = _titleStates.asStateFlow()

    init {
        // 订阅数据源设置:设置页一改即更新,后续 refresh 自动用新源(不自动重抓)。
        viewModelScope.launch {
            settingsStore.prefsFlow.map { it.sourceMode }.collect { _sourceMode.value = it }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { currentRepo().fetch(limit = 20) }
                .onSuccess { result ->
                    // 空结果视为「无内容」而非错误:模块整体隐藏。
                    _state.value =
                        if (result.stories.isEmpty()) UiState.Error("今日暂无内容", ErrorKind.NoData) else UiState.Success(result.stories)
                    // 记录数据落盘时刻(缓存命中也会更新),供顶栏显示「上次刷新」。
                    _lastRefreshAt.value = result.fetchedAt
                }
                .onFailure { _state.value = it.toUiError() }
        }
    }

    /**
     * 强制刷新:忽略缓存真打网络(用户点顶栏刷新按钮)。
     *
     * 与 [refresh] 的区别:[refresh] 命中缓存秒回不打网络,适合进入页面;
     * 本方法一定走网络,拉取成功后刷新缓存使后续命中。
     *
     * 失败处理:若当前已有数据(Success),保留旧数据不切 Error,用户至少能看旧列表;
     * 若当前无数据,则与 [refresh] 一样设 Error 态。刷新中([isRefreshing]为 true)忽略重复点击。
     */
    fun forceRefresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            runCatching { currentRepo().forceRefresh(limit = 20) }
                .onSuccess { result ->
                    _state.value =
                        if (result.stories.isEmpty()) UiState.Error("今日暂无内容", ErrorKind.NoData) else UiState.Success(result.stories)
                    _lastRefreshAt.value = result.fetchedAt
                }
                .onFailure {
                    // 有旧数据就保留(保可用),无数据才显示错误。
                    if (_state.value !is UiState.Success) {
                        _state.value = it.toUiError()
                    }
                }
            _isRefreshing.value = false
        }
    }

    /** 翻译 story 标题(列表页)。 */
    fun translateTitle(story: HackerNewsStory) {
        val current = _titleStates.value[story.id]
        if (current is TranslationState.Loading) return
        if (story.title.isBlank()) return
        _titleStates.value = _titleStates.value + (story.id to TranslationState.Loading)
        viewModelScope.launch {
            val outcome = doTranslate(story.title)
            _titleStates.value = _titleStates.value + (story.id to outcome)
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
                        TranslationState.Error(it.toUiError().message)
                    }
                }
            )
    }
}
