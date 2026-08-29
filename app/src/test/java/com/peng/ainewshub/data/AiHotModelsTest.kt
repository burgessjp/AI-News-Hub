package com.peng.ainewshub.data

import com.peng.ainewshub.R
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.peng.ainewshub.data.model.HotTopic
import com.peng.ainewshub.data.model.NewsCategory
import com.peng.ainewshub.data.model.NewsItem

/**
 * aihot API 模型解析回归([NewsItem] / [HotTopic] companion fromJson)。
 *
 * 重点钉住 asClean 语义:org.json 的 optString 对 JSON null 返回字面 "null"/"",
 * 解析层必须统一清洗成 Kotlin null(历史 bug 来源)。
 */
class AiHotModelsTest {

    @Test
    fun `NewsItem 完整字段解析`() {
        val item = NewsItem.fromJson(
            JSONObject(
                """
                {
                  "id": "ckabc123def456ghi789012",
                  "title": "Claude 4 发布",
                  "title_en": "Claude 4 Released",
                  "summary": "Anthropic 发布新一代模型",
                  "url": "https://example.com/a",
                  "permalink": "https://aihot.virxact.com/read/1",
                  "source": "anthropic",
                  "publishedAt": "2026-08-29T10:00:00Z",
                  "category": "ai-models",
                  "score": 88,
                  "selected": true
                }
                """.trimIndent()
            )
        )
        assertEquals("ckabc123def456ghi789012", item.id)
        assertEquals("Claude 4 发布", item.title)
        assertEquals("Claude 4 Released", item.titleEn)
        assertEquals("https://example.com/a", item.url)
        assertEquals("ai-models", item.category)
        assertEquals(NewsCategory.AI_MODELS, NewsCategory.fromApi(item.category))
        assertEquals(88, item.score)
        assertEquals(true, item.selected)
        assertEquals(R.string.category_model, item.categoryLabelRes())
    }

    @Test
    fun `JSON null 与缺失字段统一清洗为 null 而非字面量`() {
        val item = NewsItem.fromJson(
            JSONObject("""{"id":"x","title":"t","title_en":null,"summary":null,"score":null}""")
        )
        assertNull(item.titleEn)
        assertNull(item.summary)
        assertNull(item.publishedAt)
        assertEquals(0, item.score)
        assertNull(item.categoryLabelRes())
    }

    @Test
    fun `分类枚举与 API 键互查`() {
        // 5 个官方 enum 值全覆盖;未知值返回 null(UI 回退原始 code)
        listOf(
            "ai-models" to NewsCategory.AI_MODELS,
            "ai-products" to NewsCategory.AI_PRODUCTS,
            "industry" to NewsCategory.INDUSTRY,
            "paper" to NewsCategory.PAPER,
            "tip" to NewsCategory.TIP
        ).forEach { (api, expect) -> assertEquals(expect, NewsCategory.fromApi(api)) }
        assertNull(NewsCategory.fromApi("unknown"))
        assertNull(NewsCategory.fromApi(null))
    }

    @Test
    fun `HotTopic 解析含来源清单数组`() {
        val topic = HotTopic.fromJson(
            JSONObject(
                """
                {
                  "id": "topic-1",
                  "title": "同一事件跨多源",
                  "url": "https://example.com/main",
                  "permalink": "https://aihot.virxact.com/topic/1",
                  "source": "hackernews",
                  "sourceCount": 3,
                  "sourceNames": ["Hacker News", "Reddit", null],
                  "latestAt": "2026-08-29T09:00:00Z"
                }
                """.trimIndent()
            )
        )
        assertEquals("topic-1", topic.id)
        assertEquals(3, topic.sourceCount)
        // JSON null 的数组成员被 asClean 过滤,不产出字面 "null"
        assertEquals(listOf("Hacker News", "Reddit"), topic.sourceNames)
        assertEquals("2026-08-29T09:00:00Z", topic.latestAt)
    }

    @Test
    fun `HotTopic 缺数组时来源清单为空`() {
        val topic = HotTopic.fromJson(JSONObject("""{"id":"t","title":"x"}"""))
        assertEquals(emptyList<String>(), topic.sourceNames)
        assertNull(topic.latestAt)
    }
}
