package com.peng.ainewshub.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.R
import com.peng.ainewshub.data.prefs.AiConfigStore
import com.peng.ainewshub.data.repo.ShortContentException
import com.peng.ainewshub.data.model.SourceListResult
import com.peng.ainewshub.data.repo.TranslationRepository
import com.peng.ainewshub.data.source.ArchiveHttpClient
import com.peng.ainewshub.ui.i18n.localized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 7 个源列表 ViewModel 的公共基类 —— 收敛此前逐字复制的样板:
 *  - state / lastRefreshAt / isRefreshing 三个 StateFlow
 *  - refresh()(走缓存,进入页面用)与 forceRefresh()(忽略缓存,下拉刷新用)的标准流程
 *  - 空结果 → NoData、失败 → toUiError、forceRefresh 失败保旧数据的统一处理
 *
 * 子类只需实现:
 *  - [doFetch] / [doForceRefresh]:调各自归档 Repository 的 fetch/forceRefresh,
 *    返回 [SourceListResult]
 *  - 可选重写 [onRefreshSuccess]:在刷新成功后更新源专属的额外状态(如 stormzhang 的 pageDate)
 *
 * 翻译逻辑不在此基类:接翻译的子类组合 [TranslateSupport] 委托(6 个源),
 * 不接翻译的子类(stormzhang)直接继承本类即可。
 *
 * @param T 列表元素类型(如 TrendingRepo / HackerNewsStory)
 */
abstract class SourceListViewModel<T>(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<UiState<List<T>>>(UiState.Loading)
    val state: StateFlow<UiState<List<T>>> = _state.asStateFlow()

    /**
     * 上次成功拿到数据时的「数据落盘时刻」。命中缓存秒回时也是缓存写入时刻
     * —— 这正是「上次刷新时间」的语义。null 表示尚未成功过。
     */
    private val _lastRefreshAt = MutableStateFlow<Long?>(null)
    val lastRefreshAt: StateFlow<Long?> = _lastRefreshAt.asStateFlow()

    /** 手动刷新进行中(下拉刷新转圈 + 防重复)。仅 [forceRefresh] 置 true。 */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** 子类实现:调 Repository 的 fetch(走缓存)。 */
    protected abstract suspend fun doFetch(): SourceListResult<T>

    /** 子类实现:调 Repository 的 forceRefresh(忽略缓存)。 */
    protected abstract suspend fun doForceRefresh(): SourceListResult<T>

    /**
     * 刷新成功后的钩子,供子类更新源专属状态(如 stormzhang 的 pageDate)。
     * 默认空实现。在 state/lastRefreshAt 更新后调用。
     */
    protected open fun onRefreshSuccess(result: SourceListResult<T>) {}

    init {
        viewModelScope.launch { refresh() }
    }

    /** 进入页面加载(走缓存,命中则秒回不打网络)。 */
    fun refresh() {
        viewModelScope.launch {
            runCatching { doFetch() }
                .onSuccess { handleSuccess(it) }
                .onFailure { _state.value = it.toUiError(getApplication<Application>().localized()) }
        }
    }

    /**
     * 强制刷新(下拉刷新):忽略缓存真打网络。
     *
     * 失败处理:若当前已有数据(Success),保留旧数据不切 Error,用户至少能看旧列表;
     * 若当前无数据,则与 [refresh] 一样设 Error 态。刷新中忽略重复触发。
     */
    fun forceRefresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                // 强刷前的批次指纹(上次成功数据的落盘时刻)
                val previousFetchedAt = _lastRefreshAt.value
                runCatching { doForceRefresh() }
                    .onSuccess {
                        // 强刷成功但数据落盘时刻未变(且非离线兜底)→ 告知「已是最新批次」,
                        // 消除「刷新了却没变化 = App 坏了」的误解(归档一天只更数批)
                        if (previousFetchedAt != null && previousFetchedAt == it.fetchedAt &&
                            !ArchiveHttpClient.offlineMode.value
                        ) {
                            RefreshNotices.notifyNoNewBatch(it.fetchedAt)
                        }
                        handleSuccess(it)
                    }
                    .onFailure {
                        // 有旧数据就保留(保可用),无数据才显示错误。
                        if (_state.value !is UiState.Success) {
                            _state.value = it.toUiError(getApplication<Application>().localized())
                        }
                    }
            } finally {
                // 复位前保证最小转一档:归档模式下 forceRefresh 命中快照缓存会瞬间完成,
                // isRefreshing 同帧 true→false 会让 PullToRefreshBox 指示器卡在展示态不收起
                // (与 Overview/Trends/Summary 三个 VM 的 MIN_REFRESH_SPIN_MS 同款兜底)。
                ensureMinRefreshSpin(startedAt)
                _isRefreshing.value = false
            }
        }
    }

    /** 统一的刷新成功处理:空判 → NoData,更新 state + lastRefreshAt,再调子类钩子。 */
    private fun handleSuccess(result: SourceListResult<T>) {
        _state.value =
            if (result.items.isEmpty()) {
                UiState.Error(
                    getApplication<Application>().localized().getString(R.string.common_empty_today),
                    ErrorKind.NoData
                )
            } else {
                UiState.Success(result.items)
            }
        _lastRefreshAt.value = result.fetchedAt
        onRefreshSuccess(result)
    }
}

