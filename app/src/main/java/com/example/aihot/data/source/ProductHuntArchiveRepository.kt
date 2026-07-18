package com.example.aihot.data.source

import com.example.aihot.data.ProductHunt
import org.json.JSONObject

/**
 * Product Hunt 的 [gitcode 归档]数据源实现。
 *
 * 与 [com.example.aihot.data.source.HuggingFacePapersArchiveRepository](实时)并列,
 * 实现同一 [ProductHuntSource] 接口。数据来自数据流水线
 * ([scripts/fetch_data.py] 每天 06:00/14:00 经 PH GraphQL 抓取归档)的快照。
 *
 * 字段映射对齐 docs/news-hub-data-usage.md 的 producthunt items 表
 * 与 [com.example.aihot.data.ProductHunt.fromJson]。
 * 无缓存概念:fetch == forceRefresh。失败抛 RuntimeException 交由 VM 显示 Error。
 */
class ProductHuntArchiveRepository : ProductHuntSource {

    override suspend fun fetch(): ProductHuntResult = load()

    override suspend fun forceRefresh(): ProductHuntResult = load()

    private suspend fun load(): ProductHuntResult {
        val snapshot = ArchiveHttpClient.fetchLatestSnapshot(SOURCE_KEY)
        val fetchedAt = snapshot.optLong("fetched_at_ms", System.currentTimeMillis())
        val items = snapshot.optJSONArray("items")
            ?: throw RuntimeException("归档 producthunt 快照无 items")
        val products = (0 until items.length()).mapNotNull { i ->
            val obj = items.optJSONObject(i) ?: return@mapNotNull null
            ProductHunt.fromJson(obj, fallbackRank = i + 1)
        }
        if (products.isEmpty()) throw RuntimeException("归档暂无 producthunt 数据")
        return ProductHuntResult(fetchedAt, products)
    }

    private companion object {
        const val SOURCE_KEY = "producthunt"
    }
}
