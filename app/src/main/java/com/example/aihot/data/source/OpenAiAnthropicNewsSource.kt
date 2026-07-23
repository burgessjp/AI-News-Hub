package com.example.aihot.data.source

import com.example.aihot.data.OpenAiAnthropicNews

/**
 * OpenAI x Anthropic 厂商动态数据源抽象 ——
 * [OpenAiAnthropicNewsArchiveRepository](gitcode 归档)的接口。
 *
 * 与 [ProductHuntSource] 平行。当前只有归档实现(两家均无稳定公开 API,App 端不直连);
 * LIVE 与 ARCHIVE 都走归档,接口留作未来扩展。
 */
interface OpenAiAnthropicNewsSource {
    suspend fun fetch(): OpenAiAnthropicNewsResult
    suspend fun forceRefresh(): OpenAiAnthropicNewsResult
}

/**
 * OpenAI x Anthropic 厂商动态抓取结果(对齐 [ProductHuntResult])。
 *
 * @param fetchedAt 数据落盘时刻(归档快照的 fetched_at_ms)
 * @param articles  厂商动态列表(最新 20 条,按发布时间倒序)
 */
data class OpenAiAnthropicNewsResult(
    val fetchedAt: Long,
    val articles: List<OpenAiAnthropicNews>
)
