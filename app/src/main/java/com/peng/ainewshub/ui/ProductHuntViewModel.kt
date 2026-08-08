package com.peng.ainewshub.ui

import androidx.lifecycle.viewModelScope
import android.app.Application
import com.peng.ainewshub.data.ProductHunt
import com.peng.ainewshub.data.SourceListResult
import com.peng.ainewshub.data.source.ProductHuntArchiveRepository
import com.peng.ainewshub.data.source.ProductHuntSource
import kotlinx.coroutines.flow.StateFlow

/**
 * Product Hunt ViewModel。
 *
 * 继承 [SourceListViewModel]:sourceMode 订阅 / state / refresh / forceRefresh 等公共逻辑
 * 由基类统一。翻译逻辑由 [translateSupport] 委托(整体翻译 name+tagline,以 slug 为 key)。
 *
 * **只归档**:PH Developer Token 是服务端 secret 不进 APK,两种 SourceMode 都走归档。
 * [sourceMode] 仍订阅设置,仅为顶栏角标一致。
 */
class ProductHuntViewModel(application: Application) : SourceListViewModel<ProductHunt>(application) {

    private val archiveRepo: ProductHuntSource = ProductHuntArchiveRepository()
    private val translateSupport = TranslateSupport(application)

    val configFlow = translateSupport.configFlow

    /** 整体译文翻译状态(slug → state)。 */
    val translationStates: StateFlow<Map<String, TranslationState>> = translateSupport.states

    override suspend fun doFetch(): SourceListResult<ProductHunt> = archiveRepo.fetch()

    override suspend fun doForceRefresh(): SourceListResult<ProductHunt> = archiveRepo.forceRefresh()

    /**
     * 翻译产品(name + tagline 整体),列表页单按钮触发。
     * 合并为一段文本一次翻译;两者都为空时不翻译。
     */
    fun translateProduct(product: ProductHunt) {
        val key = product.slug.ifBlank { product.id }
        val text = buildString {
            if (product.name.isNotBlank()) append(product.name.trim())
            if (product.tagline.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(product.tagline.trim())
            }
        }
        translateSupport.translate(viewModelScope, key, text)
    }
}
