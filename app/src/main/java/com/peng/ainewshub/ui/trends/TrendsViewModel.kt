package com.peng.ainewshub.ui.trends

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.R
import com.peng.ainewshub.data.AppException
import com.peng.ainewshub.data.TrendsDigest
import com.peng.ainewshub.data.TrendsRepository
import com.peng.ainewshub.data.source.ArchiveHttpClient
import com.peng.ainewshub.ui.FollowNotices
import com.peng.ainewshub.ui.RefreshNotices
import com.peng.ainewshub.ui.i18n.localized
import com.peng.ainewshub.ui.more.MAX_FOLLOWED_KEYWORDS
import com.peng.ainewshub.ui.more.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 热词趋势 UI 状态。
 *
 * [NoData]:趋势尚未生成(归档 trends.json 缺失或无热词),语义是空态而非出错
 * —— write_trends 失败的批次会暂缺文件,下次批次自愈。
 * [Error]:网络/解析失败。
 */
sealed interface TrendsState {
    data object Loading : TrendsState
    data object NoData : TrendsState
    data class Error(val message: String) : TrendsState
    data class Success(val digest: TrendsDigest) : TrendsState
}

/**
 * 热词趋势 ViewModel —— 根 tab「趋势」。
 *
 * 趋势由流水线预生成(scripts/trend_keywords.py,统计为主 + 可选 AI 精修),
 * 本 VM 只读归档(对齐 OverviewViewModel 范式):
 *  - init 自动 [load](命中 trends.json 2 分钟缓存即秒回);
 *  - [refresh]:下拉刷新,绕过缓存强制重读(流水线刚推送立即可见);
 *  - 重击 tab 走 [load] 即可。
 *
 * 另承载展开区「+ 关注」缝合动作:[followedKeywords] 响应式收集关注词集合
 * (小写;按钮已关注态判定),[followKeyword] 一键写入 DataStore(关注页
 * followedKeywordsFlow 自动联动重算),结果经 [FollowNotices] 全局胶囊提示。
 *
 * 加载中保留旧内容(Success 不回落 Loading),只亮下拉刷新指示。
 */
class TrendsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = TrendsRepository()
    private val settingsStore = SettingsStore(application)

    private val _state = MutableStateFlow<TrendsState>(TrendsState.Loading)
    val state: StateFlow<TrendsState> = _state.asStateFlow()

    /** 读取进行中(下拉刷新指示 + 防重复)。 */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** 已关注词集合(全小写;写入端去重是 ignoreCase、存量大小写不定,统一小写比对)。 */
    private val _followedKeywords = MutableStateFlow<Set<String>>(emptySet())

    /** 已关注词集合(小写):展开区「+ 关注」按钮的已关注态判定。 */
    val followedKeywords: StateFlow<Set<String>> = _followedKeywords.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            settingsStore.followedKeywordsFlow.collect { list ->
                _followedKeywords.value = list.mapTo(mutableSetOf()) { it.lowercase() }
            }
        }
    }

    /** 自动加载:读归档 trends.json(命中 2 分钟缓存秒回)。 */
    fun load() = run(false)

    /** 下拉刷新:绕过 trends.json 2 分钟缓存强制重读。 */
    fun refresh() = run(true)

    /**
     * 展开区「+ 关注」:把热词(展示形态)写入关注词。
     *
     * 已关注静默(按钮本就呈已关注态);达上限经 [FollowNotices] 提示;
     * 写入成功同样提示「已关注 X」。关注页经 followedKeywordsFlow 自动联动。
     */
    fun followKeyword(display: String) {
        val keyword = display.trim()
        if (keyword.isEmpty()) return
        viewModelScope.launch {
            val current = _followedKeywords.value
            when {
                keyword.lowercase() in current -> Unit // 已关注:无变化可告知
                current.size >= MAX_FOLLOWED_KEYWORDS ->
                    FollowNotices.notify(FollowNotices.Event(keyword, FollowNotices.Outcome.Capped))
                else -> {
                    val added = settingsStore.addFollowedKeyword(keyword)
                    if (added) {
                        FollowNotices.notify(FollowNotices.Event(keyword, FollowNotices.Outcome.Added))
                    }
                    // 写入失败静默:用户再点一次即可,不打断阅读
                }
            }
        }
    }

    private fun run(force: Boolean) {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        // 已有内容时保留展示,后台重读;否则进 Loading
        if (_state.value !is TrendsState.Success) _state.value = TrendsState.Loading
        // 强刷前的批次指纹:Success 保留旧内容,天然提供「刷新前」参照
        val previousGeneratedAt = (_state.value as? TrendsState.Success)?.digest?.generatedAt
        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                repo.loadTrends(force).fold(
                    onSuccess = {
                        // 强刷成功但批次指纹未变(且非离线兜底)→ 告知「已是最新批次」,
                        // 消除「刷新了却没变化 = App 坏了」的误解(归档一天只更数批)
                        if (force && previousGeneratedAt == it.generatedAt &&
                            !ArchiveHttpClient.offlineMode.value
                        ) {
                            RefreshNotices.notifyNoNewBatch(it.generatedAt)
                        }
                        _state.value = TrendsState.Success(it)
                    },
                    onFailure = { e ->
                        Log.w("UiError", "趋势加载失败: ${e.message ?: "(no message)"}", e)
                        val localized = getApplication<Application>().localized()
                        _state.value = when (e) {
                            is AppException.NoData -> TrendsState.NoData
                            is AppException.Network -> TrendsState.Error(localized.getString(R.string.error_network))
                            is AppException.ServerError -> TrendsState.Error(localized.getString(R.string.trends_error_parse))
                            is AppException.RateLimited -> TrendsState.Error(localized.getString(R.string.error_rate_limited))
                            else -> TrendsState.Error(localized.getString(R.string.trends_error_load_failed))
                        }
                    }
                )
            } finally {
                // finally 复位:非预期异常也不能让刷新态永久卡 true。
                // 复位前保证最小转一档:命中 force 去重窗口时刷新会瞬间完成,isRefreshing
                // 同帧 true→false 会让 PullToRefreshBox 指示器卡在展示态不收起
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

    private companion object {
        /** 下拉刷新指示器最小展示时长(正常网络刷新远超此值,只兜瞬间完成的缓存命中)。 */
        const val MIN_REFRESH_SPIN_MS = 600L
    }
}
