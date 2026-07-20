package com.example.aihot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aihot.data.GitHubTrendingRepository
import com.example.aihot.data.ShortContentException
import com.example.aihot.data.TrendingRepo
import com.example.aihot.data.AiConfigStore
import com.example.aihot.data.TranslationRepository
import com.example.aihot.data.source.GitHubTrendingArchiveRepository
import com.example.aihot.data.source.GitHubTrendingSource
import com.example.aihot.data.source.SourceMode
import com.example.aihot.ui.more.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * GitHub Trending ViewModel。
 *
 * 与 [HackerNewsViewModel] 同构:AndroidViewModel 拿 cacheDir 注入 Repository,
 * 启用 4 小时文件缓存(进入页面命中缓存秒回,不打网络)。
 *
 * 描述翻译:仓库描述多为英文,翻译开关开且配置就绪时,描述行出现「译」按钮
 * (复用 HackerNews 的 [InlineTranslateButton] / [TranslatedText] 组件,UI 一致)。
 * 翻译状态以 repo.url 为 key(同 URL 即同仓库,刷新后状态保留,避免重复请求)。
 *
 * 数据源模式([SourceMode]):订阅 [SettingsStore].prefsFlow,实时跟随设置变化
 * (在设置页切换数据源后,本页下拉刷新立即用新模式,无需重进页面或重建 ViewModel)。
 * LIVE → [liveRepo](实时,带 4 小时缓存);ARCHIVE → [archiveRepo](gitcode 归档,无缓存)。
 * [currentRepo] 按当前 sourceMode 取,refresh/forceRefresh 调它。
 */
class GitHubTrendingViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)

    // 两个 Repository 都持有,按当前 sourceMode 动态选用(避免切换后旧 repo 固化)。
    // archiveRepo 无缓存/无状态,常驻无开销;liveRepo 带 4 小时文件缓存,构造即就绪。
    private val liveRepo: GitHubTrendingRepository = GitHubTrendingRepository(cacheDir = application.cacheDir)
    private val archiveRepo: GitHubTrendingSource = GitHubTrendingArchiveRepository()

    /**
     * 当前数据源模式 —— 订阅 prefsFlow,设置一变即更新。
     * 初始值 [SourceMode.LIVE](prefsFlow 首帧到达前用默认值,避免阻塞构造);
     * [init] 里同步读一次持久化值,让首次 [refresh] 用对模式。
     */
    private val _sourceMode = MutableStateFlow(
        runCatching { settingsStore.currentSourceModeSync() }.getOrDefault(SourceMode.LIVE)
    )
    val sourceMode: StateFlow<SourceMode> = _sourceMode.asStateFlow()

    /** 按当前 sourceMode 取对应 Repository。 */
    private fun currentRepo(): GitHubTrendingSource =
        if (_sourceMode.value == SourceMode.ARCHIVE) archiveRepo else liveRepo

    private val translationRepo = TranslationRepository(application)
    private val configStore = AiConfigStore(application)

    private val _state = MutableStateFlow<UiState<List<TrendingRepo>>>(UiState.Loading)
    val state: StateFlow<UiState<List<TrendingRepo>>> = _state.asStateFlow()

    /**
     * 上次成功拿到数据时的「数据落盘时刻」(缓存写入或刚抓取)。
     * 命中缓存秒回时也会更新为缓存写入时刻 —— 这正是「上次刷新时间」的语义。
     * null 表示尚未成功过。
     */
    private val _lastRefreshAt = MutableStateFlow<Long?>(null)
    val lastRefreshAt: StateFlow<Long?> = _lastRefreshAt.asStateFlow()

    /**
     * 手动刷新进行中(下拉刷新转圈 + 防重复)。
     * 仅 [forceRefresh] 置 true;[refresh](走缓存)不触发,避免进入页面就转圈。
     */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** 翻译配置流(UI 订阅以决定是否显示「译」按钮)。 */
    val configFlow = configStore.configFlow

    /** 描述翻译状态(repoUrl → state),与 HackerNewsViewModel.titleStates 同源。 */
    private val _descStates = MutableStateFlow<Map<String, TranslationState>>(emptyMap())
    val descStates: StateFlow<Map<String, TranslationState>> = _descStates.asStateFlow()

    init {
        // 订阅数据源设置:设置页一改,_sourceMode 即更新,后续 refresh 自动用新源。
        // 不在这里触发 refresh —— 避免设置变化导致自动重抓(用户需主动下拉刷新才切数据)。
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
                        if (result.repos.isEmpty()) UiState.Error("今日暂无内容", ErrorKind.NoData) else UiState.Success(result.repos)
                    _lastRefreshAt.value = result.fetchedAt
                }
                .onFailure { _state.value = it.toUiError() }
        }
    }

    /**
     * 强制刷新:忽略缓存真打网络(用户下拉刷新)。
     *
     * 失败处理:若当前已有数据(Success),保留旧数据不切 Error,用户至少能看旧列表;
     * 若当前无数据,则与 [refresh] 一样设 Error 态。
     * 刷新中([isRefreshing]为 true)忽略重复触发。
     *
     * 用当前 sourceMode([currentRepo]):设置页切换数据源后,下拉刷新立即用新源。
     */
    fun forceRefresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            runCatching { currentRepo().forceRefresh() }
                .onSuccess { result ->
                    _state.value =
                        if (result.repos.isEmpty()) UiState.Error("今日暂无内容", ErrorKind.NoData) else UiState.Success(result.repos)
                    _lastRefreshAt.value = result.fetchedAt
                }
                .onFailure {
                    if (_state.value !is UiState.Success) {
                        _state.value = it.toUiError()
                    }
                }
            _isRefreshing.value = false
        }
    }

    /** 翻译仓库描述(列表页)。 */
    fun translateDesc(repo: TrendingRepo) {
        val current = _descStates.value[repo.url]
        if (current is TranslationState.Loading) return
        if (repo.description.isBlank()) return
        _descStates.value = _descStates.value + (repo.url to TranslationState.Loading)
        viewModelScope.launch {
            val outcome = doTranslate(repo.description)
            _descStates.value = _descStates.value + (repo.url to outcome)
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
