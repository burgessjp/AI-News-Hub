package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.AppException
import com.peng.ainewshub.data.HuggingFacePaper
import com.peng.ainewshub.data.HuggingFacePapersResult
import org.json.JSONObject

/**
 * HuggingFace Trending Papers 的 [gitcode 归档]数据源实现。
 *
 * 与 [com.peng.ainewshub.data.HuggingFacePapersRepository](实时)并列,实现同一
 * [HuggingFacePapersSource] 接口。数据来自 GitHub Action 每天 08:00 归档的快照。
 *
 * 字段映射对齐 docs/news-hub-data-usage.md 的 huggingface-papers items 表。
 * 无缓存概念:fetch == forceRefresh。失败抛 RuntimeException 交由 VM 显示 Error。
 */
class HuggingFacePapersArchiveRepository : HuggingFacePapersSource {

    override suspend fun fetch(): HuggingFacePapersResult = load()

    override suspend fun forceRefresh(): HuggingFacePapersResult = load()

    private suspend fun load(): HuggingFacePapersResult {
        val snapshot = ArchiveHttpClient.fetchLatestSnapshot(SOURCE_KEY)
        val fetchedAt = snapshot.optLong("fetched_at_ms", System.currentTimeMillis())
        val items = snapshot.optJSONArray("items")
            ?: throw AppException.NoData()
        val papers = (0 until items.length()).mapNotNull { i ->
            val obj = items.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id")
            val title = obj.optString("title")
            // id 与 title 必有(对齐 fetch_data.py:缺则跳过)
            if (id.isBlank() || title.isBlank()) return@mapNotNull null
            HuggingFacePaper(
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
        if (papers.isEmpty()) throw AppException.NoData()
        return HuggingFacePapersResult(fetchedAt, papers)
    }

    private companion object {
        const val SOURCE_KEY = "huggingface-papers"
    }
}
