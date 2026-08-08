package com.peng.ainewshub.ui

import androidx.lifecycle.viewModelScope
import android.app.Application
import com.peng.ainewshub.data.HackerNewsRepository
import com.peng.ainewshub.data.HackerNewsStory
import com.peng.ainewshub.data.SourceListResult
import com.peng.ainewshub.data.source.HackerNewsArchiveRepository
import com.peng.ainewshub.data.source.HackerNewsSource
import com.peng.ainewshub.data.source.SourceMode
import kotlinx.coroutines.flow.StateFlow

/**
 * HackerNews ViewModel。
 *
 * 继承 [SourceListViewModel]:sourceMode 订阅 / state / refresh / forceRefresh 等公共逻辑
 * 由基类统一。翻译逻辑由 [translateSupport] 委托(标题翻译,以 story.id 为 key)。
 *
 * HackerNews 的 fetch 带 limit(默认 20),[doFetch]/[doForceRefresh] 传 limit=20
 * (对齐既有约定,而非 Repository 原默认 10)。
 */
class HackerNewsViewModel(application: Application) : SourceListViewModel<HackerNewsStory>(application) {

    private val liveRepo: HackerNewsRepository = HackerNewsRepository(cacheDir = application.cacheDir)
    private val archiveRepo: HackerNewsSource = HackerNewsArchiveRepository()
    private val translateSupport = TranslateSupport(application)

    val configFlow = translateSupport.configFlow

    /** story 标题翻译状态(storyId 字符串 → state;委托统一用 String key)。 */
    val titleStates: StateFlow<Map<String, TranslationState>> = translateSupport.states

    private fun currentRepo(): HackerNewsSource =
        if (sourceMode.value == SourceMode.ARCHIVE) archiveRepo else liveRepo

    override suspend fun doFetch(): SourceListResult<HackerNewsStory> = currentRepo().fetch(limit = 20)

    override suspend fun doForceRefresh(): SourceListResult<HackerNewsStory> = currentRepo().forceRefresh(limit = 20)

    /** 翻译 story 标题(列表页)。 */
    fun translateTitle(story: HackerNewsStory) =
        translateSupport.translate(viewModelScope, story.id.toString(), story.title)
}
