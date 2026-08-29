package com.peng.ainewshub.data.model

import androidx.compose.runtime.Immutable


import org.json.JSONObject

/**
 * The Rundown AI 单篇 newsletter(来源:https://www.therundown.ai 首页文章卡片墙)。
 *
 * 与 [ProductHunt] / [TrendingRepo] / [HuggingFacePaper] / [StormzhangAiNews] 平行:
 * 第七个独立热榜数据源。The Rundown AI 是头部英文 AI 日更 newsletter(beehiiv 托管),
 * 每日 1 篇大综合,含 1 个主事件 + 1 个 PLUS 副标题(次要工具/技巧)。首页固定展示
 * 约 16 篇近况 newsletter 卡片,App 把每篇做成一张列表卡。
 *
 * 纯归档源:数据来自 [com.peng.ainewshub.data.source.RundownAiArchiveRepository]
 * 读 gitcode 快照,App 端不直连 beehiiv。
 * 不加 @Parcelize:点击走 [url](beehiiv 文章页,内置 WebView),URL 是普通字符串,
 * 无需跨页面传整个对象(与 [StormzhangAiNews] 同套路)。
 *
 * @param rank      1 起的排名(由首页卡片顺序决定)
 * @param slug      文章 slug,用于拼 URL;同时作为翻译状态 key(slug 唯一)
 * @param url       文章完整 HTTPS 地址(https://www.therundown.ai/p/<slug>)
 * @param title     主标题(当日主事件)
 * @param subtitle  PLUS 副标题(次要工具/技巧);部分条目可能为空
 * @param authors   作者段,如 "Zach Mink, +4"(+4 表示还有 4 位合著者);原样展示
 * @param coverUrl  封面图 URL(beehiiv cdn-cgi 图);列表缩略图用,无则为空
 */
@Immutable

data class RundownAiArticle(
    val rank: Int,
    val slug: String,
    val url: String,
    val title: String,
    val subtitle: String = "",
    val authors: String = "",
    val coverUrl: String = ""
) {
    companion object {
        /**
         * 从归档快照的一个 item JSON 抽取文章信息。
         *
         * 字段名对齐 [scripts/fetch_data.py] 的 fetch_rundown_ai 落盘结构。
         * slug 与 title 必有(对齐抓取端:缺则跳过);其余字段缺失走类型默认值。
         *
         * @return slug 或 title 为空返回 null,调用方 mapNotNull 跳过
         */
        fun fromJson(obj: JSONObject, fallbackRank: Int): RundownAiArticle? {
            val slug = obj.optString("slug").trim()
            val title = obj.optString("title").trim()
            if (slug.isBlank() || title.isBlank()) return null
            return RundownAiArticle(
                rank = obj.optInt("rank", fallbackRank),
                slug = slug,
                url = obj.optString("url").trim().ifBlank { "https://www.therundown.ai/p/$slug" },
                title = title,
                subtitle = obj.optString("subtitle").trim(),
                authors = obj.optString("authors").trim(),
                coverUrl = obj.optString("coverUrl").trim()
            )
        }
    }
}

/**
 * The Rundown AI 拉取结果(带数据新鲜度),对齐 [StormzhangAiNewsResult]。
 *
 * @param fetchedAt 数据落盘时刻(归档快照的 fetched_at_ms)
 * @param articles  近况 newsletter 文章列表(首页约 16 篇)
 */
data class RundownAiResult(
    override val fetchedAt: Long,
    val articles: List<RundownAiArticle>
) : SourceListResult<RundownAiArticle> {
    override val items: List<RundownAiArticle> get() = articles
}
