package com.peng.ainewshub.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 三个 HTML 抓取源解析器回归(jsoup `fromArticle` / `fromItem`)。
 *
 * HTML 片段按被测类 KDoc 记录的真实页面结构(2026-07 验证的选择器)构造;
 * 上游页面改版时这里是第一时间报警的哨兵 —— 失败即说明选择器需跟进。
 */
class HtmlParsersTest {

    private fun selectFirst(html: String, css: String): Element =
        Jsoup.parseBodyFragment(html).selectFirst(css)!!

    // ===== GitHub Trending =====

    @Test
    fun `TrendingRepo 完整字段解析`() {
        val el = selectFirst(
            """
            <article class="Box-row">
              <h2><a href="/tt-a1i/archify">archify</a></h2>
              <p>Agent skill for beautiful diagrams</p>
              <span itemprop="programmingLanguage">JavaScript</span>
              <span class="repo-language-color" style="background-color: #f1e05a;"></span>
              <a href="/tt-a1i/archify/stargazers">1,234</a>
              <a href="/tt-a1i/archify/forks">56</a>
              <span>789 stars today</span>
            </article>
            """.trimIndent(),
            "article"
        )
        val repo = TrendingRepo.fromArticle(el, rank = 3)!!
        assertEquals(3, repo.rank)
        assertEquals("tt-a1i", repo.owner)
        assertEquals("archify", repo.name)
        assertEquals("https://github.com/tt-a1i/archify", repo.url)
        assertEquals("Agent skill for beautiful diagrams", repo.description)
        assertEquals("JavaScript", repo.language)
        assertEquals("#f1e05a", repo.languageColor)
        assertEquals(1234, repo.totalStars)
        assertEquals(56, repo.forks)
        assertEquals(789, repo.starsToday)
    }

    @Test
    fun `TrendingRepo 非 owner 形态链接与缺链接返回 null`() {
        val noLink = Jsoup.parseBodyFragment("""<article class="Box-row"><p>无链接</p></article>""").selectFirst("article")!!
        assertNull(TrendingRepo.fromArticle(noLink, 1))

        val badPath = Jsoup.parseBodyFragment("""<article><h2><a href="/login">Login</a></h2></article>""").selectFirst("article")!!
        assertNull(TrendingRepo.fromArticle(badPath, 1))
    }

    // ===== HuggingFace Papers =====

    @Test
    fun `HuggingFacePaper 完整字段解析`() {
        val el = selectFirst(
            """
            <article class="relative">
              <h3><a href="/papers/2403.08299">MoE Layers 详解</a></h3>
              <p class="line-clamp-2">一句话摘要文本。</p>
              <div class="font-semibold text-orange-500">42</div>
              <span>Published on Jul 8, 2026</span>
              <div>5 authors</div>
              <a href="https://github.com/foo/bar" target="_blank">GitHub</a>
            </article>
            """.trimIndent(),
            "article"
        )
        val paper = HuggingFacePaper.fromArticle(el, rank = 1)!!
        assertEquals("2403.08299", paper.id)
        assertEquals("https://huggingface.co/papers/2403.08299", paper.url)
        assertEquals("MoE Layers 详解", paper.title)
        assertEquals("一句话摘要文本。", paper.summary)
        assertEquals(42, paper.upvotes)
        assertEquals("Jul 8, 2026", paper.published)
        assertEquals("5 authors", paper.authors)
        assertEquals("https://github.com/foo/bar", paper.githubUrl)
    }

    @Test
    fun `HuggingFacePaper 官方仓库链接被过滤`() {
        val el = selectFirst(
            """
            <article class="relative">
              <h3><a href="/papers/1111.2222">Title</a></h3>
              <a href="https://github.com/huggingface/transformers" target="_blank">GitHub</a>
            </article>
            """.trimIndent(),
            "article"
        )
        val paper = HuggingFacePaper.fromArticle(el, rank = 1)!!
        assertEquals("", paper.githubUrl)
        assertEquals("", paper.summary) // 无 line-clamp-2 段落 → 空摘要
    }

    // ===== stormzhang AI Daily =====

    @Test
    fun `StormzhangAiNews 完整字段解析`() {
        val el = selectFirst(
            """
            <a class="item" href="https://example.com/post/1">
              <span class="item-index">01</span>
              <span class="item-summary">中文摘要内容</span>
              <span class="item-en">English original line</span>
              <span class="badge">Hacker News</span>
              <span class="item-time">2026-07-13 20:00</span>
            </a>
            """.trimIndent(),
            "a.item"
        )
        val news = StormzhangAiNews.fromItem(el, fallbackRank = 9)!!
        assertEquals(1, news.rank) // "01" 解析为 1
        assertEquals("https://example.com/post/1", news.url)
        assertEquals("中文摘要内容", news.summary)
        assertEquals("English original line", news.english)
        assertEquals("Hacker News", news.source)
        assertEquals("2026-07-13 20:00", news.time)
    }

    @Test
    fun `StormzhangAiNews 非法链接或空摘要返回 null 且序号缺失回退`() {
        val notHttp = Jsoup.parseBodyFragment(
            """<a class="item" href="javascript:void(0)"><span class="item-summary">s</span></a>"""
        ).selectFirst("a.item")!!
        assertNull(StormzhangAiNews.fromItem(notHttp, 1))

        val noSummary = Jsoup.parseBodyFragment(
            """<a class="item" href="https://a.com"><span class="item-index">02</span></a>"""
        ).selectFirst("a.item")!!
        assertNull(StormzhangAiNews.fromItem(noSummary, 1))

        val noIndex = Jsoup.parseBodyFragment(
            """<a class="item" href="https://a.com"><span class="item-summary">s</span></a>"""
        ).selectFirst("a.item")!!
        assertEquals(7, StormzhangAiNews.fromItem(noIndex, fallbackRank = 7)!!.rank)
    }

    @Test
    fun `信源徽章配色与原站一致`() {
        assertEquals("#ff8844", StormzhangAiNews.sourceColorHex("Hacker News"))
        assertEquals("#f472b6", StormzhangAiNews.sourceColorHex("Product Hunt"))
        assertEquals("#aaaaaa", StormzhangAiNews.sourceColorHex("未知来源"))
    }
}
