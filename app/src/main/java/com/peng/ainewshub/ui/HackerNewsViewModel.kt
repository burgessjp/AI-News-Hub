package com.peng.ainewshub.ui

import androidx.lifecycle.viewModelScope
import android.app.Application
import com.peng.ainewshub.data.HackerNewsStory
import com.peng.ainewshub.data.SourceListResult
import com.peng.ainewshub.data.source.HackerNewsArchiveRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * HackerNews ViewModel。
 *
 * 继承 [SourceListViewModel]:state / refresh / forceRefresh 等公共逻辑由基类统一,
 * 数据恒走 gitcode 归档([HackerNewsArchiveRepository])。翻译逻辑由 [translateSupport]
 * 委托(标题翻译,以 story.id 为 key)。
 */
class HackerNewsViewModel(application: Application) : SourceListViewModel<HackerNewsStory>(application) {

    private val archiveRepo = HackerNewsArchiveRepository()
    private val translateSupport = TranslateSupport(application)

    val configFlow = translateSupport.configFlow

    /** story 标题翻译状态(storyId 字符串 → state;委托统一用 String key)。 */
    val titleStates: StateFlow<Map<String, TranslationState>> = translateSupport.states

    override suspend fun doFetch(): SourceListResult<HackerNewsStory> = archiveRepo.fetch()

    override suspend fun doForceRefresh(): SourceListResult<HackerNewsStory> = archiveRepo.forceRefresh()

    /** 翻译 story 标题(列表页)。 */
    fun translateTitle(story: HackerNewsStory) =
        translateSupport.translate(viewModelScope, story.id.toString(), story.title)
}
