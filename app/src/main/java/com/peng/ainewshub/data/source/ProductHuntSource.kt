package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.ProductHunt

/**
 * Product Hunt 数据源抽象 —— [ProductHuntArchiveRepository](gitcode 归档)的接口。
 *
 * 与 [HuggingFacePapersSource] 平行。当前只有归档实现(Developer Token 不进 APK,
 * App 端不直连 PH GraphQL);LIVE 与 ARCHIVE 都走归档,接口留作未来扩展。
 */
interface ProductHuntSource {
    suspend fun fetch(): ProductHuntResult
    suspend fun forceRefresh(): ProductHuntResult
}

/**
 * Product Hunt 抓取结果(对齐 [com.peng.ainewshub.data.HuggingFacePapersResult])。
 *
 * @param fetchedAt 数据落盘时刻(归档快照的 fetched_at_ms)
 * @param products  当日热门产品列表
 */
data class ProductHuntResult(
    val fetchedAt: Long,
    val products: List<ProductHunt>
)

