package com.peng.ainewshub.data

import com.peng.ainewshub.data.source.RundownAiResult
import com.peng.ainewshub.data.source.RundownAiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File

/**
 * The Rundown AI 首页文章卡片墙抓取客户端(实时源)。
 * 来源:https://www.therundown.ai/(beehiiv 托管的 AI newsletter,首页静态 HTML)。
 *
 * 继承 [BaseHtmlCacheRepository]:缓存 + Mutex + stale 兜底四步逻辑由基类统一,
 * 本类只提供 URL / headers / jsoup 解析(卡片墙 + 封面图)+ 结果包装。TTL 4 小时(基类统一)。
 *
 * 选择器 `a[href^="/p/"]`,每张卡片含「标题 | PLUS:副标题 | 作者」三段文本 +
 * 一张封面图(beehiiv cdn-cgi 图,需排除 width=256 的作者头像)。列表页无日期字段,不抽 pageDate。
 *
 * UA:站点对默认 OkHttp UA 偶尔差异对待,统一带浏览器 UA。
 *
 * @param cacheDir 缓存根目录,通常传 application.cacheDir;传 null 关闭缓存
 */
class RundownAiRepository(
    cacheDir: File? = null
) : BaseHtmlCacheRepository<RundownAiResult>(cacheDir), RundownAiSource {

    private val homeUrl = "https://www.therundown.ai/"

    override val cacheFileName: String = "rundown_ai.html"

    override suspend fun fetchHtml(): String = withContext(Dispatchers.IO) {
        HttpClients.get(
            homeUrl,
            mapOf(
                "User-Agent" to HttpClients.DEFAULT_BROWSER_UA,
                "Accept" to "text/html,application/xhtml+xml",
                "Accept-Language" to "en-US,en;q=0.9"
            )
        )
    }

    override fun packResult(fetchedAt: Long, rawHtml: String): RundownAiResult =
        RundownAiResult(fetchedAt, parse(rawHtml))

    /**
     * 用 jsoup 解析首页文章卡片墙。
     *
     * 选择器 `a[href^="/p/"]`:beehiiv 首页每张文章卡是 `<a href="/p/<slug>">`,
     * 卡内 getText(" | ") 合并出「标题 | PLUS:副标题 | 作者, +N」三段文本。
     * 同一 slug 可能被多个锚点引用(如「最新」「精选」重复),按 slug 去重取首次出现。
     *
     * 封面图:卡内 `<img>` 中,作者头像是 `width=256` 规格的图,排除后取首张
     * beehiiv cdn-cgi 图作为封面。
     */
    private fun parse(html: String): List<RundownAiArticle> {
        val doc = Jsoup.parse(html)
        val seenSlugs = mutableSetOf<String>()
        val articles = mutableListOf<RundownAiArticle>()
        var rank = 0
        for (el in doc.select("a[href^=/p/]")) {
            val href = el.attr("href").trim()
            val slug = href.substringBefore("?").substringBefore("#")
                .removePrefix("/p/").trim('/')
            if (slug.isBlank() || slug in seenSlugs) continue

            val rawText = el.textNodes().joinToString(" | ") { it.text().trim() }
                .replace(Regex("\\s+"), " ")
                .trim()
            val (title, subtitle, authors) = splitCardText(rawText)
            if (title.isBlank()) continue

            // 封面图:排除作者头像(width=256 是 beehiiv 头像规格特征),
            // 取首张 beehiiv cdn-cgi 图(对齐 fetch_data.py 的判别逻辑)。
            val coverUrl = el.select("img").firstOrNull { img ->
                val src = img.attr("src")
                src.isNotBlank() &&
                    !src.contains("width=256") &&
                    (src.contains("beehiiv.com/cdn-cgi/image") || src.contains("beehiiv-images-production"))
            }?.attr("src")?.trim().orEmpty()

            rank += 1
            seenSlugs.add(slug)
            articles.add(
                RundownAiArticle(
                    rank = rank,
                    slug = slug,
                    url = "https://www.therundown.ai/p/$slug",
                    title = title,
                    subtitle = subtitle,
                    authors = authors,
                    coverUrl = coverUrl
                )
            )
        }
        return articles
    }

    /**
     * 拆 The Rundown AI 首页卡片的合并文本「标题 | PLUS: 副标题 | 作者, +N」。
     *
     * @return (title, subtitle, authors),任何一段缺失返回空串
     */
    private fun splitCardText(text: String): Triple<String, String, String> {
        if (text.isBlank()) return Triple("", "", "")
        val parts = text.split(" | ").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return Triple("", "", "")
        val title = parts.first()
        var subtitle = ""
        var authors = ""
        for (p in parts.drop(1)) {
            if (p.uppercase().startsWith("PLUS")) {
                subtitle = Regex("^PLUS:\\s*", RegexOption.IGNORE_CASE).replace(p, "").trim()
            } else {
                authors = p
            }
        }
        return Triple(title, subtitle, authors)
    }
}