/**
 * 翻译支持委托 —— 收敛 5 个接翻译的源列表 VM 此前逐字复制的翻译逻辑。
 *
 * 提供统一的 [translationStates](key → [TranslationState])管理、[configFlow] 暴露、
 * 以及 [translate](入参为 key + 待译文本)和内部 [doTranslate]。
 *
 * 子类 VM 持有一个本委托实例,把 translateXxx 方法的「Loading 守卫 + 文本拼接 + 状态更新」
 * 简化为调 [translate](key, text);具体的文本如何拼接(如 title+summary 合并)由子类决定。
 */
class TranslateSupport(
    application: Application
) {
    private val appContext = application.applicationContext
    private val translationRepo = TranslationRepository.get(application)
    private val configStore = AiConfigStore(application)

    /** 翻译配置流(UI 订阅以决定是否显示「译」按钮)。 */
    val configFlow = configStore.configFlow

    /** 翻译状态(key → state)。 */
    private val _states = MutableStateFlow<Map<String, TranslationState>>(emptyMap())
    val states: StateFlow<Map<String, TranslationState>> = _states.asStateFlow()

    /**
     * 翻译一段文本(以 [key] 标识)。供各 VM 的 translateXxx 调用:
     *  - 已在翻译中(Loading)或文本为空 → 跳过
     *  - 否则置 Loading,异步翻译后更新为 Success/Error
     *
     * 在 [vmScope] 内启动协程。[key] 用于翻译状态 Map 的存取(如 repo.url / story.id)。
     */
    fun translate(vmScope: kotlinx.coroutines.CoroutineScope, key: String, text: String) {
        val current = _states.value[key]
        if (current is TranslationState.Loading) return
        if (text.isBlank()) return
        _states.value = _states.value + (key to TranslationState.Loading)
        vmScope.launch {
            val outcome = doTranslate(vmScope, text)
            _states.value = _states.value + (key to outcome)
        }
    }

    private suspend fun doTranslate(
        @Suppress("UNUSED_PARAMETER") vmScope: kotlinx.coroutines.CoroutineScope,
        text: String
    ): TranslationState {
        val config = configStore.configFlow.first()
        if (!config.isReady) return TranslationState.Error(TranslationState.CONFIG_MISSING)
        return runCatching { translationRepo.translate(text, config).getOrThrow() }
            .fold(
                onSuccess = { TranslationState.Success(it) },
                onFailure = {
                    if (it is ShortContentException) {
                        TranslationState.Error(TranslationState.TOO_SHORT)
                    } else {
                        TranslationState.Error(it.toUiError(appContext.localized()).message)
                    }
                }
            )
    }
}
