package com.peng.ainewshub.ui

import androidx.lifecycle.viewModelScope
import android.app.Application
import com.peng.ainewshub.data.OpenAiAnthropicNews
import com.peng.ainewshub.data.SourceListResult
import com.peng.ainewshub.data.source.OpenAiAnthropicNewsArchiveRepository
import com.peng.ainewshub.data.source.OpenAiAnthropicNewsSource
import kotlinx.coroutines.flow.StateFlow

/**
 * OpenAI x Anthropic 厂商动态 ViewModel。
 *
 * 继承 [SourceListViewModel]:sourceMode 订阅 / state / refresh / forceRefresh 等公共逻辑
 * 由基类统一。翻译逻辑由 [translateSupport] 委托(整体翻译 title+summary,以 url 为 key)。
 *
 * **只归档**:两家均无稳定公开 API(Anthropic 无官方 RSS),两种 SourceMode 都走归档。
 * [sourceMode] 仍订阅设置,仅为顶栏角标一致。
 */
class OpenAiAnthropicNewsViewModel(application: Application) : SourceListViewModel<OpenAiAnthropicNews>(application) {

    private val archiveRepo: OpenAiAnthropicNewsSource = OpenAiAnthropicNewsArchiveRepository()
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
