package com.peng.ainewshub.data

import org.jsoup.nodes.Element

/**
 * stormzhang AI Daily 单条资讯(来源:https://news.stormzhang.ai)。
 *
 * 与 [TrendingRepo] / [LinuxDoTopic] 平行:这是第四个独立热榜数据源。
 * 该站是「每日 AI 资讯聚合」,由 AI 自动摘要生成,聚合 Hacker News / Reddit /
 * Product Hunt / The Rundown AI / TLDR AI 等信源。每条同时带中文摘要(主)和英文原文(辅)。
 *
 * 不加 @Parcelize:点击走 [url](内置 WebView),URL 是普通字符串,无需跨页面传整个对象。
 *
 * @param rank      1 起的排名(由列表位置决定,取自页面 .item-index)
 * @param url       资讯原文完整 HTTPS 地址(.item 的 href)
 * @param summary   中文摘要(AI 生成,作为主标题展示)
 * @param english   英文原文一句话(辅助信息,弱色展示);部分条目可能为空
 * @param source    来源信源名,如 "Hacker News" / "Reddit" / "Product Hunt" / "The Rundown AI" / "TLDR AI"
 * @param time      发布时间原文,如 "2026-07-13 20:00";原样展示不做解析
 */
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
         * 从一个 `<a class="item">` 元素抽取一条资讯。
         *
         * 选择器均来自真实页面结构(2026-07 验证):
         *  - 链接:元素自身 href(.item 即是 `<a>`)
         *  - 序号:`.item-index` 文本(形如 "01");页面给出的是零填充字符串,
         *    这里统一解析成 Int 当 rank,展示时再格式化
         *  - 中文摘要:`.item-summary` 文本(主标题)
         *  - 英文原文:`.item-en` 文本(辅助)
         *  - 信源:`.badge` 文本,如 "Hacker News"
         *  - 时间:`.item-time` 文本,如 "2026-07-13 20:00"
         *
         * @return 解析失败(取不到 url 或摘要)返回 null,调用方 mapNotNull 跳过
         */
        fun fromItem(el: Element, fallbackRank: Int): StormzhangAiNews? {
            val url = el.attr("href").trim().takeIf { it.startsWith("http") } ?: return null
            val summary = el.selectFirst(".item-summary")?.text()?.trim().orEmpty()
            if (summary.isBlank()) return null

            val rank = el.selectFirst(".item-index")?.text()?.trim()?.toIntOrNull()
                ?: fallbackRank
            val english = el.selectFirst(".item-en")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: ""
            val source = el.selectFirst(".badge")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: ""
            val time = el.selectFirst(".item-time")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: ""

            return StormzhangAiNews(
                rank = rank,
                url = url,
                summary = summary,
                english = english,
                source = source,
                time = time
            )
        }

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
