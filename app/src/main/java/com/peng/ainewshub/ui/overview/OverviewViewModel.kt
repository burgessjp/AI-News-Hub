package com.peng.ainewshub.ui.overview

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.data.AppException
import com.peng.ainewshub.data.OverviewDigest
import com.peng.ainewshub.data.OverviewRepository
import com.peng.ainewshub.widget.HotNowWidgetUpdater
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
 *  - [refresh]:顶栏刷新按钮,触发一次网络重读(归档缓存仍生效);
 *  - 重击 tab 走 [load] 即可。
 *
 * 加载中保留旧内容(Success 不回落 Loading),只亮顶栏转圈。
 */
class OverviewViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = OverviewRepository(application)

    private val _state = MutableStateFlow<OverviewState>(OverviewState.Loading)
    val state: StateFlow<OverviewState> = _state.asStateFlow()

    /** 读取进行中(顶栏转圈 + 防重复)。 */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        load()
    }

    /** 自动加载:读归档 latest_overview 字段(命中 2 分钟缓存秒回)。 */
    fun load() = run()

    /** 手动刷新:触发一次网络重读(归档只读,刷新仅绕过 UI 防抖,缓存层仍生效)。 */
    fun refresh() = run()

    private fun run() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        // 已有内容时保留展示,后台重读;否则进 Loading
        if (_state.value !is OverviewState.Success) _state.value = OverviewState.Loading
        viewModelScope.launch {
            repo.loadDigest().fold(
                onSuccess = {
                    _state.value = OverviewState.Success(it)
                    // 联动刷新「今日热点」小组件(同进程命中 ArchiveHttpClient 2 分钟
                    // 内存缓存,零额外网络;失败静默,不影响本页 UI 态)
                    HotNowWidgetUpdater.refreshFromApp(getApplication())
                },
                onFailure = { e ->
                    Log.w("UiError", "总览加载失败: ${e.message ?: "(no message)"}", e)
                    _state.value = when (e) {
                        is AppException.NoData -> OverviewState.NoData
                        is AppException.Network -> OverviewState.Error("网络异常,请检查连接后重试")
                        is AppException.ServerError -> OverviewState.Error("数据解析失败,请稍后重试")
                        is AppException.RateLimited -> OverviewState.Error("访问受限,请稍后重试")
                        else -> OverviewState.Error("总览加载失败,请稍后重试")
                    }
                }
            )
            _isRefreshing.value = false
        }
    }
}
