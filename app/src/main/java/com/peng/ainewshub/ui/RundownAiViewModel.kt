package com.peng.ainewshub.ui

import androidx.lifecycle.viewModelScope
import android.app.Application
import com.peng.ainewshub.data.model.RundownAiArticle
import com.peng.ainewshub.data.model.SourceListResult
import com.peng.ainewshub.data.source.RundownAiArchiveRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * The Rundown AI ViewModel。
 *
 * 继承 [SourceListViewModel]:state / refresh / forceRefresh 等公共逻辑由基类统一,
 * 数据恒走 gitcode 归档([RundownAiArchiveRepository])。翻译逻辑由 [translateSupport]
 * 委托(整体翻译 title+subtitle,以 slug 为 key)。
 */
class RundownAiViewModel(application: Application) : SourceListViewModel<RundownAiArticle>(application) {

    private val archiveRepo = RundownAiArchiveRepository()
    private val translateSupport = TranslateSupport(application)

    val configFlow = translateSupport.configFlow

    /** 整体译文翻译状态(slug → state)。 */
    val translationStates: StateFlow<Map<String, TranslationState>> = translateSupport.states

    override suspend fun doFetch(): SourceListResult<RundownAiArticle> = archiveRepo.fetch()

    override suspend fun doForceRefresh(): SourceListResult<RundownAiArticle> = archiveRepo.forceRefresh()

    /**
     * 翻译文章(title + subtitle 整体),列表页单按钮触发。
     * 合并为一段文本一次翻译;slug 为空或两者都为空时不翻译。
     */
    fun translateArticle(article: RundownAiArticle) {
        val key = article.slug
        if (key.isBlank()) return
        val text = buildString {
            if (article.title.isNotBlank()) append(article.title.trim())
            if (article.subtitle.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(article.subtitle.trim())
            }
        }
        translateSupport.translate(viewModelScope, key, text)
    }
}
