package com.peng.ainewshub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.R
import com.peng.ainewshub.data.model.HotTopic
import com.peng.ainewshub.data.repo.NewsRepository
import com.peng.ainewshub.ui.i18n.localized
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
class HotTopicsViewModel(application: Application) : AndroidViewModel(application) {

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
                        if (list.isEmpty()) {
                            UiState.Error(getApplication<Application>().localized().getString(R.string.hot_topics_empty))
                        } else {
                            UiState.Success(list)
                        }
                }
                .onFailure { _state.value = it.toUiError(getApplication<Application>().localized()) }
        }
    }
}
