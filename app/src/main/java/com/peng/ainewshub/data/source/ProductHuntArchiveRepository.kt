package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.model.ProductHunt
import com.peng.ainewshub.data.model.ProductHuntResult
import com.peng.ainewshub.data.repo.SearchIndexRepository
import com.peng.ainewshub.data.source.SourceKeys

/**
 * Product Hunt 的 [gitcode 归档]数据源。
 *
 * 数据来自数据流水线([scripts/fetch_data.py] 经 PH GraphQL 抓取归档,Developer
 * Token 是服务端 secret 不进 APK)的快照。字段映射对齐 docs/news-hub-data-usage.md
 * 的 producthunt items 表与 [com.peng.ainewshub.data.model.ProductHunt.fromJson]。
 * 缓存语义:fetch() 走 index 2 分钟缓存,forceRefresh() 绕过 TTL 强制重读 index
 * (源列表二级页下拉刷新);快照本体按路径不可变,无需 force。
 * 失败抛 RuntimeException 交由 VM 显示 Error。
 */
class ProductHuntArchiveRepository {

    suspend fun fetch(): ProductHuntResult = load()

    suspend fun forceRefresh(): ProductHuntResult = load(true)

    private suspend fun load(force: Boolean = false): ProductHuntResult {
        val (fetchedAt, products) = ArchiveHttpClient.fetchItemsList(SourceKeys.PRODUCTHUNT, force = force) { obj, i ->
            ProductHunt.fromJson(obj, fallbackRank = i + 1)
        }
        // 本地搜索索引回填:URL 与列表页打开的 targetUrl 一致(url 空时回退 website)
        SearchIndexRepository.index(
            products.map {
                SearchIndexRepository.SearchDoc(it.targetUrl, it.name, it.tagline, SourceKeys.PRODUCTHUNT)
            }
        )
        return ProductHuntResult(fetchedAt, products)
    }
}
