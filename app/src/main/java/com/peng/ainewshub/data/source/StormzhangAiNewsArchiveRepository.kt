package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.AppException
import com.peng.ainewshub.data.SearchIndexRepository
import com.peng.ainewshub.data.SourceKeys
import com.peng.ainewshub.data.StormzhangAiNews
import com.peng.ainewshub.data.StormzhangAiNewsResult
import org.json.JSONObject

/**
 * stormzhang AI 资讯的 [gitcode 归档]数据源。
 *
 * 数据来自数据流水线归档的快照。字段映射对齐 docs/news-hub-data-usage.md 的
 * stormzhang-ai items 表;顶层 pageDate(页面声明的资讯日期)一并取回,
 * 填入 [StormzhangAiNewsResult.pageDate]。无缓存概念:fetch == forceRefresh。
 * 失败抛 RuntimeException 交由 VM 显示 Error。
 */
class StormzhangAiNewsArchiveRepository {

    suspend fun fetch(): StormzhangAiNewsResult = load()

    suspend fun forceRefresh(): StormzhangAiNewsResult = load()

    private suspend fun load(): StormzhangAiNewsResult {
        val snapshot = ArchiveHttpClient.fetchLatestSnapshot(SourceKeys.STORMZHANG_AI)
        val fetchedAt = snapshot.optLong("fetched_at_ms", System.currentTimeMillis())
        val pageDate = snapshot.optString("pageDate")
        val items = snapshot.optJSONArray("items")
            ?: throw AppException.NoData()
        val news = (0 until items.length()).mapNotNull { i ->
            val obj = items.optJSONObject(i) ?: return@mapNotNull null
            val url = obj.optString("url")
            val summary = obj.optString("summary")
            // url 与 summary 是必有字段(对齐 fetch_data.py 解析逻辑:缺则跳过)
            if (url.isBlank() || summary.isBlank()) return@mapNotNull null
            StormzhangAiNews(
                rank = obj.optInt("rank", i + 1),
                url = url,
                summary = summary,
                english = obj.optString("english"),
                source = obj.optString("source"),
                time = obj.optString("time")
            )
        }
        if (news.isEmpty()) throw AppException.NoData()
        // 本地搜索索引回填:该源无标题字段,summary 即主文本(列表页也拿它当标题传
        // openUrl),english 副文本作可搜索摘要
        SearchIndexRepository.index(
            news.map {
                SearchIndexRepository.SearchDoc(it.url, it.summary, it.english, SourceKeys.STORMZHANG_AI)
            }
        )
        return StormzhangAiNewsResult(fetchedAt, news, pageDate)
    }
}
