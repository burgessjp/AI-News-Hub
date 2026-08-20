package com.peng.ainewshub.ui.follows

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.data.FollowCorpus
import com.peng.ainewshub.data.FollowFeedItem
import com.peng.ainewshub.data.FollowMatcher
import com.peng.ainewshub.data.FollowsRepository
import com.peng.ainewshub.data.TrendsRepository
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.i18n.localized
import com.peng.ainewshub.ui.more.SettingsStore
import com.peng.ainewshub.ui.toUiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「我的关注」页 UI 模型 —— 过滤结果 + 关键词/推荐词等页面态的快照。
 *
 * [items] 为按当前关键词(与单选过滤词)算好的命中条目;关键词增删由
 * [FollowsViewModel] 内部重算,不需要 UI 自己再过滤。
 */
data class FollowsUi(
    val keywords: List<String>,
    val selectedKeyword: String?,
    val items: List<FollowFeedItem>,
    val missingSources: List<String>,
    val dataFetchedAt: Long,
    val suggestions: List<String>
)

/**
 * 「我的关注」ViewModel —— 关键词订阅 + 当日语料的命中过滤。
 *
 * 数据分三层,彼此解耦:
 *  - **语料**([FollowsRepository.loadCorpus]):当日总览 Top10 + 8 源结构化摘要,
 *    全是归档缓存数据;init 拉一次,下拉刷新 force 重拉,单源失败页脚标注;
 *  - **关键词**(SettingsStore.followedKeywordsFlow):DataStore 响应式收集,
 *    增删词只触发 [recompute] 重算过滤、不发网络请求;
 *  - **推荐词**([TrendsRepository.loadTrends]):趋势热词 Top N 做一键添加候选,
 *    尽力而为,失败静默(推荐区直接隐藏)。
 *
 * 刷新语义同摘要 Tab:已有内容时刷新不回骨架,失败保留旧内容;指示器最短转
 * [MIN_REFRESH_SPIN_MS] 一档(防 PullToRefreshBox 卡展示态)。
 */
class FollowsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val followsRepo = FollowsRepository()
    private val trendsRepo = TrendsRepository()

    private val _state = MutableStateFlow<UiState<FollowsUi>>(UiState.Loading)
    val state: StateFlow<UiState<FollowsUi>> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // 重新过滤的输入缓存:corpus 网络成功后才有值;keywords 来自 DataStore 流
    private var corpus: FollowCorpus? = null
    private var keywords: List<String> = emptyList()
    private var selectedKeyword: String? = null
    private var suggestions: List<String> = emptyList()

    init {
        load()
        // 关键词增删 → 只重算过滤,不重拉语料(DataStore 流先发一次当前值)
        viewModelScope.launch {
            settingsStore.followedKeywordsFlow.collect { list ->
                keywords = list
                // 正被单选过滤的词被删除时,回落到「全部」
                if (selectedKeyword != null && selectedKeyword !in list) selectedKeyword = null
                recompute()
            }
        }
        loadSuggestions()
    }

    /** 首次加载(Success 后幂等,重进页不重复触发)。 */
    fun load() {
        if (_state.value is UiState.Success) return
        loadInternal(force = false, keepContent = false)
    }

    /** 错误态重试(重置骨架重新拉取)。 */
    fun retry() = loadInternal(force = false, keepContent = false)

    /** 下拉刷新:绕过 index 缓存强制重拉,保留现有内容(只亮下拉指示器)。 */
    fun refresh() = loadInternal(force = true, keepContent = true)

    private fun loadInternal(force: Boolean, keepContent: Boolean) {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        if (!keepContent) _state.value = UiState.Loading
        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val sourceOrder = settingsStore.sourceOrderFlow.first()
                followsRepo.loadCorpus(force, sourceOrder)
                    .onSuccess {
                        corpus = it
                        recompute()
                    }
                    .onFailure {
                        // 刷新失败但已有内容:保留旧内容继续展示;仅首次(无语料)走错误态
                        if (corpus == null) {
                            _state.value = it.toUiError(getApplication<Application>().localized())
                        }
                    }
            } finally {
                // 复位前保证最小转一档(缓存命中时刷新瞬间完成,指示器会卡展示态)
                val remaining = MIN_REFRESH_SPIN_MS - (SystemClock.elapsedRealtime() - startedAt)
                if (remaining > 0) {
                    try {
                        delay(remaining)
                    } catch (_: CancellationException) {
                        // VM 已销毁,复位无意义
                    }
                }
                _isRefreshing.value = false
            }
        }
    }

    /** 顶栏关键词 chips 的单选过滤:再点一次同一词恢复「全部」。 */
    fun selectKeyword(keyword: String) {
        selectedKeyword = if (selectedKeyword == keyword) null else keyword
        recompute()
    }

    /** 添加关注词(写入 DataStore,流自动触发重算;上限由 SettingsStore 兜底)。 */
    fun addKeyword(keyword: String) {
        viewModelScope.launch { settingsStore.addFollowedKeyword(keyword) }
    }

    /** 删除关注词(写入 DataStore,流自动触发重算)。 */
    fun removeKeyword(keyword: String) {
        viewModelScope.launch { settingsStore.removeFollowedKeyword(keyword) }
    }

    /** 推荐词:趋势热词 Top N(展示形态),失败静默 —— 推荐区可整体隐藏。 */
    private fun loadSuggestions() {
        viewModelScope.launch {
            trendsRepo.loadTrends()
                .onSuccess { digest ->
                    suggestions = digest.keywords
                        .take(SUGGESTION_COUNT)
                        .map { it.display.trim() }
                        .filter { it.isNotEmpty() }
                    recompute()
                }
        }
    }

    /** 以缓存语料 + 当前关键词重算过滤结果(语料未到位时不动作,保持 Loading)。 */
    private fun recompute() {
        val c = corpus ?: return
        _state.value = UiState.Success(
            FollowsUi(
                keywords = keywords,
                selectedKeyword = selectedKeyword,
                items = FollowMatcher.filter(c.entries, keywords, selectedKeyword),
                missingSources = c.missingSources,
                dataFetchedAt = c.dataFetchedAt,
                // 已关注的推荐词不再重复出现在候选区(忽略大小写)
                suggestions = suggestions.filterNot { s ->
                    keywords.any { it.equals(s, ignoreCase = true) }
                }
            )
        )
    }

    private companion object {
        /** 下拉刷新指示器最小展示时长(兜缓存命中的瞬间完成,同 SummaryViewModel)。 */
        const val MIN_REFRESH_SPIN_MS = 600L

        /** 推荐词数量(趋势热词取前 N 个展示形态)。 */
        const val SUGGESTION_COUNT = 10
    }
}
