package com.example.aihot.data.source

import com.example.aihot.data.RundownAiArticle

/**
 * The Rundown AI 的 gitcode 归档数据源实现。
 *
 * 与 [RundownAiRepository](实时)并列,实现同一 [RundownAiSource] 接口。
 * 数据来自数据流水线([scripts/fetch_data.py] 每天 06:00/14:00 经首页 HTML 抓取归档)的快照。
 *
 * 字段映射对齐 docs/news-hub-data-usage.md 的 rundown-ai items 表
 * 与 [com.example.aihot.data.RundownAiArticle.fromJson]。
 * 无缓存概念:fetch == forceRefresh。失败抛 RuntimeException 交由 VM 显示 Error。
 */
class RundownAiArchiveRepository : RundownAiSource {

    override suspend fun fetch(): RundownAiResult = load()

    override suspend fun forceRefresh(): RundownAiResult = load()

    private suspend fun load(): RundownAiResult {
        val snapshot = ArchiveHttpClient.fetchLatestSnapshot(SOURCE_KEY)
        val fetchedAt = snapshot.optLong("fetched_at_ms", System.currentTimeMillis())
        val items = snapshot.optJSONArray("items")
            ?: throw RuntimeException("归档 rundown-ai 快照无 items")
        val articles = (0 until items.length()).mapNotNull { i ->
            val obj = items.optJSONObject(i) ?: return@mapNotNull null
            RundownAiArticle.fromJson(obj, fallbackRank = i + 1)
        }
        if (articles.isEmpty()) throw RuntimeException("归档暂无 rundown-ai 数据")
        return RundownAiResult(fetchedAt, articles)
    }

    private companion object {
        const val SOURCE_KEY = "rundown-ai"
    }
}
