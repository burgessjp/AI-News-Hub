package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.model.HuggingFacePaper
import com.peng.ainewshub.data.model.HuggingFacePapersResult
import com.peng.ainewshub.data.repo.SearchIndexRepository
import com.peng.ainewshub.data.source.SourceKeys

/**
 * HuggingFace Trending Papers 的 [gitcode 归档]数据源。
 *
 * 数据来自数据流水线归档的快照。字段映射对齐 docs/news-hub-data-usage.md 的
 * huggingface-papers items 表。无缓存概念:fetch == forceRefresh。
 * 失败抛 RuntimeException 交由 VM 显示 Error。
 */
class HuggingFacePapersArchiveRepository {

    suspend fun fetch(): HuggingFacePapersResult = load()

    suspend fun forceRefresh(): HuggingFacePapersResult = load()

    private suspend fun load(): HuggingFacePapersResult {
        val (fetchedAt, papers) = ArchiveHttpClient.fetchItemsList(SourceKeys.HUGGINGFACE_PAPERS) { obj, i ->
            val id = obj.optString("id")
            val title = obj.optString("title")
            // id 与 title 必有(对齐 fetch_data.py:缺则跳过)
            if (id.isBlank() || title.isBlank()) null
            else HuggingFacePaper(
                rank = obj.optInt("rank", i + 1),
                id = id,
                url = obj.optString("url"),
                title = title,
                summary = obj.optString("summary"),
                upvotes = obj.optInt("upvotes", 0),
                published = obj.optString("published"),
                authors = obj.optString("authors"),
                githubUrl = obj.optString("githubUrl")
            )
        }
        // 本地搜索索引回填
        SearchIndexRepository.index(
            papers.map {
                SearchIndexRepository.SearchDoc(it.url, it.title, it.summary, SourceKeys.HUGGINGFACE_PAPERS)
            }
        )
        return HuggingFacePapersResult(fetchedAt, papers)
    }
}
