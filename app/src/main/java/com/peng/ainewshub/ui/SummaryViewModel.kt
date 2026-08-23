package com.peng.ainewshub.ui
import com.peng.ainewshub.ui.i18n.localized

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.data.SourceSummary
import com.peng.ainewshub.data.SummaryRepository
import com.peng.ainewshub.data.source.ArchiveHttpClient
import com.peng.ainewshub.ui.more.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * AI 摘要 ViewModel —— 聚合 8 个归档源的 AI 中文摘要。
 *
 * 摘要正文由数据流水线在抓取时预生成,写入快照顶层 `ai_summary_v2` 字段(见
 * [SummaryRepository]);App 端不再运行时调用 AI API,也不再依赖任何 AI 服务配置。
 *
 * 状态模型:8 个源各自独立的 [UiState]<[SourceSummary]>,并发拉取、互不影响
 * (一个源失败不拖累其余)。`states` 以全集 key 初始化一次拉满,顺序变化时不重拉。
 *
 * **源顺序跟随用户**:[sourceKeys] 取自 [SettingsStore.sourceOrderFlow](用户在「信息源」
 * 页拖拽自定义,默认 [com.peng.ainewshub.ui.more.DEFAULT_SOURCE_ORDER]);UI 据此决定
 * PagerState pageCount 与卡片渲染顺序。数据全集不变,仅展示顺序变 —— 改顺序后即时生效、无需重拉。
 *
 * 触发时机:
 *  - 进入页面 init 自动 [loadAll](8 源重置骨架,走 index 2 分钟缓存)
 *  - [refresh]:下拉刷新/重击 tab,绕过 index 缓存强制重拉,保留现有内容不回骨架
 */
class SummaryViewModel(application: Application) : AndroidViewModel(application) {

    private val summaryRepo = SummaryRepository()
    private val settingsStore = SettingsStore(application)

    /**
     * 当前源顺序(用户自定义,默认全集顺序)。UI 据此决定 PagerState pageCount
     * 与卡片渲染顺序。冷启动首次取值前用 [SummaryRepository.SOURCE_KEYS] 兜底,
     * 保证首帧 pageCount 不为 0(HorizontalPager 的 pageCount 不能为 0)。
     *
     * 摘要 Tab 用 HorizontalPager 渲染,卡片按 page index 取,顺序变化只改各页内容、
     * 无 LazyColumn 那样的 item 位移动画,故无需同步读真实顺序(默认顺序兜底即可)。
     */
    val sourceKeys: StateFlow<List<String>> = settingsStore.sourceOrderFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SummaryRepository.SOURCE_KEYS)

    // 8 源各自独立状态,key = source(全集,顺序变化时不重拉)
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
     * 并发拉取 8 个全集归档源的摘要。每源独立 try/catch,失败只置该源 Error。
     * 以 [SummaryRepository.SOURCE_KEYS](全集)为拉取范围,与用户自定义顺序无关
     * —— 顺序只影响展示,数据全集恒定。
     *
     * @param force 绕过 index.json 2 分钟缓存(下拉刷新);init 走缓存秒回
     * @param keepContent true 保留各源现有内容(刷新只亮指示器);false 重置骨架(init)
     */
    private fun loadAll(force: Boolean, keepContent: Boolean) {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        if (!keepContent) {
            // 全集 8 源置 Loading(让 UI 显骨架)
            _states.value = SummaryRepository.SOURCE_KEYS.associateWith { UiState.Loading as UiState<SourceSummary> }
        }
        // 强刷前的各源批次指纹(快照落盘时刻):刷新完成后判定「无新批次」用
        val previousTimes = if (force) snapshotFetchedTimes() else null
        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                // 8 源并发,各自独立失败
                SummaryRepository.SOURCE_KEYS.map { key ->
                    async {
                        val result = summaryRepo.summarize(key, force)
                        val state: UiState<SourceSummary> = result.fold(
                            onSuccess = { UiState.Success(it) },
                            onFailure = { it.toUiError(getApplication<Application>().localized()) }
                        )
                        _states.value = _states.value + (key to state)
                    }
                }.awaitAll()
                // 强刷成功且全部源时间戳与刷新前一致 → 告知「已是最新批次」。
                // 任一源失败或时间戳变化都不提示(有失败时提示会掩盖问题);
                // 刷新前有源非 Success(指纹为 null)同样不提示——那属于「拉到了新东西」
                if (previousTimes != null && !ArchiveHttpClient.offlineMode.value) {
                    val afterTimes = snapshotFetchedTimes()
                    val unchanged = SummaryRepository.SOURCE_KEYS.all { previousTimes[it] == afterTimes[it] }
                    if (unchanged) {
                        val newest = afterTimes.values.filterNotNull().maxOrNull() ?: 0L
                        RefreshNotices.notifyNoNewBatch(newest)
                    }
                }
            } finally {
                // finally 复位:非预期异常也不能让刷新态永久卡 true。
                // 复位前保证最小转一档:index 去重窗口 + 快照路径缓存双双命中时刷新会
                // 瞬间完成(如 2 秒内连拉两次),isRefreshing 同帧 true→false 会让
                // PullToRefreshBox 指示器卡在展示态不收起
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

    /** 各源批次指纹快照:source → 快照落盘时刻(非 Success 的源为 null)。 */
    private fun snapshotFetchedTimes(): Map<String, Long?> =
        SummaryRepository.SOURCE_KEYS.associateWith { key ->
            (_states.value[key] as? UiState.Success<SourceSummary>)?.data?.fetchedAtMs
        }

    /** init 自动加载:8 源重置骨架,命中 index 缓存秒回。 */
    fun loadAll() = loadAll(force = false, keepContent = false)

    /** 下拉刷新/重击 tab:绕过 index 缓存强制重拉,保留现有内容(只亮下拉指示器)。 */
    fun refresh() = loadAll(force = true, keepContent = true)

    /** 单源重试:仅重拉失败的源。 */
    fun retry(source: String) {
        viewModelScope.launch {
            _states.value = _states.value + (source to UiState.Loading as UiState<SourceSummary>)
            val result = summaryRepo.summarize(source)
            val state: UiState<SourceSummary> = result.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { it.toUiError(getApplication<Application>().localized()) }
            )
            _states.value = _states.value + (source to state)
        }
    }
}
