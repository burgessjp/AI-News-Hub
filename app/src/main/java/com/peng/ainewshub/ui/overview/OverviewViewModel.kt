package com.peng.ainewshub.ui.overview

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.R
import com.peng.ainewshub.data.AppException
import com.peng.ainewshub.data.OverviewDigest
import com.peng.ainewshub.data.OverviewRepository
import com.peng.ainewshub.data.source.ArchiveHttpClient
import com.peng.ainewshub.ui.RefreshNotices
import com.peng.ainewshub.ui.i18n.localized
import com.peng.ainewshub.widget.HotNowWidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 今日总览 UI 状态。
 *
 * [NoData]:今日总览尚未生成(归档字段缺失),语义是空态而非出错。
 * [Error]:网络/解析失败。
 */
sealed interface OverviewState {
    data object Loading : OverviewState
    data object NoData : OverviewState
    data class Error(val message: String) : OverviewState
    data class Success(val digest: OverviewDigest) : OverviewState
}

/**
 * 今日总览 ViewModel —— 首个根 tab「总览」。
 *
 * 总览由流水线预生成,本 VM 只读归档(对齐 SummaryRepository 范式):
 *  - init 自动 [load](命中 index.json 2 分钟缓存即秒回);
 *  - [refresh]:下拉刷新,绕过 index 缓存强制重读(流水线刚推送立即可见);
 *  - 重击 tab 走 [load] 即可。
 *
 * 加载中保留旧内容(Success 不回落 Loading),只亮下拉刷新指示。
 */
class OverviewViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = OverviewRepository()

    private val _state = MutableStateFlow<OverviewState>(OverviewState.Loading)
    val state: StateFlow<OverviewState> = _state.asStateFlow()

    /** 读取进行中(下拉刷新指示 + 防重复)。 */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        load()
    }

    /** 自动加载:读归档 latest_overview 字段(命中 2 分钟缓存秒回)。 */
    fun load() = run(false)

    /** 下拉刷新:绕过 index.json 2 分钟缓存强制重读。 */
    fun refresh() = run(true)

    private fun run(force: Boolean) {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        // 已有内容时保留展示,后台重读;否则进 Loading
        if (_state.value !is OverviewState.Success) _state.value = OverviewState.Loading
        // 强刷前的批次指纹:Success 保留旧内容,天然提供「刷新前」参照
        val previousGeneratedAt = (_state.value as? OverviewState.Success)?.digest?.generatedAt
        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                repo.loadDigest(force).fold(
                    onSuccess = {
                        // 强刷成功但批次指纹未变(且非离线兜底)→ 告知「已是最新批次」,
                        // 消除「刷新了却没变化 = App 坏了」的误解(归档一天只更数批)
                        if (force && previousGeneratedAt == it.generatedAt &&
                            !ArchiveHttpClient.offlineMode.value
                        ) {
                            RefreshNotices.notifyNoNewBatch(it.generatedAt)
                        }
                        _state.value = OverviewState.Success(it)
                        // 联动刷新「今日热点」小组件(同进程命中 ArchiveHttpClient 2 分钟
                        // 内存缓存,零额外网络;失败静默,不影响本页 UI 态)
                        HotNowWidgetUpdater.refreshFromApp(getApplication())
                    },
                    onFailure = { e ->
                        Log.w("UiError", "总览加载失败: ${e.message ?: "(no message)"}", e)
                        val localized = getApplication<Application>().localized()
                        _state.value = when (e) {
                            is AppException.NoData -> OverviewState.NoData
                            is AppException.Network -> OverviewState.Error(localized.getString(R.string.error_network))
                            is AppException.ServerError -> OverviewState.Error(localized.getString(R.string.overview_error_parse))
                            is AppException.RateLimited -> OverviewState.Error(localized.getString(R.string.error_rate_limited))
                            else -> OverviewState.Error(localized.getString(R.string.overview_error_load_failed))
                        }
                    }
                )
            } finally {
                // finally 复位:非预期异常(JSON OOM 等)也不能让刷新态永久卡 true。
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
