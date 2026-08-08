package com.peng.ainewshub.ui
import com.peng.ainewshub.ui.i18n.localized
import com.peng.ainewshub.R

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.data.LinuxDoHotRepository
import com.peng.ainewshub.data.LinuxDoTopic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * LinuxDo 热榜 ViewModel。
 *
 * 与 [GitHubTrendingViewModel] 同构:AndroidViewModel 拿 cacheDir 注入 Repository,
 * 启用 4 小时文件缓存(进入页面命中缓存秒回,不打网络)。
 *
 * linux.do 内容是中文,不接翻译(对比 GitHubTrending 接了英文描述翻译)。
 */
class LinuxDoHotViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = LinuxDoHotRepository(
        cacheDir = application.cacheDir
    )

    private val _state = MutableStateFlow<UiState<List<LinuxDoTopic>>>(UiState.Loading)
    val state: StateFlow<UiState<List<LinuxDoTopic>>> = _state.asStateFlow()

    /**
     * 上次成功拿到数据时的「数据落盘时刻」。命中缓存秒回时也是缓存写入时刻
     * —— 这正是「上次刷新时间」的语义。null 表示尚未成功过。
     */
    private val _lastRefreshAt = MutableStateFlow<Long?>(null)
    val lastRefreshAt: StateFlow<Long?> = _lastRefreshAt.asStateFlow()

    /** 手动刷新进行中(下拉刷新转圈 + 防重复)。 */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repo.fetch() }
                .onSuccess { result ->
                    _state.value =
                        if (result.topics.isEmpty()) UiState.Error(getApplication<Application>().localized().getString(R.string.common_empty_today), ErrorKind.NoData) else UiState.Success(result.topics)
                    _lastRefreshAt.value = result.fetchedAt
                }
                .onFailure { _state.value = it.toUiError(getApplication<Application>().localized()) }
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
                        if (result.topics.isEmpty()) UiState.Error(getApplication<Application>().localized().getString(R.string.common_empty_today), ErrorKind.NoData) else UiState.Success(result.topics)
                    _lastRefreshAt.value = result.fetchedAt
                }
                .onFailure {
                    if (_state.value !is UiState.Success) {
                        _state.value = it.toUiError(getApplication<Application>().localized())
                    }
                }
            _isRefreshing.value = false
        }
    }
}
