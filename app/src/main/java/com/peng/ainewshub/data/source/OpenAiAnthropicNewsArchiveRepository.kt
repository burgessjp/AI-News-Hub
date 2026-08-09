package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.OpenAiAnthropicNews
import com.peng.ainewshub.data.SourceKeys

/**
 * OpenAI x Anthropic 厂商动态的 [gitcode 归档]数据源实现。
 *
 * 与 [ProductHuntArchiveRepository](纯归档)并列,实现同一 [OpenAiAnthropicNewsSource] 接口。
 * 数据来自数据流水线([scripts/fetch_data.py] 每天 06:00/14:00 抓 OpenAI RSS +
 * Anthropic HTML 合并归档)的快照。
 *
 * 字段映射对齐 [com.peng.ainewshub.data.OpenAiAnthropicNews.fromJson]。
 * 无缓存概念:fetch == forceRefresh。失败抛 RuntimeException 交由 VM 显示 Error。
 */
class OpenAiAnthropicNewsArchiveRepository : OpenAiAnthropicNewsSource {

    override suspend fun fetch(): OpenAiAnthropicNewsResult = load()

    override suspend fun forceRefresh(): OpenAiAnthropicNewsResult = load()

    private suspend fun load(): OpenAiAnthropicNewsResult {
        val (fetchedAt, articles) = ArchiveHttpClient.fetchItemsList(SourceKeys.OPENAI_ANTHROPIC_NEWS) { obj, i ->
            OpenAiAnthropicNews.fromJson(obj, fallbackRank = i + 1)
        }
        return OpenAiAnthropicNewsResult(fetchedAt, articles)
    }
}
