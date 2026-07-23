package com.example.aihot.data.source

import com.example.aihot.data.AppException
import com.example.aihot.data.OpenAiAnthropicNews

/**
 * OpenAI x Anthropic 厂商动态的 [gitcode 归档]数据源实现。
 *
 * 与 [ProductHuntArchiveRepository](纯归档)并列,实现同一 [OpenAiAnthropicNewsSource] 接口。
 * 数据来自数据流水线([scripts/fetch_data.py] 每天 06:00/14:00 抓 OpenAI RSS +
 * Anthropic HTML 合并归档)的快照。
 *
 * 字段映射对齐 [com.example.aihot.data.OpenAiAnthropicNews.fromJson]。
 * 无缓存概念:fetch == forceRefresh。失败抛 RuntimeException 交由 VM 显示 Error。
 */
class OpenAiAnthropicNewsArchiveRepository : OpenAiAnthropicNewsSource {

    override suspend fun fetch(): OpenAiAnthropicNewsResult = load()

    override suspend fun forceRefresh(): OpenAiAnthropicNewsResult = load()

    private suspend fun load(): OpenAiAnthropicNewsResult {
        val snapshot = ArchiveHttpClient.fetchLatestSnapshot(SOURCE_KEY)
        val fetchedAt = snapshot.optLong("fetched_at_ms", System.currentTimeMillis())
        val items = snapshot.optJSONArray("items")
            ?: throw AppException.NoData()
        val articles = (0 until items.length()).mapNotNull { i ->
            val obj = items.optJSONObject(i) ?: return@mapNotNull null
            OpenAiAnthropicNews.fromJson(obj, fallbackRank = i + 1)
        }
        if (articles.isEmpty()) throw AppException.NoData()
        return OpenAiAnthropicNewsResult(fetchedAt, articles)
    }

    private companion object {
        const val SOURCE_KEY = "openai-anthropic-news"
    }
}
