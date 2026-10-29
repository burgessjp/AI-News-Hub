package com.peng.ainewshub.data.repo

import com.peng.ainewshub.data.AppException
import com.peng.ainewshub.data.source.ArchiveHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 周刊精选条目 —— 流水线从上周 overview 归档回填的完整条目(防幻觉:字段
 * 逐字来自归档原文,AI 只负责选与评)。
 *
 * @param title 条目标题(归档原文)
 * @param url 点击落地页(内置 WebView)
 * @param source 归档源 key(供 UI 取源名徽章)
 * @param date 条目所在 overview 归档日的北京日期(yyyy-MM-dd,「哪天上的榜」)
 * @param metrics 原始热度指标文案(如「得分 319 · 评论 136」)
 * @param comment 日报 AI 的一句话点评(可为空串,UI 不渲染)
 * @param breaking 本周曾被标突发(任一日在榜日命中即真)
 * @param daysSeen 在榜天数(连续主线信号)
 */
data class WeeklyIssueItem(
    val title: String,
    val url: String,
    val source: String,
    val date: String,
    val metrics: String,
    val comment: String,
    val breaking: Boolean,
    val daysSeen: Int
)

/**
 * 周刊主题板块 —— AI 按本周素材成形的主题线。
 *
 * @param title 板块标题(AI 生成,≤12 字名词性短语)
 * @param summary 板块短评(AI 生成;空串时 UI 不渲染该段)
 * @param items 精选条目(3-6 条,全刊 url 唯一)
 */
data class WeeklySection(
    val title: String,
    val summary: String,
    val items: List<WeeklyIssueItem>
)

/**
 * 一期周刊 —— 根级 `weekly.json` / `weekly/<weekStart>/` 归档的同构内容
 * (流水线 `scripts/weekly_digest.py` 产出,App 只读反序列化)。
 *
 * @param weekStart 周一日期(yyyy-MM-dd,归档目录名与索引键)
 * @param weekEnd 周日日期(冗余输出,供刊头直接显示区间)
 * @param coverageDays 本期实际覆盖的归档天数(<7 时 UI 注记「本期覆盖 N 天数据」)
 * @param issueNo 期号(流水线自纪元周确定性推算)
 * @param generatedAt 流水线生成时刻(毫秒)
 * @param digest 本周总综述(AI 生成;空串时 UI 不渲染 Hero)
 * @param sections 主题板块(3-6 个)
 */
data class WeeklyDigest(
    val weekStart: String,
    val weekEnd: String,
    val coverageDays: Int,
    val issueNo: Int,
    val generatedAt: Long,
    val digest: String,
    val sections: List<WeeklySection>
)

/**
 * 周刊 Repository —— 「AI 周刊」二级页(Today 页入口行进入)的数据源。
 *
 * 与 [TrendsRepository] 同范式:**流水线预生成、App 只读归档**。流水线在
 * push 阶段生成上周刊(`weekly_digest.py`,周一首批触发、幂等),产物三件套
 * (根级 `weekly.json` 最新期 + `weekly/<weekStart>/` 按周归档 +
 * `weekly_history.json` 索引);本 Repository 只负责拉取与反序列化。
 *
 * 设计要点:
 *  - 最新期走 [ArchiveHttpClient.fetchLatestWeekly] 的独立 2 分钟缓存
 *    (Today 入口行的数据源);文件「成功才写」,404 是正常暂态 → NoData
 *    (UI 语义:「周刊尚未生成」,Today 入口行随之隐藏);
 *  - 往期经 [availableIssues] / [loadIssue] 按 `weekly_history.json` 索引
 *    寻址逐期归档(快照按路径不可变缓存,期次切换零重复下载);
 *  - 解析口径:title/url 空的条目丢弃、title 空的板块丢弃、summary 空保留
 *    板块;sections 整体为空 → [AppException.NoData]。
 */
class WeeklyRepository {

