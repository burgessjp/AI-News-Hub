package com.example.aihot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aihot.data.StormzhangAiNews
import com.example.aihot.data.StormzhangAiNewsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * stormzhang AI Daily ViewModel。
 *
 * 与 [LinuxDoHotViewModel] 同构:AndroidViewModel 拿 cacheDir 注入 Repository,
 * 启用 4 小时文件缓存(进入页面命中缓存秒回,不打网络)。
 *
 * 内容已是中文摘要(AI 生成),不接翻译(对比 GitHubTrending 接了英文描述翻译)。
 *
 * 额外暴露 [pageDate]:页面声明的资讯日期(如 "2026.07.13"),取自 title,
 * 供 UI 在顶栏展示「AI Daily · 2026.07.13」副标题,让用户知道看的是哪一天。
 */
class StormzhangAiNewsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = StormzhangAiNewsRepository(
        cacheDir = application.cacheDir
    )

    private val _state = MutableStateFlow<UiState<List<StormzhangAiNews>>>(UiState.Loading)
    val state: StateFlow<UiState<List<StormzhangAiNews>>> = _state.asStateFlow()

    /**
     * 上次成功拿到数据时的「数据落盘时刻」。命中缓存秒回时也是缓存写入时刻
     * —— 这正是「上次刷新时间」的语义。null 表示尚未成功过。
     */
    private val _lastRefreshAt = MutableStateFlow<Long?>(null)
    val lastRefreshAt: StateFlow<Long?> = _lastRefreshAt.asStateFlow()

    /** 手动刷新进行中(下拉刷新转圈 + 防重复)。 */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** 页面声明的资讯日期(如 "2026.07.13");解析不到时为空。 */
    private val _pageDate = MutableStateFlow("")
    val pageDate: StateFlow<String> = _pageDate.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repo.fetch() }
                .onSuccess { result ->
                    _state.value =
                        if (result.news.isEmpty()) UiState.Error("无内容") else UiState.Success(result.news)
                    _lastRefreshAt.value = result.fetchedAt
                    _pageDate.value = result.pageDate
                }
                .onFailure { _state.value = UiState.Error(it.message ?: "未知错误") }
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
                        if (result.news.isEmpty()) UiState.Error("无内容") else UiState.Success(result.news)
                    _lastRefreshAt.value = result.fetchedAt
                    _pageDate.value = result.pageDate
                }
                .onFailure {
                    if (_state.value !is UiState.Success) {
                        _state.value = UiState.Error(it.message ?: "未知错误")
                    }
                }
            _isRefreshing.value = false
        }
    }
}
