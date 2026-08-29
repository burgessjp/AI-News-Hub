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
 * @param display 展示形(映射表指定形、语料最常见写法,或 AI 精修命名,可为中文)
 * @param total 窗口期总命中次数(按「条目命中」计)
 * @param daysActive 窗口期活跃天数(当日命中 ≥1 即活跃)
 * @param daily 与 [TrendsDigest.days] 对齐的每日命中序列(sparkline 数据源)
 * @param trend 涨跌标记:"up" / "down" / "flat"(近 3 日命中和 vs 前 3 日)
 * @param rankChange 排名变化(较昨日最后一期榜单):正 = 上升 N 名、0 = 持平、
 *   负 = 下降;流水线无历史基准(首期运行 / 基准归档缺失)时不输出该字段,
 *   此处为 null(UI 不显示标记)
 * @param isNewEntry 新上榜标记(昨日最后一期不在榜);与 rankChange 互斥
 * @param items ≤3 条代表条目(日期新的优先,源尽量多样)
 */
data class TrendKeyword(
    val term: String,
    val display: String,
    val total: Int,
    val daysActive: Int,
    val daily: List<Int>,
    val trend: String,
    val rankChange: Int?,
    val isNewEntry: Boolean,
    val items: List<TrendItem>
)

/**
 * 词云候选词 —— `trends_cloud.json` 的轻量词条(趋势词云页数据源)。
 *
 * @param term 归一化 canonical key(如 "gpt-5")
 * @param display 展示形(映射表指定形或语料最常见写法;词云恒为纯统计产出,
 *   display 不会是 AI 精修命名)
 * @param total 窗口期总命中次数(词云字号权重)
 */
data class TrendCloudWord(
    val term: String,
    val display: String,
    val total: Int
)

/**
 * 趋势词云结果 —— 根级独立文件 `trends_cloud.json`(专用数据文件,与热词榜
 * `trends.json` 同批生成、互不依赖;不进按日归档,无历史回看)。
 *
 * @param words 词云候选词(纯统计 top ~60,按流水线动量分值序)
 * @param generatedAt 流水线生成时刻(毫秒)
 * @param windowDays 统计窗口天数(与热词榜一致,当前 14)
 * @param days 窗口内日历日期序列(caption 取末位作「数据截至」)
 */
data class TrendsCloudDigest(
    val words: List<TrendCloudWord>,
    val generatedAt: Long,
    val windowDays: Int,
    val days: List<String>
)

/**
 * 热词趋势榜结果。
 *
 * @param keywords 热词榜(流水线按动量加权分排序,≤10 个)
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
 * (`scripts/trend_keywords.py`,统计为主 + 每批至多一次 AI 精修,失败回退纯
 * 统计)在 push 阶段扫近 14 天快照做词频统计,把结果写进数据仓库根级独立
 * 文件 `trends.json`(内容与原 index.json 内联 `latest_trends` 字段同构),
 * 本 Repository 只负责拉取并反序列化为 [TrendsDigest]。
 *
 * 设计要点:
 *  - 输入 [ArchiveHttpClient.fetchLatestTrends] 走 trends.json 的独立 2 分钟
 *    缓存(与 index 互不影响);
 *  - 文件缺失(404)或 keywords 为空 → 抛 [AppException.NoData](UI 走空态,
 *    语义是「趋势尚未生成」——write_trends 失败的批次会暂缺文件,下次批次自愈);
 *  - 网络/解析失败 → 抛 [AppException.Network]/[AppException.ServerError](UI 走错误态);
 *  - 历史热词:「更多 → 历史热词」经 [availableDates] / [loadDigestOn] 按
 *    `trends_history.json` 索引寻址逐日归档(同 [OverviewRepository.loadDigestOn] 范式)。
 */
class TrendsRepository {

    companion object {
        /** 趋势归档目录名(索引 trends_history 指针相对该目录寻址,数据仓库同名目录)。 */
        const val ARCHIVE_DIR = "trends"
    }

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

