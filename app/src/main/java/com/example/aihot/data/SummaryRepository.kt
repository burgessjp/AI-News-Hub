package com.example.aihot.data

import com.example.aihot.data.source.ArchiveHttpClient

/**
 * 单个归档源的 AI 摘要结果 —— 摘要正文 + 该源快照落盘时刻(用于 UI 标注数据新鲜度)。
 *
 * [fetchedAtMs] 直接取自归档快照的 `fetched_at_ms`(北京时间每日 06:00/14:00 附近),
 * 与「摘要生成时刻」区分:用户关心的是「这份数据是哪天的」,而非「AI 何时跑的」。
 */
data class SourceSummary(
    val text: String,
    val fetchedAtMs: Long
)

/**
 * 各归档源的展示元信息 —— 摘要卡片的标题。
 *
 * 摘要正文本身不再由 App 生成,而是直接读数据流水线
 * ([scripts/ai_summary.py](../../../../../../scripts/ai_summary.py))在抓取时
 * 写入快照顶层的 `ai_summary` 字段。这里只保留 key / title 用于 UI 展示与反查。
 */
private enum class SummarySource(val key: String, val title: String) {
    HACKERNEWS("hackernews", "HackerNews"),
    GITHUB_TRENDING("github-trending", "GitHub Trending"),
    HUGGINGFACE_PAPERS("huggingface-papers", "HuggingFace Papers"),
    STORMZHANG_AI("stormzhang-ai", "stormzhang AI 资讯"),
    PRODUCTHUNT("producthunt", "Product Hunt");

    companion object {
        /** 按归档源的 key 反查枚举;未知 key 返回 null。 */
        fun fromKey(key: String): SummarySource? = entries.firstOrNull { it.key == key }
    }
}

/**
 * AI 摘要 Repository —— 读取各归档源当日快照里预生成的 `ai_summary` 字段。
 *
 * 摘要由数据流水线在抓取时生成(OpenAI 兼容调用 + 4 个针对各源定制的 system prompt,
 * 实现见 `scripts/ai_summary.py`),写入快照顶层 `ai_summary`。App 端不再运行时调用
 * AI API,直接读字段即可 —— 快照本身就是缓存(gitcode CDN + [ArchiveHttpClient] 的
 * index.json 2 分钟 TTL),无需额外的本地缓存或锁。
 *
 * - 数据始终取自 gitcode 归档([ArchiveHttpClient] 每日快照),与全局
 *   [com.example.aihot.data.source.SourceMode] 无关 —— 归档数据稳定、代表「今日」,
 *   适合做每日摘要;实时源波动大、用户可直接看列表。
 * - `ai_summary` 缺失(当天流水线 AI 调用失败 / 源不支持)时返回失败,UI 显示「暂无摘要 + 重试」。
 * - 不再依赖任何 AI 服务配置:无 baseUrl/apiKey/model 依赖,无需 [AiConfigStore]。
 */
class SummaryRepository {

    /**
     * 读取某归档源当日的 AI 摘要。返回 [Result]:成功为 [SourceSummary](含正文与快照时刻),
     * 失败为异常(归档拉取失败 / ai_summary 缺失)。
     *
     * @param source 归档源 key(hackernews / github-trending / huggingface-papers / stormzhang-ai)
     */
    suspend fun summarize(source: String): Result<SourceSummary> {
        val src = SummarySource.fromKey(source)
            ?: return Result.failure(IllegalArgumentException("未知源:$source"))

        // 拉归档快照(fetchLatestSnapshot 内部已切 IO,返回整个顶层 JSONObject,含 ai_summary)
        val snapshot = runCatching { ArchiveHttpClient.fetchLatestSnapshot(src.key) }
            .getOrElse { return Result.failure(RuntimeException("归档数据拉取失败:${it.message}")) }

        val aiSummary = snapshot.optString("ai_summary").orEmpty().trim()
        if (aiSummary.isBlank()) {
            return Result.failure(RuntimeException("该源今日暂无 AI 摘要"))
        }
        val fetchedAt = snapshot.optLong("fetched_at_ms", 0L)
        return Result.success(SourceSummary(text = aiSummary, fetchedAtMs = fetchedAt))
    }

    companion object {
        /** 5 个支持的归档源 key,供 ViewModel / UI 遍历。 */
        val SOURCE_KEYS = listOf(
            SummarySource.HACKERNEWS.key,
            SummarySource.GITHUB_TRENDING.key,
            SummarySource.HUGGINGFACE_PAPERS.key,
            SummarySource.STORMZHANG_AI.key,
            SummarySource.PRODUCTHUNT.key
        )

        /** 源 key → 展示标题。 */
        fun titleOf(source: String): String =
            SummarySource.fromKey(source)?.title ?: source
    }
}
