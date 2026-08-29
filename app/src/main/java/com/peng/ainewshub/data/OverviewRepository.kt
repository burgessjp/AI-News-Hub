package com.peng.ainewshub.data

import com.peng.ainewshub.data.source.ArchiveHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 今日总览单条——标题/链接/指标由流水线直接写入(不再端侧 ref 回填),
 * AI 只产出 [comment]/[breaking]/[breakingReason]。
 *
 * @param source 归档源 key(供 UI 取源名徽章)
 * @param title 原标题(来自快照)
 * @param url 点击落地页(内置 WebView)
 * @param metrics 互动指标行(如 "得分 512 · 评论 389");无指标的源为空串
 * @param comment AI 给出的一句话(为什么重要/值得关注什么)
 * @param breaking 是否「突发重磅」(多源交叉/数据爆发/重大发布;UI 特殊样式 + 标签)
 * @param breakingReason Breaking 专属「推荐理由」(为什么是突发/影响面有多大),
 *   仅 breaking=true 时有;普通条目为空串。与 [comment] 语义区分:comment=重要性,
 *   breakingReason=突发性
 */
data class OverviewEntry(
    val source: String,
    val title: String,
    val url: String,
    val metrics: String,
    val comment: String,
    val breaking: Boolean,
    val breakingReason: String = ""
)

/**
 * 今日总览结果。
 *
 * @param items 今日热点 Top10(breaking 条目排在最前,全部条目共 ≤10 条)
 * @param generatedAt 流水线生成时刻(毫秒)
 * @param dataFetchedAt 输入快照中最新的 fetched_at_ms(「数据截至」)
 * @param digest 跨源「今日综述」(2-3 句,流水线 overview_summary.py 生成;
 *   旧 index.json 无此字段 → 空串,UI 不渲染)
 * @param missingSources 本次未能加载的源 key(页脚标注)
 */
data class OverviewDigest(
    val items: List<OverviewEntry>,
    val generatedAt: Long,
    val dataFetchedAt: Long,
    val digest: String = "",
    val missingSources: List<String>
)

/**
 * 今日总览 Repository —— 首个根 tab「总览」的数据源。
 *
 * 与 [SummaryRepository] 同范式:**流水线预生成、App 只读归档**。流水线
 * (`scripts/overview_summary.py`)在抓取后做跨源综合分析,把结果写进
 * index.json 顶层 `latest_overview` 字段(含 items + generatedAt 等),本 Repository
 * 只负责拉取该字段并反序列化为 [OverviewDigest]。
 *
 * 设计要点:
 *  - 输入 [ArchiveHttpClient.fetchLatestOverview] 复用 index.json 的 2 分钟缓存,
 *    与 latest/history 指针读取共享一次网络请求;
 *  - 字段缺失(null)→ 抛 [AppException.NoData](UI 走空态,语义是「今日尚未生成」);
 *  - 网络/解析失败 → 抛 [AppException.Network]/[AppException.ServerError](UI 走错误态)。
 */
class OverviewRepository {

    companion object {
        /** 总览归档目录名(index `overview_history` 指针相对该目录寻址,数据仓库同名目录)。 */
        const val ARCHIVE_DIR = "overview"
    }

    /**
     * 加载今日总览。
     *
     * 归档只读:经 [ArchiveHttpClient.fetchLatestOverview](复用 index.json 2 分钟缓存)
     * 拉取流水线预生成的 `latest_overview` 字段并反序列化。
     *
     * @param force true 绕过 index.json 2 分钟缓存(总览 Tab 下拉刷新)
     * @return 成功为 [OverviewDigest];失败为 [AppException.NoData](字段缺失)/
     * 网络/解析失败
     */
    suspend fun loadDigest(force: Boolean = false): Result<OverviewDigest> = runCatching {
        val json = ArchiveHttpClient.fetchLatestOverview(force)
            ?: throw AppException.NoData()
        parseDigest(json)
    }

    /**
     * 可选日期列表(「历史总览」日期列表页):index `overview_history` 索引键,倒序。
     * 索引缺失/为空返回空列表(UI 走空态)。
     */
    suspend fun availableDates(): List<String> =
        ArchiveHttpClient.fetchOverviewHistory().keys.sortedDescending()

    /**
     * 加载指定日期的历史总览。
     *
     * 经 index `overview_history` 索引按日期寻址,归档文件内容与当日 latest_overview
     * 同构(流水线逐批次落盘 + 一次性回填),复用 [parseDigest] 反序列化。
     *
     * @param date YYYY-MM-DD(北京时间)
     * @return 成功为 [OverviewDigest];日期不在索引/归档缺失 → [AppException.NoData]
     */
    suspend fun loadDigestOn(date: String): Result<OverviewDigest> = runCatching {
        val relPath = ArchiveHttpClient.fetchOverviewHistory()[date]
            ?: throw AppException.NoData()
        parseDigest(ArchiveHttpClient.fetchSnapshot(ARCHIVE_DIR, relPath))
    }

    /** 反序列化 latest_overview JSON 为 [OverviewDigest]。items 为空视为数据无效抛 [AppException.NoData]。 */
    private suspend fun parseDigest(json: JSONObject): OverviewDigest = withContext(Dispatchers.IO) {
        val items = parseEntries(json.optJSONArray("items"))
        if (items.isEmpty()) throw AppException.NoData()
        // 本地搜索索引回填:Top10/breaking 条目(原文 URL;comment 为 AI 一句话点评作可搜索摘要)。
        // 总览是默认首页,冷启动即拉取 —— 这是跨源条目进入索引的日常入口
        SearchIndexRepository.index(
            items.map { SearchIndexRepository.SearchDoc(it.url, it.title, it.comment, it.source) }
        )
        OverviewDigest(
            items = items,
            generatedAt = json.optLong("generatedAt", 0L),
            dataFetchedAt = json.optLong("dataFetchedAt", 0L),
            digest = json.optString("digest").orEmpty().trim(),
            missingSources = json.optJSONArray("missingSources").asStringList()
        )
    }

    /** 解析 items 数组为 [OverviewEntry] 列表,过滤掉标题/URL 为空的项。 */
    private fun parseEntries(arr: JSONArray?): List<OverviewEntry> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            OverviewEntry(
                source = o.optString("source"),
                title = o.optString("title"),
                url = o.optString("url"),
                metrics = o.optString("metrics"),
                comment = o.optString("comment"),
                breaking = o.optBoolean("breaking"),
                breakingReason = o.optString("breakingReason")
            ).takeIf { it.title.isNotBlank() && it.url.isNotBlank() }
        }
    }
}
