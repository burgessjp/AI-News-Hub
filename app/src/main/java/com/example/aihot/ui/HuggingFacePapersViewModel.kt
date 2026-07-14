package com.example.aihot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aihot.data.HuggingFacePaper
import com.example.aihot.data.HuggingFacePapersRepository
import com.example.aihot.data.ShortContentException
import com.example.aihot.data.TranslationConfigStore
import com.example.aihot.data.TranslationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * HuggingFace Trending Papers ViewModel。
 *
 * 与 [GitHubTrendingViewModel] 同构:AndroidViewModel 拿 cacheDir 注入 Repository,
 * 启用 4 小时文件缓存(进入页面命中缓存秒回,不打网络)。
 *
 * 整体翻译:论文标题 + 摘要均为英文,翻译开关开且配置就绪时,标题行出现「译」按钮
 * (复用 HackerNews 的 [InlineTranslateButton] / [TranslatedText] 组件,UI 一致)。
 * 翻译状态以 paper.id 为 key(arXiv 编号唯一,刷新后状态保留,避免重复请求)。
 *
 * 单按钮整体翻译:标题与摘要合并为一段文本,一次 API 调用拿到整体译文,
 * UI 把译文块整体显示在摘要之后(无摘要时紧跟标题)。合并而非分开两次请求,
 * 既省一次网络往返,译文也天然连贯。
 */
class HuggingFacePapersViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = HuggingFacePapersRepository(cacheDir = application.cacheDir)
    private val translationRepo = TranslationRepository(application.cacheDir)
    private val configStore = TranslationConfigStore(application)

    private val _state = MutableStateFlow<UiState<List<HuggingFacePaper>>>(UiState.Loading)
    val state: StateFlow<UiState<List<HuggingFacePaper>>> = _state.asStateFlow()

    /**
     * 上次成功拿到数据时的「数据落盘时刻」。命中缓存秒回时也是缓存写入时刻
     * —— 这正是「上次刷新时间」的语义。null 表示尚未成功过。
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

    /** 整体译文翻译状态(paperId → state),与 GitHubTrendingViewModel.descStates 同源。 */
    private val _translationStates = MutableStateFlow<Map<String, TranslationState>>(emptyMap())
    val translationStates: StateFlow<Map<String, TranslationState>> = _translationStates.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repo.fetch() }
                .onSuccess { result ->
                    _state.value =
                        if (result.papers.isEmpty()) UiState.Error("无内容") else UiState.Success(result.papers)
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
            runCatching { repo.forceRefresh() }
                .onSuccess { result ->
                    _state.value =
                        if (result.papers.isEmpty()) UiState.Error("无内容") else UiState.Success(result.papers)
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
     * 翻译论文(标题 + 摘要整体),列表页单按钮触发。
     *
     * 把标题和摘要合并为一段文本一次翻译:既省一次网络往返,译文也天然连贯,
     * UI 把整段译文块显示在摘要之后。两者都为空时不翻译。
     */
    fun translatePaper(paper: HuggingFacePaper) {
        val current = _translationStates.value[paper.id]
        if (current is TranslationState.Loading) return
        val text = buildString {
            if (paper.title.isNotBlank()) append(paper.title.trim())
            if (paper.summary.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(paper.summary.trim())
            }
        }
        if (text.isBlank()) return
        _translationStates.value = _translationStates.value + (paper.id to TranslationState.Loading)
        viewModelScope.launch {
            val outcome = doTranslate(text)
            _translationStates.value = _translationStates.value + (paper.id to outcome)
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
