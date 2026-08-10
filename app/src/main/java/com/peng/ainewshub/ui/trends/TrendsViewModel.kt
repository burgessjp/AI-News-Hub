package com.peng.ainewshub.ui.trends

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.R
import com.peng.ainewshub.data.AppException
import com.peng.ainewshub.data.TrendsDigest
import com.peng.ainewshub.data.TrendsRepository
import com.peng.ainewshub.ui.i18n.localized
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
 *  - [refresh]:顶栏刷新按钮,触发一次网络重读(归档缓存仍生效);
 *  - 重击 tab 走 [load] 即可。
 *
 * 加载中保留旧内容(Success 不回落 Loading),只亮顶栏转圈。
 */
class TrendsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = TrendsRepository()

    private val _state = MutableStateFlow<TrendsState>(TrendsState.Loading)
    val state: StateFlow<TrendsState> = _state.asStateFlow()

    /** 读取进行中(顶栏转圈 + 防重复)。 */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        load()
    }

    /** 自动加载:读归档 latest_trends 字段(命中 2 分钟缓存秒回)。 */
    fun load() = run()

    /** 手动刷新:触发一次网络重读(归档只读,刷新仅绕过 UI 防抖,缓存层仍生效)。 */
    fun refresh() = run()

    private fun run() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        // 已有内容时保留展示,后台重读;否则进 Loading
        if (_state.value !is TrendsState.Success) _state.value = TrendsState.Loading
        viewModelScope.launch {
            repo.loadTrends().fold(
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
            _isRefreshing.value = false
        }
    }
}
