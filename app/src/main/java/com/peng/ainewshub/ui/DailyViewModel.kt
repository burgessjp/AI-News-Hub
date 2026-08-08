package com.peng.ainewshub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.data.DailyReport
import com.peng.ainewshub.data.DailySummary
import com.peng.ainewshub.data.NewsRepository
import com.peng.ainewshub.ui.i18n.localized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 日报 ViewModel。最新日报 + 历史归档。 */
class DailyViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = NewsRepository()

    private val _latest = MutableStateFlow<UiState<DailyReport>>(UiState.Loading)
    val latest: StateFlow<UiState<DailyReport>> = _latest.asStateFlow()

    private val _archive = MutableStateFlow<UiState<List<DailySummary>>>(UiState.Loading)
    val archive: StateFlow<UiState<List<DailySummary>>> = _archive.asStateFlow()

    private val _selected = MutableStateFlow<UiState<DailyReport>>(UiState.Loading)
    val selected: StateFlow<UiState<DailyReport>> = _selected.asStateFlow()

    /** 下拉刷新进行中(供 PullToRefreshBox 转圈);不打断现有内容。 */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        refreshLatest()
        loadArchive()
    }

    fun refreshLatest() {
        _latest.value = UiState.Loading
        viewModelScope.launch {
            runCatching { repo.fetchDaily() }
                .onSuccess { _latest.value = UiState.Success(it) }
                .onFailure { _latest.value = it.toUiError(getApplication<Application>().localized()) }
        }
    }

    /**
     * 下拉刷新最新日报:不翻回 Loading(内容区保持现有日报,仅转刷新指示)。
     * 失败保留旧内容;刷新中忽略重复触发。与 HackerNewsViewModel.forceRefresh 同模式。
     */
    fun pullRefreshLatest() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            runCatching { repo.fetchDaily() }
                .onSuccess { _latest.value = UiState.Success(it) }
                .onFailure {
                    if (_latest.value !is UiState.Success) {
                        _latest.value = it.toUiError(getApplication<Application>().localized())
                    }
                }
            _isRefreshing.value = false
        }
    }

    fun loadArchive() {
        _archive.value = UiState.Loading
        viewModelScope.launch {
            runCatching { repo.fetchDailies() }
                .onSuccess { _archive.value = UiState.Success(it) }
                .onFailure { _archive.value = it.toUiError(getApplication<Application>().localized()) }
        }
    }

    /** 加载某一天的日报(/daily/{date})。 */
    fun loadDate(date: String) {
        _selected.value = UiState.Loading
        viewModelScope.launch {
            runCatching { repo.fetchDaily(date) }
                .onSuccess { _selected.value = UiState.Success(it) }
                .onFailure { _selected.value = it.toUiError(getApplication<Application>().localized()) }
        }
    }
}
