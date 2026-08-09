package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.HuggingFacePaper
import com.peng.ainewshub.data.HuggingFacePapersResult
import com.peng.ainewshub.data.SourceKeys
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
        return HuggingFacePapersResult(fetchedAt, papers)
    }
}
