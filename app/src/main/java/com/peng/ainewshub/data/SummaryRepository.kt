package com.peng.ainewshub.data

import android.content.Context
import androidx.annotation.StringRes
import com.peng.ainewshub.R
import org.json.JSONObject
import com.peng.ainewshub.data.source.ArchiveHttpClient

/**
 * 单个归档源的 AI 摘要结果 —— 摘要正文 + 该源快照落盘时刻(用于 UI 标注数据新鲜度)。
 *
 * [fetchedAtMs] 直接取自归档快照的 `fetched_at_ms`(北京时间每日 06:00/14:00 附近),
 * 与「摘要生成时刻」区分:用户关心的是「这份数据是哪天的」,而非「AI 何时跑的」。
 */
data class SourceSummary(
    val content: SummaryContent,
    val fetchedAtMs: Long
)

/**
 * 摘要正文。兼容两种数据格式:
 * - [Structured]:新格式 `ai_summary_v2`(JSON 数组,每项含 title + desc + url),数据流水线只产出这种;
 * - [Plain]:旧格式 `ai_summary`(纯文本 `• **标题**：描述` 串),仅历史快照存在,作回退。
 */
sealed interface SummaryContent {
    /** v2:结构化条目列表。 */
    data class Structured(val items: List<SummaryItem>) : SummaryContent
    /** v1:整段纯文本(含 markdown 加粗与 bullet)。 */
    data class Plain(val text: String) : SummaryContent
}

/**
 * v2 摘要的单个条目:title 为加粗导语,desc 为 2-3 句正文。
 *
 * [url] 为该条对应的原始条目链接(流水线按 AI 返回的 ref 编号回填,见
 * scripts/ai_summary.py);为空串(历史快照 / ref 无效)时 UI 不可点。
 */
data class SummaryItem(
    val title: String,
    val desc: String,
    val url: String = ""
)

/**
 * 各归档源的展示元信息 —— 摘要卡片的标题。
 *
 * 摘要正文本身不再由 App 生成,而是直接读数据流水线
 * ([scripts/ai_summary.py](../../../../../../scripts/ai_summary.py))在抓取时
 * 写入快照顶层的 `ai_summary_v2` 字段(JSON 数组);历史快照仅有旧 `ai_summary`
 * 纯文本,作回退。这里只保留 key / title 用于 UI 展示与反查。
 */
private enum class SummarySource(val key: String, @StringRes val titleRes: Int) {
    HACKERNEWS(SourceKeys.HACKERNEWS, R.string.source_title_hackernews),
    GITHUB_TRENDING(SourceKeys.GITHUB_TRENDING, R.string.source_title_github_trending),
    HUGGINGFACE_PAPERS(SourceKeys.HUGGINGFACE_PAPERS, R.string.source_title_huggingface),
    STORMZHANG_AI(SourceKeys.STORMZHANG_AI, R.string.source_title_stormzhang),
    PRODUCTHUNT(SourceKeys.PRODUCTHUNT, R.string.source_title_producthunt),
    RUNDOWN_AI(SourceKeys.RUNDOWN_AI, R.string.source_title_rundown),
    OPENAI_ANTHROPIC_NEWS(SourceKeys.OPENAI_ANTHROPIC_NEWS, R.string.source_title_openai_anthropic),
    AIHOT_FEATURED(SourceKeys.AIHOT_FEATURED, R.string.source_title_aihot_featured);

    companion object {
        /** 按归档源的 key 反查枚举;未知 key 返回 null。 */
        fun fromKey(key: String): SummarySource? = entries.firstOrNull { it.key == key }
    }
}

/**
 * AI 摘要 Repository —— 读取各归档源当日快照里预生成的 `ai_summary_v2` 字段。
 *
 * 摘要由数据流水线在抓取时生成(OpenAI 兼容调用 + 8 个针对各源定制的 system prompt,
 * 实现见 `scripts/ai_summary.py`),写入快照顶层 `ai_summary_v2`(JSON 数组,每项含
 * title + desc + url)。App 端不再运行时调用 AI API,直接读字段即可 —— 快照本身就是缓存
 * (gitcode CDN + [ArchiveHttpClient] 的 index.json 2 分钟 TTL),无需额外的本地缓存或锁。
 *
 * - 数据始终取自 gitcode 归档([ArchiveHttpClient] 每日快照),与全局
 *   [com.peng.ainewshub.data.source.SourceMode] 无关 —— 归档数据稳定、代表「今日」,
 *   适合做每日摘要;实时源波动大、用户可直接看列表。
 * - 「历史摘要」经 [summarizeOn] / [availableDates] 走 index.json 的 `history` 索引
 *   按日期寻址(流水线每源仅保留最近 31 天),同样与 SourceMode 无关。
 * - 同时兼容两种格式:优先读结构化 `ai_summary_v2`,缺失则回退旧纯文本 `ai_summary`
 *   (仅历史快照);两者都缺失(当天流水线 AI 调用失败 / 源不支持)时返回失败,UI 显示「暂无摘要 + 重试」。
 * - 不再依赖任何 AI 服务配置:无 baseUrl/apiKey/model 依赖,无需 [AiConfigStore]。
 */
class SummaryRepository {

