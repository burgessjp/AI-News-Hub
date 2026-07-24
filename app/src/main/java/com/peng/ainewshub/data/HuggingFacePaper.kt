package com.peng.ainewshub.data

import org.jsoup.nodes.Element

/**
 * HuggingFace Trending Paper 单篇论文(来源:https://huggingface.co/papers/trending)。
 *
 * 与 [TrendingRepo] / [LinuxDoTopic] / [StormzhangAiNews] 平行:这是第五个独立热榜数据源。
 * HuggingFace 的 Trending Papers 由 AK 每日精选 arXiv 论文,按社区 upvote 排序,
 * 是跟踪前沿 AI 研究的常用入口。页面为 SSR HTML,无公开 JSON API。
 *
 * 不加 @Parcelize:点击走 [url](内置 WebView),URL 是普通字符串,无需跨页面传整个对象。
 *
 * @param rank       1 起的排名(由列表位置决定,非 HTML 字段)
 * @param id         论文 id,即 arXiv 编号,如 "2403.08299"
 * @param url        论文页完整 HTTPS 地址,如 "https://huggingface.co/papers/2403.08299"
 * @param title      论文标题(h3 > a 文本)
 * @param summary    一句话摘要(页面 .line-clamp-2 段落,已由 jsoup 解码 HTML 实体);可能为空
 * @param upvotes    社区 upvote 数(热度主指标)
 * @param published  发布日期原文,如 "Jul 8, 2026";原样展示不做解析
 * @param authors    作者信息文本,优先取页面 "N authors" 段(如 "5 authors");
 *                   取不到时若页面给了具名头像/作者名,则拼成 "A, B, C" 形式;都没有则为空
 * @param githubUrl  论文关联的 GitHub 仓库地址(部分论文带「GitHub」按钮);无则为空
 */
data class HuggingFacePaper(
    val rank: Int,
    val id: String,
    val url: String,
    val title: String,
    val summary: String = "",
    val upvotes: Int = 0,
    val published: String = "",
    val authors: String = "",
    val githubUrl: String = ""
) {
    companion object {
        /**
         * 从一个 `<article class="relative overflow-hidden rounded-xl border">` 元素抽取论文信息。
         *
         * 选择器均来自真实 trending 页面结构(2026-07 验证):
         *  - id / url:`h3 > a[href^=/papers/]` 的 href(形如 "/papers/2403.08299")
         *  - 标题:该 a 的文本
         *  - 摘要:article 内第一个 `p.line-clamp-2` 的文本
         *  - upvote:`div.font-semibold.text-orange-500` 文本(页面有桌面/移动两份,取第一份即可)
         *  - 发布日期:文本为 "Published on …" 的 `<span>`
         *  - 作者:优先 `>N authors<` 段;取不到则聚合作者头像 `li[title]` 的 title 属性
         *  - GitHub:`a[href^=https://github.com/][target=_blank]` 的 href(过滤掉 huggingface 官方链接)
         *
         * @return 解析失败(取不到 id 或标题)返回 null,调用方 mapNotNull 跳过
         */
        fun fromArticle(el: Element, rank: Int): HuggingFacePaper? {
            val link = el.selectFirst("h3 > a[href^=/papers/]") ?: return null
            val path = link.attr("href").trim().removePrefix("/papers/")
            if (path.isBlank()) return null
            val title = link.text().trim()
            if (title.isBlank()) return null

            val summary = el.selectFirst("p.line-clamp-2")?.text()?.trim().orEmpty()

            val upvotes = el.selectFirst("div.font-semibold.text-orange-500")
                ?.text()?.trim()?.toIntOrNull() ?: 0

            val published = el.select("span").mapNotNull { it.text().trim() }
                .firstOrNull { it.startsWith("Published on") }
                ?.removePrefix("Published on")?.trim()
                ?: ""

            // 作者:优先页面给出的 "N authors" 计数段;取不到则聚合作者头像的 title 属性。
            val authors = el.allElements.mapNotNull { it.text().trim() }
                .firstOrNull { it.contains(" authors") && it.matches(Regex("\\d+ authors")) }
                ?: el.select("li[title]").map { it.attr("title").trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(", ")
                    .takeIf { it.isNotBlank() }
                ?: ""

            val githubUrl = el.select("a[href^=https://github.com/][target=_blank]")
                .map { it.attr("href").trim() }
                .firstOrNull { it.isNotBlank() && !it.contains("github.com/huggingface") }
                ?: ""

            return HuggingFacePaper(
                rank = rank,
                id = path,
                url = "https://huggingface.co/papers/$path",
                title = title,
                summary = summary,
                upvotes = upvotes,
                published = published,
                authors = authors,
                githubUrl = githubUrl
            )
        }
    }
}
