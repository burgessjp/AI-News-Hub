package com.peng.ainewshub.data

import android.content.Context
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
 * @param missingSources 本次未能加载的源 key(页脚标注)
 */
data class OverviewDigest(
    val items: List<OverviewEntry>,
    val generatedAt: Long,
    val dataFetchedAt: Long,
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
class OverviewRepository(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 加载今日总览。
     *
     * @param force 保留参数兼容现有调用方(刷新按钮);归档只读模式下 force 失去原强制
     * 重新生成的语义,仅触发一次网络重读(归档 2 分钟缓存仍生效)
     * @return 成功为 [OverviewDigest];失败为 [AppException.NoData](字段缺失)/
     * 网络/解析失败
     */
    suspend fun loadDigest(force: Boolean = false): Result<OverviewDigest> = runCatching {
        val json = ArchiveHttpClient.fetchLatestOverview()
            ?: throw AppException.NoData()
        parseDigest(json)
    }

    /** 反序列化 latest_overview JSON 为 [OverviewDigest]。items 为空视为数据无效抛 [AppException.NoData]。 */
    private suspend fun parseDigest(json: JSONObject): OverviewDigest = withContext(Dispatchers.IO) {
        val items = parseEntries(json.optJSONArray("items"))
        if (items.isEmpty()) throw AppException.NoData()
        OverviewDigest(
            items = items,
            generatedAt = json.optLong("generatedAt", 0L),
            dataFetchedAt = json.optLong("dataFetchedAt", 0L),
            missingSources = readStringList(json.optJSONArray("missingSources"))
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

    private fun readStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }
}
