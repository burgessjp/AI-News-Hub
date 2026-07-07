package com.example.aihot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aihot.data.DailyReport
import com.example.aihot.data.DailySummary
import com.example.aihot.data.NewsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 日报 ViewModel。最新日报 + 历史归档。 */
class DailyViewModel : ViewModel() {

    private val repo = NewsRepository()

    private val _latest = MutableStateFlow<UiState<DailyReport>>(UiState.Loading)
    val latest: StateFlow<UiState<DailyReport>> = _latest.asStateFlow()

    private val _archive = MutableStateFlow<UiState<List<DailySummary>>>(UiState.Loading)
    val archive: StateFlow<UiState<List<DailySummary>>> = _archive.asStateFlow()

    private val _selected = MutableStateFlow<UiState<DailyReport>>(UiState.Loading)
    val selected: StateFlow<UiState<DailyReport>> = _selected.asStateFlow()

    init {
        refreshLatest()
        loadArchive()
    }

    fun refreshLatest() {
        _latest.value = UiState.Loading
        viewModelScope.launch {
            runCatching { repo.fetchDaily() }
                .onSuccess { _latest.value = UiState.Success(it) }
                .onFailure { _latest.value = UiState.Error(it.message ?: "未知错误") }
        }
    }

    fun loadArchive() {
        _archive.value = UiState.Loading
        viewModelScope.launch {
            runCatching { repo.fetchDailies() }
                .onSuccess { _archive.value = UiState.Success(it) }
                .onFailure { _archive.value = UiState.Error(it.message ?: "未知错误") }
        }
    }

    /** 加载某一天的日报(/daily/{date})。 */
    fun loadDate(date: String) {
        _selected.value = UiState.Loading
        viewModelScope.launch {
            runCatching { repo.fetchDaily(date) }
                .onSuccess { _selected.value = UiState.Success(it) }
                .onFailure { _selected.value = UiState.Error(it.message ?: "未知错误") }
        }
    }
}
