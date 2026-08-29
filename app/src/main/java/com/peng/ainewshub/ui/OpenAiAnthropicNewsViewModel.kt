package com.peng.ainewshub.ui

import androidx.lifecycle.viewModelScope
import android.app.Application
import com.peng.ainewshub.data.model.OpenAiAnthropicNews
import com.peng.ainewshub.data.model.SourceListResult
import com.peng.ainewshub.data.source.OpenAiAnthropicNewsArchiveRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * OpenAI x Anthropic 厂商动态 ViewModel。
 *
 * 继承 [SourceListViewModel]:state / refresh / forceRefresh 等公共逻辑由基类统一,
 * 数据恒走 gitcode 归档([OpenAiAnthropicNewsArchiveRepository],两家均无稳定公开
 * API,App 端不直连)。翻译逻辑由 [translateSupport] 委托
 * (整体翻译 title+summary,以 url 为 key)。
 */
class OpenAiAnthropicNewsViewModel(application: Application) : SourceListViewModel<OpenAiAnthropicNews>(application) {

    private val archiveRepo = OpenAiAnthropicNewsArchiveRepository()
    private val translateSupport = TranslateSupport(application)

    val configFlow = translateSupport.configFlow

    /** 整体译文翻译状态(url → state)。 */
    val translationStates: StateFlow<Map<String, TranslationState>> = translateSupport.states

    override suspend fun doFetch(): SourceListResult<OpenAiAnthropicNews> = archiveRepo.fetch()

    override suspend fun doForceRefresh(): SourceListResult<OpenAiAnthropicNews> = archiveRepo.forceRefresh()

    /**
     * 翻译文章(title + summary 整体),列表页单按钮触发。
     * 合并为一段文本一次翻译;两者都为空时不翻译。
     */
    fun translateArticle(article: OpenAiAnthropicNews) {
        val text = buildString {
            if (article.title.isNotBlank()) append(article.title.trim())
            if (article.summary.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(article.summary.trim())
            }
        }
        translateSupport.translate(viewModelScope, article.url, text)
    }
}
