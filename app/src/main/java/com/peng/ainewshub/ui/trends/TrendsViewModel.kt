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
import com.peng.ainewshub.ui.i18n.localized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 热词趋势 UI 状态。
 *
 * [NoData]:趋势尚未生成(归档 latest_trends 字段缺失),语义是空态而非出错
 * —— 功能上线初期旧 index.json 即如此。
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
 * 趋势由流水线预生成(scripts/trend_keywords.py,纯统计不调 AI),本 VM 只读归档
 * (对齐 OverviewViewModel 范式):
 *  - init 自动 [load](命中 index.json 2 分钟缓存即秒回);
 *  - [refresh]:下拉刷新,绕过 index 缓存强制重读(流水线刚推送立即可见);
 *  - 重击 tab 走 [load] 即可。
 *
 * 加载中保留旧内容(Success 不回落 Loading),只亮下拉刷新指示。
 */
class TrendsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = TrendsRepository()

    private val _state = MutableStateFlow<TrendsState>(TrendsState.Loading)
    val state: StateFlow<TrendsState> = _state.asStateFlow()

    /** 读取进行中(下拉刷新指示 + 防重复)。 */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        load()
    }

    /** 自动加载:读归档 latest_trends 字段(命中 2 分钟缓存秒回)。 */
    fun load() = run(false)

    /** 下拉刷新:绕过 index.json 2 分钟缓存强制重读。 */
    fun refresh() = run(true)

    private fun run(force: Boolean) {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        // 已有内容时保留展示,后台重读;否则进 Loading
        if (_state.value !is TrendsState.Success) _state.value = TrendsState.Loading
        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                repo.loadTrends(force).fold(
                    onSuccess = {
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
