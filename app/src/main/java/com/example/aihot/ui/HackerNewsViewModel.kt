package com.example.aihot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aihot.data.HackerNewsRepository
import com.example.aihot.data.HackerNewsStory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * HackerNews ViewModel。
 *
 * 与 [HotTopicsViewModel] 同构:精选 tab 顶部的装饰性模块,失败/为空时静默隐藏,
 * 不阻塞下方主列表的加载与展示。
 */
class HackerNewsViewModel : ViewModel() {

    private val repo = HackerNewsRepository()

    private val _state = MutableStateFlow<UiState<List<HackerNewsStory>>>(UiState.Loading)
    val state: StateFlow<UiState<List<HackerNewsStory>>> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repo.fetchTopStories(limit = 10) }
                .onSuccess { list ->
                    // 空结果视为「无内容」而非错误:模块整体隐藏。
                    _state.value =
                        if (list.isEmpty()) UiState.Error("无内容") else UiState.Success(list)
                }
                .onFailure { _state.value = UiState.Error(it.message ?: "未知错误") }
        }
    }
}