    companion object {
        /** 周刊归档目录名(索引指针相对该目录寻址,数据仓库同名目录)。 */
        const val ARCHIVE_DIR = "weekly"

        /**
         * 反序列化周刊 JSON 为 [WeeklyDigest](latest 与往期归档同构,共用)。
         * sections 解析后为空视为数据无效抛 [AppException.NoData]。
         */
        internal fun parseDigest(json: JSONObject): WeeklyDigest {
            val sections = parseSections(json.optJSONArray("sections"))
            if (sections.isEmpty()) throw AppException.NoData()
            return WeeklyDigest(
                weekStart = json.optString("weekStart"),
                weekEnd = json.optString("weekEnd"),
                coverageDays = json.optInt("coverageDays", 7),
                issueNo = json.optInt("issueNo", 0),
                generatedAt = json.optLong("generatedAt", 0L),
                digest = json.optString("digest"),
                sections = sections
            )
        }

        /** 解析板块数组:空标题板块丢弃;条目经 [parseItems] 过滤后为空的板块丢弃。 */
        private fun parseSections(arr: JSONArray?): List<WeeklySection> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val items = parseItems(o.optJSONArray("items"))
                WeeklySection(
                    title = o.optString("title"),
                    summary = o.optString("summary"),
                    items = items
                ).takeIf { it.title.isNotBlank() && it.items.isNotEmpty() }
            }
        }

        /** 解析条目数组,过滤掉标题/URL 为空的项。 */
        private fun parseItems(arr: JSONArray?): List<WeeklyIssueItem> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                WeeklyIssueItem(
                    title = o.optString("title"),
                    url = o.optString("url"),
                    source = o.optString("source"),
                    date = o.optString("date"),
                    metrics = o.optString("metrics"),
                    comment = o.optString("comment"),
                    breaking = o.optBoolean("breaking", false),
                    daysSeen = o.optInt("daysSeen", 1)
                ).takeIf { it.title.isNotBlank() && it.url.isNotBlank() }
            }
        }
    }

    /**
     * 加载最新一期。**聚合包优先**:先取根级 `today.json`(「今天」页单请求载体,
     * 与 Overview/Summary 两 Repository 共享一次网络请求)内原样内嵌的 `weekly`
     * 键;包缺失(404)/包内无周刊键(缺刊批次)时回退根级 `weekly.json` 路径。
     *
     * @param force true 绕过 2 分钟缓存(Today 页下拉刷新联动)
     * @return 成功为 [WeeklyDigest];文件缺失(尚未生成)/空刊 → [AppException.NoData]
     */
    suspend fun loadLatest(force: Boolean = false): Result<WeeklyDigest> = runCatching {
        val json = ArchiveHttpClient.fetchTodayBundle(force)?.optJSONObject("weekly")
            ?: ArchiveHttpClient.fetchLatestWeekly(force)
            ?: throw AppException.NoData()
        parseDigest(json)
    }

    /**
     * 可选期次列表(周刊页期次条):weekly_history 索引键(周一日期),倒序。
     * 索引缺失/为空返回空列表(UI 空态)。
     */
    suspend fun availableIssues(): List<String> =
        ArchiveHttpClient.fetchWeeklyHistory().keys.sortedDescending()

    /**
     * 加载指定期次的周刊(经 weekly_history 索引寻址按周归档)。
     *
     * @param weekStart 周一日期(yyyy-MM-DD)
     * @return 成功为 [WeeklyDigest];期次不在索引/归档缺失 → [AppException.NoData]
     */
    suspend fun loadIssue(weekStart: String): Result<WeeklyDigest> = runCatching {
        val relPath = ArchiveHttpClient.fetchWeeklyHistory()[weekStart]
            ?: throw AppException.NoData()
        parseDigest(ArchiveHttpClient.fetchSnapshot(ARCHIVE_DIR, relPath, "sections"))
    }
}
