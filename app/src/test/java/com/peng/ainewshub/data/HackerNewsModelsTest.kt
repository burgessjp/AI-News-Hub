package com.peng.ainewshub.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import com.peng.ainewshub.data.model.HackerNewsStory

/**
 * HackerNews 模型解析回归([HackerNewsStory] fromJson)。
 */
class HackerNewsModelsTest {

    @Test
    fun `Story 完整字段与 kids 数组解析`() {
        val story = HackerNewsStory.fromJson(
            JSONObject(
                """
                {
                  "id": 49485267,
                  "title": "Boot a Virtual iPhone",
                  "url": "https://github.com/Lakr233/vphone-cli",
                  "by": "hentrep",
                  "score": 115,
                  "descendants": 38,
                  "time": 1787958141,
                  "kids": [1, 2, 3]
                }
                """.trimIndent()
            )
        )
        assertEquals(49485267L, story.id)
        assertEquals(115, story.score)
        assertEquals(listOf(1L, 2L, 3L), story.kids)
        // 有外部链接时 targetUrl 优先原文
        assertEquals("https://github.com/Lakr233/vphone-cli", story.targetUrl)
        assertEquals("https://news.ycombinator.com/item?id=49485267", story.discussionUrl)
    }

    @Test
    fun `站内帖缺 url 时 targetUrl 回退讨论页`() {
        val story = HackerNewsStory.fromJson(JSONObject("""{"id":7,"title":"Ask HN"}"""))
        assertEquals("", story.url)
        assertEquals("https://news.ycombinator.com/item?id=7", story.targetUrl)
        assertEquals(0, story.score)
        assertEquals(emptyList<Long>(), story.kids)
    }
}
