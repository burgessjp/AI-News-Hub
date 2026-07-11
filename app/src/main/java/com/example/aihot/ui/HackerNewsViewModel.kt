package com.example.aihot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aihot.data.HackerNewsRepository
import com.example.aihot.data.HackerNewsStory
import com.example.aihot.data.ShortContentException
import com.example.aihot.data.TranslationConfigStore
import com.example.aihot.data.TranslationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * HackerNews ViewModel。
 *
 * 与 [HotTopicsViewModel] 同构:精选 tab 顶部的装饰性模块,失败/为空时静默隐藏,
 * 不阻塞下方主列表的加载与展示。
 *
 * 继承 [AndroidViewModel] 以拿到 application.cacheDir 注入 [HackerNewsRepository],
 * 启用 30 分钟文件缓存:进入页面命中缓存秒回,不打网络。
 */
class HackerNewsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = HackerNewsRepository(cacheDir = application.cacheDir)
    private val translationRepo = TranslationRepository(application.cacheDir)
    private val configStore = TranslationConfigStore(application)

    private val _state = MutableStateFlow<UiState<List<HackerNewsStory>>>(UiState.Loading)
    val state: StateFlow<UiState<List<HackerNewsStory>>> = _state.asStateFlow()

    /** 翻译配置流(UI 订阅以决定是否显示「译」按钮)。 */
    val configFlow = configStore.configFlow

    /** story 标题翻译状态(storyId → state)。与 HackerNewsCommentsViewModel 同源。 */
    private val _titleStates = MutableStateFlow<Map<Long, TranslationState>>(emptyMap())
    val titleStates: StateFlow<Map<Long, TranslationState>> = _titleStates.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repo.fetchTopStories(limit = 20) }
                .onSuccess { list ->
                    // 空结果视为「无内容」而非错误:模块整体隐藏。
                    _state.value =
                        if (list.isEmpty()) UiState.Error("无内容") else UiState.Success(list)
                }
                .onFailure { _state.value = UiState.Error(it.message ?: "未知错误") }
        }
    }

    /** 翻译 story 标题(列表页)。 */
    fun translateTitle(story: HackerNewsStory) {
        val current = _titleStates.value[story.id]
        if (current is TranslationState.Loading) return
        if (story.title.isBlank()) return
        _titleStates.value = _titleStates.value + (story.id to TranslationState.Loading)
        viewModelScope.launch {
            val outcome = doTranslate(story.title)
            _titleStates.value = _titleStates.value + (story.id to outcome)
        }
    }

    private suspend fun doTranslate(text: String): TranslationState {
        val config = configStore.configFlow.first()
        if (!config.isReady) return TranslationState.Error(TranslationState.CONFIG_MISSING)
        return runCatching { translationRepo.translate(text, config).getOrThrow() }
            .fold(
                onSuccess = { TranslationState.Success(it) },
                onFailure = {
                    if (it is ShortContentException) {
                        TranslationState.Error(TranslationState.TOO_SHORT)
                    } else {
                        TranslationState.Error(it.message ?: "翻译失败")
                    }
                }
            )
    }
}
