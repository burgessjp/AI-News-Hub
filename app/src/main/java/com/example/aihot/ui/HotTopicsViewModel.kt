package com.example.aihot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aihot.data.HotTopic
import com.example.aihot.data.NewsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 今日热点 ViewModel。
 *
 * 独立于 [ItemsViewModel]:热点是精选 tab 顶部的「装饰性」模块,失败/为空时
 * 静默隐藏,不阻塞下方主列表的加载与展示。
 */
class HotTopicsViewModel : ViewModel() {

    private val repo = NewsRepository()

    private val _state = MutableStateFlow<UiState<List<HotTopic>>>(UiState.Loading)
    val state: StateFlow<UiState<List<HotTopic>>> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repo.fetchHotTopics() }
                .onSuccess { list ->
                    // 空结果视为「无内容」而非错误:模块整体隐藏。
                    _state.value =
                        if (list.isEmpty()) UiState.Error("无热点") else UiState.Success(list)
                }
                .onFailure { _state.value = UiState.Error(it.message ?: "未知错误") }
        }
    }
}
