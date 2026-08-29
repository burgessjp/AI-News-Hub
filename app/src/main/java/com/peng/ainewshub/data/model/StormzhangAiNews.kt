package com.peng.ainewshub.data.model

import androidx.compose.runtime.Immutable

/**
 * stormzhang AI Daily 单条资讯(来源:https://news.stormzhang.ai,由数据流水线抓取归档)。
 *
 * 与 [TrendingRepo] 平行:这是独立热榜数据源之一。
 * 该站是「每日 AI 资讯聚合」,由 AI 自动摘要生成,聚合 Hacker News / Reddit /
 * Product Hunt / The Rundown AI / TLDR AI 等信源。每条同时带中文摘要(主)和英文原文(辅)。
 *
 * 不加 @Parcelize:点击走 [url](内置 WebView),URL 是普通字符串,无需跨页面传整个对象。
 *
 * @param rank      1 起的排名(由列表位置决定)
 * @param url       资讯原文完整 HTTPS 地址
 * @param summary   中文摘要(AI 生成,作为主标题展示)
 * @param english   英文原文一句话(辅助信息,弱色展示);部分条目可能为空
 * @param source    来源信源名,如 "Hacker News" / "Reddit" / "Product Hunt" / "The Rundown AI" / "TLDR AI"
 * @param time      发布时间原文,如 "2026-07-13 20:00";原样展示不做解析
 */
@Immutable

data class StormzhangAiNews(
    val rank: Int,
    val url: String,
    val summary: String,
    val english: String = "",
    val source: String = "",
    val time: String = ""
) {
    companion object {
        /**
         * 信源 → 徽章主色(ARGB Int),取自原站 badge CSS 配色:
         *  - Hacker News   #ff8844
         *  - Reddit        #ff6644
         *  - Product Hunt  #f472b6
         *  - The Rundown AI #a78bfa
         *  - TLDR AI       #60a5fa
         *  - 其余兜底      #aaaaaa(badge-default)
         *
         * 用色直接对齐原站,让徽章视觉与 news.stormzhang.ai 一致;未知名源走中性灰。
         * 返回 ARGB Int,UI 层用 [androidx.compose.ui.graphics.Color] 包一层即可。
         */
        fun sourceColorHex(source: String): String = when (source) {
            "Hacker News" -> "#ff8844"
            "Reddit" -> "#ff6644"
            "Product Hunt" -> "#f472b6"
            "The Rundown AI" -> "#a78bfa"
            "TLDR AI" -> "#60a5fa"
            else -> "#aaaaaa"
        }
    }
}

/**
 * AI 资讯拉取结果(带数据新鲜度),与 [TrendingResult] 同构。
 *
 * [fetchedAt] 是归档快照顶层的 fetched_at_ms(数据落盘时刻),
 * UI 据此在列表头显示「数据更新时间」。
 *
 * [pageDate] 是页面声明的资讯日期(如 "2026.07.13"),由流水线从 title 抽取,
 * 解析不到时为空;供列表页顶栏展示「AI Daily · 2026.07.13」副标题。
 */
data class StormzhangAiNewsResult(
    override val fetchedAt: Long,
    val news: List<StormzhangAiNews>,
    val pageDate: String = ""
) : SourceListResult<StormzhangAiNews> {
    override val items: List<StormzhangAiNews> get() = news
}
