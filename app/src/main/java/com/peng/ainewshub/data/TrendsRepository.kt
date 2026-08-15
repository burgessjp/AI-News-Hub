package com.peng.ainewshub.data

import com.peng.ainewshub.data.source.ArchiveHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 热词代表条目——命中该热词的一条真实资讯(阅读出口)。
 *
 * @param title 条目标题(来自快照原文,中英不限)
 * @param url 点击落地页(内置 WebView)
 * @param source 归档源 key(供 UI 取源名徽章)
 * @param date 条目所在快照的北京日期(yyyy-MM-dd)
 */
data class TrendItem(
    val title: String,
    val url: String,
    val source: String,
    val date: String
)

/**
 * 单个热词的趋势数据。
 *
 * @param term 归一化 canonical key(如 "gpt-5")
 * @param display 展示形(映射表指定形,或语料中最常见的原始大小写写法)
 * @param total 窗口期总命中次数(按「条目命中」计)
 * @param daysActive 窗口期活跃天数(当日命中 ≥1 即活跃)
 * @param daily 与 [TrendsDigest.days] 对齐的每日命中序列(sparkline 数据源)
 * @param trend 涨跌标记:"up" / "down" / "flat"(近 3 日命中和 vs 前 3 日)
 * @param items ≤3 条代表条目(日期新的优先,源尽量多样)
 */
data class TrendKeyword(
    val term: String,
    val display: String,
    val total: Int,
    val daysActive: Int,
    val daily: List<Int>,
    val trend: String,
    val items: List<TrendItem>
)

/**
 * 热词趋势榜结果。
 *
 * @param keywords 热词榜(按 total 降序,≤10 个)
 * @param generatedAt 流水线生成时刻(毫秒)
 * @param windowDays 统计窗口天数(当前 14)
 * @param days 窗口内日历日期序列(yyyy-MM-dd,与各 keyword.daily 对齐;
 *   缺数据的日期命中为 0)
 */
data class TrendsDigest(
    val keywords: List<TrendKeyword>,
    val generatedAt: Long,
    val windowDays: Int,
    val days: List<String>
)

/**
 * 热词趋势 Repository —— 根 tab「趋势」的数据源。
 *
 * 与 [OverviewRepository] 同范式:**流水线预生成、App 只读归档**。流水线
 * (`scripts/trend_keywords.py`,纯统计不调 AI)在 push 阶段扫近 14 天快照做
 * 词频统计,把结果写进数据仓库根级独立文件 `trends.json`(内容与原 index
 * 内联 `latest_trends` 字段同构),本 Repository 只负责拉取并反序列化为
 * [TrendsDigest]。
 *
 * 设计要点:
 *  - 输入 [ArchiveHttpClient.fetchLatestTrends] 走 trends.json 的独立 2 分钟
 *    缓存(与 index 互不影响);
 *  - 文件缺失(null)或 keywords 为空 → 抛 [AppException.NoData](UI 走空态,
 *    语义是「趋势尚未生成」——拆分迁移前/功能上线初期即如此);
 *  - 网络/解析失败 → 抛 [AppException.Network]/[AppException.ServerError](UI 走错误态)。
 */
class TrendsRepository {

    /**
     * 加载热词趋势榜。
     *
     * @param force true 绕过 trends.json 2 分钟缓存(趋势 Tab 下拉刷新)
     * @return 成功为 [TrendsDigest];文件缺失/无热词为 [AppException.NoData];
     * 网络/解析失败为对应异常
     */
    suspend fun loadTrends(force: Boolean = false): Result<TrendsDigest> = runCatching {
        val json = ArchiveHttpClient.fetchLatestTrends(force)
            ?: throw AppException.NoData()
        parseTrends(json)
    }

    /** 反序列化 trends JSON 为 [TrendsDigest]。keywords 为空视为数据无效抛 [AppException.NoData]。 */
    private suspend fun parseTrends(json: JSONObject): TrendsDigest = withContext(Dispatchers.IO) {
        val days = readStringList(json.optJSONArray("days"))
        val keywords = parseKeywords(json.optJSONArray("keywords"))
        if (keywords.isEmpty()) throw AppException.NoData()
        TrendsDigest(
            keywords = keywords,
            generatedAt = json.optLong("generatedAt", 0L),
            windowDays = json.optInt("windowDays", days.size),
            days = days
        )
    }

    /** 解析 keywords 数组,过滤掉 display 为空或 daily 序列无效的项。 */
    private fun parseKeywords(arr: JSONArray?): List<TrendKeyword> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val dailyArr = o.optJSONArray("daily")
            val daily = if (dailyArr == null) emptyList()
            else (0 until dailyArr.length()).map { dailyArr.optInt(it) }
            TrendKeyword(
                term = o.optString("term"),
                display = o.optString("display"),
                total = o.optInt("total"),
                daysActive = o.optInt("daysActive"),
                daily = daily,
                trend = o.optString("trend", "flat"),
                items = parseItems(o.optJSONArray("items"))
            ).takeIf { it.display.isNotBlank() && it.daily.isNotEmpty() }
        }
    }

    /** 解析代表条目数组,过滤掉标题/URL 为空的项。 */
    private fun parseItems(arr: JSONArray?): List<TrendItem> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            TrendItem(
                title = o.optString("title"),
                url = o.optString("url"),
                source = o.optString("source"),
                date = o.optString("date")
            ).takeIf { it.title.isNotBlank() && it.url.isNotBlank() }
        }
    }

    private fun readStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }
}
