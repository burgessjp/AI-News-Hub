package com.peng.ainewshub.data

import androidx.compose.runtime.Immutable


import org.jsoup.nodes.Element

/**
 * GitHub Trending 单条仓库(来源:https://github.com/trending)。
 *
 * 与 [NewsItem] / [HackerNewsStory] 平行:Trending 是第三个独立数据源,
 * 字段语义(排名/今日新增/语言色点)与新闻/HN 完全不同,故单独建模。
 *
 * 不加 @Parcelize:点击仓库走 [url](内置 WebView),URL 是普通字符串,
 * 无需把整个对象跨页面传递(对比 NewsItem/HackerNewsStory 需带详情进二级页)。
 *
 * @param rank          1 起的排名(由列表位置决定,非 HTML 字段)
 * @param owner         仓库所有者,如 "OpenCut-app"
 * @param name          仓库名,如 "OpenCut"
 * @param url           仓库完整 HTTPS 地址
 * @param description   一句话描述(已由 jsoup 解码 HTML 实体);可能为空
 * @param language      主语言,如 "TypeScript";部分仓库无语言,为空
 * @param languageColor 语言色点十六进制(如 "#3178c6");无语言时为空
 * @param totalStars    累计 star 数
 * @param forks         fork 数
 * @param starsToday    今日新增 star 数(页面默认 daily 窗口)
 */
@Immutable

data class TrendingRepo(
    val rank: Int,
    val owner: String,
    val name: String,
    val url: String,
    val description: String = "",
    val language: String = "",
    val languageColor: String = "",
    val totalStars: Int = 0,
    val forks: Int = 0,
    val starsToday: Int = 0
) {
    companion object {
        /**
         * 从一个 `<article class="Box-row">` 元素抽取仓库信息。
         *
         * 选择器均来自真实 trending 页面结构(2026-07 验证):
         *  - 路径:`h2 a` 的 href(形如 "/owner/name"),去前导斜杠
         *  - 描述:`article` 直接子 `p` 的文本
         *  - 语言:`[itemprop=programmingLanguage]` 文本
         *  - 语言色:`.repo-language-color` 的 style 中 `#xxxxxx`
         *  - stars:`a[href$=/stargazers]` 文本去逗号
         *  - forks: `a[href$=/forks]` 文本去逗号
         *  - 今日新增:整段文本匹配 `([\d,]+)\s*stars today`
         *
         * @return 解析失败(取不到 owner/name)返回 null,调用方 mapNotNull 跳过
         */
        fun fromArticle(el: Element, rank: Int): TrendingRepo? {
            val link = el.selectFirst("h2 a") ?: return null
            val path = link.attr("href").trim().removePrefix("/")
            // 仅接受 owner/name 形态,过滤无关链接
            val parts = path.split("/")
            if (parts.size < 2 || parts.any { it.isBlank() }) return null
            val owner = parts[0]
            val name = parts[1]

            val description = el.selectFirst("p")?.text()?.takeIf { it.isNotBlank() } ?: ""
            val language = el.selectFirst("[itemprop=programmingLanguage]")?.text()?.takeIf { it.isNotBlank() } ?: ""
            val languageColor = el.selectFirst(".repo-language-color")
                ?.attr("style")
                ?.let { COLOR_RE.find(it)?.value }
                ?: ""

            val totalStars = el.selectFirst("a[href$=/stargazers]")?.text().parseCount()
            val forks = el.selectFirst("a[href$=/forks]")?.text().parseCount()

            // 「今日新增」在一个无 class 的 span 里,只能靠整段文本匹配。
            val starsToday = TODAY_RE.find(el.text())?.groupValues?.getOrNull(1).parseCount()

            return TrendingRepo(
                rank = rank,
                owner = owner,
                name = name,
                url = "https://github.com/$owner/$name",
                description = description,
                language = language,
                languageColor = languageColor,
                totalStars = totalStars,
                forks = forks,
                starsToday = starsToday
            )
        }

        // 语言色点:# 后跟 3-6 位十六进制(GitHub 用 6 位,兼容缩写)
        private val COLOR_RE = Regex("#[0-9a-fA-F]{3,6}")
        // 今日新增:形如 "1,077 stars today"(weekly/monthly 不会匹配,首版只展示 daily)
        private val TODAY_RE = Regex("([\\d,]+)\\s*stars\\s*today")

        /** 把 "64,846" / "" / null 统一解析成 Int;无法解析返回 0。 */
        private fun String?.parseCount(): Int =
            this?.replace(",", "")?.trim()?.toIntOrNull() ?: 0
    }
}
