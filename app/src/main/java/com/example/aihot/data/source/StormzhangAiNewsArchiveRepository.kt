package com.example.aihot.data.source

import com.example.aihot.data.StormzhangAiNews
import com.example.aihot.data.StormzhangAiNewsResult
import org.json.JSONObject

/**
 * stormzhang AI 资讯的 [gitcode 归档]数据源实现。
 *
 * 与 [com.example.aihot.data.StormzhangAiNewsRepository](实时)并列,实现同一
 * [StormzhangAiNewsSource] 接口。数据来自 GitHub Action 每天 08:00 归档的快照。
 *
 * 字段映射对齐 docs/news-hub-data-usage.md 的 stormzhang-ai items 表;
 * 顶层 pageDate(页面声明的资讯日期)一并取回,填入 [StormzhangAiNewsResult.pageDate]。
 * 无缓存概念:fetch == forceRefresh。失败抛 RuntimeException 交由 VM 显示 Error。
 */
class StormzhangAiNewsArchiveRepository : StormzhangAiNewsSource {

    override suspend fun fetch(): StormzhangAiNewsResult = load()

    override suspend fun forceRefresh(): StormzhangAiNewsResult = load()

    private suspend fun load(): StormzhangAiNewsResult {
        val snapshot = ArchiveHttpClient.fetchLatestSnapshot(SOURCE_KEY)
        val fetchedAt = snapshot.optLong("fetched_at_ms", System.currentTimeMillis())
        val pageDate = snapshot.optString("pageDate")
        val items = snapshot.optJSONArray("items")
            ?: throw RuntimeException("归档 stormzhang-ai 快照无 items")
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
        if (news.isEmpty()) throw RuntimeException("归档暂无 stormzhang-ai 数据")
        return StormzhangAiNewsResult(fetchedAt, news, pageDate)
    }

    private companion object {
        const val SOURCE_KEY = "stormzhang-ai"
    }
}