    /**
     * 读取某归档源当日的 AI 摘要。返回 [Result]:成功为 [SourceSummary](含正文与快照时刻),
     * 失败为异常(归档拉取失败 / ai_summary 缺失)。
     *
     * @param source 归档源 key(hackernews / github-trending / huggingface-papers /
     * stormzhang-ai / producthunt / rundown-ai / openai-anthropic-news / aihot-featured)
     * @param force true 绕过 index.json 2 分钟缓存(摘要 Tab 下拉刷新)
     */
    suspend fun summarize(source: String, force: Boolean = false): Result<SourceSummary> {
        val src = SummarySource.fromKey(source)
            ?: return Result.failure(IllegalArgumentException("未知源:$source"))

        // 拉归档快照(fetchLatestSnapshot 内部已切 IO,返回整个顶层 JSONObject,含 ai_summary_v2 / ai_summary)
        val snapshot = runCatching { ArchiveHttpClient.fetchLatestSnapshot(src.key, force) }
            .getOrElse { return Result.failure(it) }

        return parseSummary(snapshot).map { content ->
            SourceSummary(content = content, fetchedAtMs = snapshot.optLong("fetched_at_ms", 0L))
        }
    }

    /**
     * 读取某归档源指定日期的 AI 摘要(「更多 → 历史摘要」用)。
     *
     * @param source 归档源 key
     * @param date 北京日期,形如 2026-07-19;经 index.json 的 history 索引寻址,
     * 该日期无数据(源起步晚 / 当天抓取失败)或 ai_summary 缺失时返回失败
     */
    suspend fun summarizeOn(source: String, date: String): Result<SourceSummary> {
        val src = SummarySource.fromKey(source)
            ?: return Result.failure(IllegalArgumentException("未知源:$source"))

        val history = runCatching { ArchiveHttpClient.fetchHistory() }
            .getOrElse { return Result.failure(it) }
        val relPath = history[src.key]?.get(date)
            ?: return Result.failure(AppException.NoData())

        val snapshot = runCatching { ArchiveHttpClient.fetchSnapshot(src.key, relPath) }
            .getOrElse { return Result.failure(it) }

        return parseSummary(snapshot).map { content ->
            SourceSummary(content = content, fetchedAtMs = snapshot.optLong("fetched_at_ms", 0L))
        }
    }

    /**
     * 从归档快照顶层解析摘要正文(兼容两种格式):
     * - 优先读 `ai_summary_v2`(JSON 数组,每项 title + desc + url)→ [SummaryContent.Structured];
     * - 回退读 `ai_summary`(纯文本)→ [SummaryContent.Plain];
     * - 都缺失或为空 → 失败 [AppException.NoData]。
     */
    private fun parseSummary(snapshot: JSONObject): Result<SummaryContent> {
        // 优先 v2 结构化数组
        val v2 = snapshot.optJSONArray("ai_summary_v2")
        if (v2 != null && v2.length() > 0) {
            val items = (0 until v2.length()).mapNotNull { i ->
                val obj = v2.optJSONObject(i) ?: return@mapNotNull null
                val title = obj.optString("title").orEmpty().trim()
                val desc = obj.optString("desc").orEmpty().trim()
                val url = obj.optString("url").orEmpty().trim()
                if (title.isNotEmpty() && desc.isNotEmpty()) SummaryItem(title, desc, url) else null
            }
            if (items.isNotEmpty()) {
                return Result.success(SummaryContent.Structured(items))
            }
        }
        // 回退 v1 纯文本(仅历史快照存在)
        val text = snapshot.optString("ai_summary").orEmpty().trim()
        if (text.isNotBlank()) {
            return Result.success(SummaryContent.Plain(text))
        }
        return Result.failure(AppException.NoData())
    }

    /**
     * 「历史摘要」可选日期列表:全源 history 索引的日期并集按倒序,
     * 每项为 (日期, 当天有归档数据的源数量)。history 索引为空时返回空列表。
     */
    suspend fun availableDates(): List<Pair<String, Int>> {
        val history = ArchiveHttpClient.fetchHistory()
        val counts = mutableMapOf<String, Int>()
        SOURCE_KEYS.forEach { key ->
            history[key]?.keys?.forEach { date ->
                counts[date] = (counts[date] ?: 0) + 1
            }
        }
        return counts.entries.sortedByDescending { it.key }.map { it.key to it.value }
    }

    companion object {
        /**
         * 8 个支持的归档源 key(全集),供 OverviewRepository 等需要遍历所有归档源的场合。
         *
         * 顺序即全 App 默认源顺序(对齐 [com.peng.ainewshub.ui.more.DEFAULT_SOURCE_ORDER]):
         * HackerNews → GitHub Trending → OpenAI×Anthropic → HuggingFace Papers →
         * Product Hunt → The Rundown AI → AIHot 精选 → stormzhang AI。
         *
         * **注意**:摘要 Tab 的实际展示顺序跟随用户在「信息源」页拖拽自定义的顺序
         * (见 [com.peng.ainewshub.ui.more.SettingsStore.sourceOrderFlow]),
         * 应经 [orderedSourceKeys] 获取,不要直接用本常量当展示顺序。
         */
        val SOURCE_KEYS = listOf(
            SummarySource.HACKERNEWS.key,
            SummarySource.GITHUB_TRENDING.key,
            SummarySource.OPENAI_ANTHROPIC_NEWS.key,
            SummarySource.HUGGINGFACE_PAPERS.key,
            SummarySource.PRODUCTHUNT.key,
            SummarySource.RUNDOWN_AI.key,
            SummarySource.AIHOT_FEATURED.key,
            SummarySource.STORMZHANG_AI.key
        )

        /** 源 key → 展示标题(按当前语言取词;未知 key 回退原始 key)。 */
        fun titleOf(context: Context, source: String): String =
            SummarySource.fromKey(source)?.let { context.getString(it.titleRes) } ?: source
    }
}