    /**
     * 可选日期列表(「历史热词」日期列表页):trends_history 索引键,倒序。
     * 索引缺失/为空返回空列表(UI 走空态)。
     */
    suspend fun availableDates(): List<String> =
        ArchiveHttpClient.fetchTrendsHistory().keys.sortedDescending()

    /**
     * 加载指定日期的历史热词榜。
     *
     * 经根级独立索引 `trends_history.json` 按日期寻址,归档文件内容与当期
     * `trends.json` 完全同构(同一对象两处落盘),复用 [parseTrends] 反序列化
     * (含 rankChange / isNewEntry,历史榜单同样带排名变化标记)。
     *
     * @param date YYYY-MM-DD(北京时间)
     * @return 成功为 [TrendsDigest];日期不在索引/归档缺失 → [AppException.NoData]
     */
    suspend fun loadDigestOn(date: String): Result<TrendsDigest> = runCatching {
        val relPath = ArchiveHttpClient.fetchTrendsHistory()[date]
            ?: throw AppException.NoData()
        parseTrends(ArchiveHttpClient.fetchSnapshot(ARCHIVE_DIR, relPath, "keywords"))
    }

    /** 反序列化 trends JSON 为 [TrendsDigest]。keywords 为空视为数据无效抛 [AppException.NoData]。 */
    private suspend fun parseTrends(json: JSONObject): TrendsDigest = withContext(Dispatchers.IO) {
        val days = json.optJSONArray("days").asStringList()
        val keywords = parseKeywords(json.optJSONArray("keywords"), days)
        if (keywords.isEmpty()) throw AppException.NoData()
        TrendsDigest(
            keywords = keywords,
            generatedAt = json.optLong("generatedAt", 0L),
            windowDays = json.optInt("windowDays", days.size),
            days = days
        )
    }

    /**
     * 加载趋势词云(「趋势词云」二级页)。
     *
     * 读根级独立文件 `trends_cloud.json`(独立 2 分钟缓存,未进词云页不下载)。
     * 文件缺失(404,尚未生成)或 words 全部无效 → [AppException.NoData]
     * (空态语义,下次批次自愈);网络/解析失败为对应异常。
     */
    suspend fun loadCloud(): Result<TrendsCloudDigest> = runCatching {
        val json = ArchiveHttpClient.fetchTrendsCloud()
            ?: throw AppException.NoData()
        parseCloudDigest(json)
    }

    /** 反序列化 trends_cloud JSON 为 [TrendsCloudDigest]。words 为空视为数据无效抛 [AppException.NoData]。 */
    private suspend fun parseCloudDigest(json: JSONObject): TrendsCloudDigest = withContext(Dispatchers.IO) {
        val words = parseCloudWords(json.optJSONArray("words"))
        if (words.isEmpty()) throw AppException.NoData()
        TrendsCloudDigest(
            words = words,
            generatedAt = json.optLong("generatedAt", 0L),
            windowDays = json.optInt("windowDays", 1),
            days = json.optJSONArray("days").asStringList()
        )
    }

    /** 解析 words 数组,过滤掉 display 为空的项。 */
    private fun parseCloudWords(arr: JSONArray?): List<TrendCloudWord> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            TrendCloudWord(
                term = o.optString("term"),
                display = o.optString("display"),
                total = o.optInt("total")
            ).takeIf { it.display.isNotBlank() }
        }
    }

    /**
     * 解析 keywords 数组,过滤掉 display 为空或 daily 序列无效的项。
     * daily 长度须与窗口 days 对齐(sparkline 按下标画,错位的词条直接过滤,
     * 防流水线口径变化时折线与窗口悄然错位)。
     */
    private fun parseKeywords(arr: JSONArray?, days: List<String>): List<TrendKeyword> {
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
                // rankChange 仅在流水线有历史基准时输出(has 判空,旧格式天然兼容)
                rankChange = if (o.has("rankChange")) o.optInt("rankChange") else null,
                isNewEntry = o.optBoolean("isNewEntry", false),
                items = parseItems(o.optJSONArray("items"))
            ).takeIf { it.display.isNotBlank() && it.daily.isNotEmpty() && it.daily.size == days.size }
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
}
