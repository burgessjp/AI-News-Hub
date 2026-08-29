package com.peng.ainewshub.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.peng.ainewshub.data.model.OpenAiAnthropicNews
import com.peng.ainewshub.data.model.ProductHunt
import com.peng.ainewshub.data.model.RundownAiArticle

/**
 * 三个归档源条目解析器回归([ProductHunt] / [RundownAiArticle] / [OpenAiAnthropicNews])。
 *
 * 共同契约:主键字段缺失 → null(调用方 mapNotNull 跳过);rank 缺失回退 fallbackRank。
 */
class ArchiveItemParsersTest {

    // ===== Product Hunt =====

    @Test
    fun `ProductHunt 完整字段解析`() {
        val ph = ProductHunt.fromJson(
            JSONObject(
                """
                {
                  "rank": 2, "id": "490881", "slug": "archify",
                  "name": "Archify", "tagline": "Diagrams from agents",
                  "votesCount": 312, "commentsCount": 45,
                  "website": "https://archify.dev", "url": "https://www.producthunt.com/posts/archify",
                  "createdAt": "2026-08-29T07:01:00Z", "dailyRank": 1,
                  "topics": ["Developer Tools", "Artificial Intelligence"],
                  "thumbnailUrl": "https://cdn.ph.example/th.png"
                }
                """.trimIndent()
            ),
            fallbackRank = 9
        )!!
        assertEquals(2, ph.rank)
        assertEquals("Archify", ph.name)
        assertEquals(312, ph.votesCount)
        assertEquals(listOf("Developer Tools", "Artificial Intelligence"), ph.topics)
        assertEquals("https://www.producthunt.com/posts/archify", ph.targetUrl)
    }

    @Test
    fun `ProductHunt 缺 id 或 name 返回 null 缺 rank 回退`() {
        assertNull(ProductHunt.fromJson(JSONObject("""{"name":"x"}"""), 1))
        assertNull(ProductHunt.fromJson(JSONObject("""{"id":"1"}"""), 1))
        val ph = ProductHunt.fromJson(JSONObject("""{"id":"1","name":"x"}"""), fallbackRank = 7)!!
        assertEquals(7, ph.rank)
        assertEquals("", ph.targetUrl) // url/website 均缺 → 空串,UI 不触发跳转
    }

    // ===== The Rundown AI =====

    @Test
    fun `RundownAiArticle 缺 url 时按 slug 拼回`() {
        val a = RundownAiArticle.fromJson(
            JSONObject("""{"rank":1,"slug":"openai-launches-x","title":"OpenAI Launches X"}"""),
            fallbackRank = 5
        )!!
        assertEquals("https://www.therundown.ai/p/openai-launches-x", a.url)
        assertEquals(1, a.rank)
    }

    @Test
    fun `RundownAiArticle 缺 slug 或 title 返回 null`() {
        assertNull(RundownAiArticle.fromJson(JSONObject("""{"title":"t"}"""), 1))
        assertNull(RundownAiArticle.fromJson(JSONObject("""{"slug":"s"}"""), 1))
    }

    // ===== OpenAI x Anthropic =====

    @Test
    fun `OpenAiAnthropicNews 完整字段解析`() {
        val n = OpenAiAnthropicNews.fromJson(
            JSONObject(
                """
                {
                  "rank": 1,
                  "title": "GPT-5.2 general availability",
                  "url": "https://openai.com/blog/gpt-5-2",
                  "summary": "GPT-5.2 is now available in the API.",
                  "vendor": "OpenAI",
                  "category": "Announcements",
                  "publishedAt": "2026-08-29T13:00:00Z"
                }
                """.trimIndent()
            ),
            fallbackRank = 3
        )!!
        assertEquals("OpenAI", n.vendor)
        assertEquals("Announcements", n.category)
        assertEquals(1, n.rank)
    }

    @Test
    fun `OpenAiAnthropicNews 缺 title 或 url 返回 null 缺 rank 回退`() {
        assertNull(OpenAiAnthropicNews.fromJson(JSONObject("""{"url":"https://a"}"""), 1))
        assertNull(OpenAiAnthropicNews.fromJson(JSONObject("""{"title":"t"}"""), 1))
        val n = OpenAiAnthropicNews.fromJson(
            JSONObject("""{"title":"t","url":"https://a"}"""), fallbackRank = 4
        )!!
        assertEquals(4, n.rank)
    }
}
