package com.peng.ainewshub.ui

import androidx.lifecycle.viewModelScope
import android.app.Application
import com.peng.ainewshub.data.model.SourceListResult
import com.peng.ainewshub.data.model.HuggingFacePaper
import com.peng.ainewshub.data.source.HuggingFacePapersArchiveRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * HuggingFace Trending Papers ViewModel。
 *
 * 继承 [SourceListViewModel]:state / refresh / forceRefresh 等公共逻辑由基类统一,
 * 数据恒走 gitcode 归档([HuggingFacePapersArchiveRepository])。翻译逻辑由
 * [translateSupport] 委托(整体翻译 title+summary,以 paper.id 为 key)。
 */
class HuggingFacePapersViewModel(application: Application) : SourceListViewModel<HuggingFacePaper>(application) {

    private val archiveRepo = HuggingFacePapersArchiveRepository()
    private val translateSupport = TranslateSupport(application)

    val configFlow = translateSupport.configFlow

    /** 整体译文翻译状态(paperId → state)。 */
    val translationStates: StateFlow<Map<String, TranslationState>> = translateSupport.states

    override suspend fun doFetch(): SourceListResult<HuggingFacePaper> = archiveRepo.fetch()

    override suspend fun doForceRefresh(): SourceListResult<HuggingFacePaper> = archiveRepo.forceRefresh()

    /**
     * 翻译论文(title + summary 整体),列表页单按钮触发。
     * 合并为一段文本一次翻译;两者都为空时不翻译。
     */
    fun translatePaper(paper: HuggingFacePaper) {
        val text = buildString {
            if (paper.title.isNotBlank()) append(paper.title.trim())
            if (paper.summary.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(paper.summary.trim())
            }
        }
        translateSupport.translate(viewModelScope, paper.id, text)
    }
}
