package com.example.aihot.ui.overview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aihot.data.AiConfigMissingException
import com.example.aihot.data.OverviewDigest
import com.example.aihot.data.OverviewRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 今日总览 UI 状态。
 *
 * 与其它列表屏共用 [com.example.aihot.ui.UiState] 不同:这里多一个
 * [ConfigMissing]——总览是端侧实时调 AI(用户自配 key)的功能,未配置时
 * 显示全屏引导而非错误(它是默认首页,首启用户必然先经过这个状态)。
 */
sealed interface OverviewState {
    data object Loading : OverviewState
    data object ConfigMissing : OverviewState
    data class Error(val message: String) : OverviewState
    data class Success(val digest: OverviewDigest) : OverviewState
}

/**
 * 今日总览 ViewModel —— 首个根 tab「总览」。
 *
 * 触发时机:
 *  - init 自动 [load](缓存指纹命中即秒回,未命中才调 AI 生成);
 *  - [refresh]:顶栏刷新按钮,**强制重新生成**(消耗 token,用户主动行为);
 *  - 重击 tab 走 [load] 即可:指纹未变命中缓存零开销,归档更新则自动重新生成。
 *
 * 加载中保留旧内容(Success 不回落 Loading),只亮顶栏转圈。
 */
class OverviewViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = OverviewRepository(application)

    private val _state = MutableStateFlow<OverviewState>(OverviewState.Loading)
    val state: StateFlow<OverviewState> = _state.asStateFlow()

    /** 生成/校验进行中(顶栏转圈 + 防重复)。 */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        load()
    }

    /** 自动加载:缓存指纹命中直接展示,未命中(当日首次/归档已更新)才调 AI。 */
    fun load() = run(force = false)

    /** 手动刷新:忽略缓存强制重新生成。 */
    fun refresh() = run(force = true)

    private fun run(force: Boolean) {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        // 已有内容时保留展示,后台校验/生成;否则进 Loading
        if (_state.value !is OverviewState.Success) _state.value = OverviewState.Loading
        viewModelScope.launch {
            repo.loadDigest(force = force).fold(
                onSuccess = { _state.value = OverviewState.Success(it) },
                onFailure = { e ->
                    _state.value = when (e) {
                        is AiConfigMissingException -> OverviewState.ConfigMissing
                        else -> OverviewState.Error(e.message ?: "生成失败")
                    }
                }
            )
            _isRefreshing.value = false
        }
    }
}
