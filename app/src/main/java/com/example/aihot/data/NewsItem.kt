package com.example.aihot.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.json.JSONObject

/**
 * 分类枚举。键名严格对齐官方 API 的 5 个 enum 值
 * (见 /agent 文档:`ai-models / ai-products / industry / paper / tip`)。
 */
enum class NewsCategory(val api: String, val zh: String) {
    AI_MODELS("ai-models", "模型"),
    AI_PRODUCTS("ai-products", "产品"),
    INDUSTRY("industry", "行业"),
    PAPER("paper", "论文"),
    TIP("tip", "技巧与观点");

    companion object {
        fun fromApi(code: String?): NewsCategory? =
            code?.let { c -> entries.firstOrNull { it.api == c } }
    }
}

/** items 端点的"模式"。 */
enum class Mode(val api: String) {
    SELECTED("selected"), // 精选(每日精编候选池,默认)
    ALL("all")            // 全部 AI 动态(含未精选)
}

/**
 * 单条 AI 动态。字段对齐官方公开 API `/api/public/items`。
 *
 * 注意:
 *  - title_en / summary / publishedAt / category / score 均可为空(JSON null)
 *  - score 是 0-100 总分,非排序字段(列表仍按 publishedAt 倒序)
 *  - id 是 cuid(25 字符),不是数字
 *  - permalink 是站内中文翻译阅读页,深链优先用之;url 是第三方原文
 */
@Parcelize
data class NewsItem(
    val id: String,
    val title: String = "",
    val titleEn: String? = null,
    val summary: String? = null,
    val url: String = "",
    val permalink: String = "",
    val source: String = "",
    val publishedAt: String? = null,
    val category: String? = null,
    val score: Int = 0,
    val selected: Boolean = false
) : Parcelable {
    /** 中文分类名;无法识别时回退原始 code。 */
    fun categoryLabel(): String = NewsCategory.fromApi(category)?.zh ?: category.orEmpty()

    companion object {
        // optString 在遇到 JSON null 时返回字面字符串 "null"(非空),故需额外 != "null" 过滤,
        // 与 NewsRepository 中 permalink/nextCursor 等的处理保持一致。
        private fun String?.asClean(): String? =
            this?.takeIf { it.isNotBlank() && it != "null" }

        fun fromJson(json: JSONObject): NewsItem = NewsItem(
            id = json.optString("id").asClean().orEmpty(),
            title = json.optString("title").asClean().orEmpty(),
            titleEn = json.optString("title_en").asClean(),
            summary = json.optString("summary").asClean(),
            url = json.optString("url").asClean().orEmpty(),
            permalink = json.optString("permalink").asClean().orEmpty(),
            source = json.optString("source").asClean().orEmpty(),
            publishedAt = json.optString("publishedAt").asClean(),
            category = json.optString("category").asClean(),
            score = json.optInt("score", 0),
            selected = json.optBoolean("selected")
        )
    }
}

// ===== 日报相关模型(/api/public/daily 系列) =====

data class DailyReport(
    val date: String,                       // YYYY-MM-DD UTC
    val generatedAt: String,
    val windowStart: String,
    val windowEnd: String,
    val lead: Lead? = null,
    val sections: List<DailySection> = emptyList(),
    val flashes: List<Flash> = emptyList()
)

data class Lead(
    val title: String,
    val leadParagraph: String? = null
)

/** 日报分节,固定 5 个 label 之一(可能为空 items)。 */
data class DailySection(
    val label: String,
    val items: List<DailyEntry> = emptyList()
)

data class DailyEntry(
    val title: String,
    val summary: String? = null,
    val sourceUrl: String = "",
    val sourceName: String = "",
    val permalink: String? = null
)

/** 快讯。 */
data class Flash(
    val title: String,
    val sourceName: String = "",
    val sourceUrl: String = "",
    val publishedAt: String? = null,
    val permalink: String? = null
)

/** /dailies 归档列表的单项摘要。 */
data class DailySummary(
    val date: String,
    val generatedAt: String,
    val leadTitle: String? = null
)

/**
 * 今日热点(/api/public/hot-topics)的单条。
 *
 * 与 [NewsItem] 的区别:热点是「同一事件跨多源聚合」,故额外带
 * sourceCount(聚合来源数)与 sourceNames(来源清单);没有 score/category 等字段。
 *
 * - latestAt:该事件最近一次更新时间(ISO 8601 UTC),用于排序与展示
 * - permalink:站内阅读页(同 NewsItem);url 是该事件的主源原文
 */
data class HotTopic(
    val id: String,
    val title: String = "",
    val url: String = "",
    val permalink: String = "",
    val source: String = "",
    val sourceCount: Int = 0,
    val sourceNames: List<String> = emptyList(),
    val latestAt: String? = null
) {
    companion object {
        // optString 在遇到 JSON null 时返回字面字符串 "null"(非空),需过滤。
        private fun String?.asClean(): String? =
            this?.takeIf { it.isNotBlank() && it != "null" }

        fun fromJson(json: JSONObject): HotTopic {
            val namesArr = json.optJSONArray("sourceNames") ?: org.json.JSONArray()
            return HotTopic(
                id = json.optString("id"),
                title = json.optString("title"),
                url = json.optString("url"),
                permalink = json.optString("permalink").asClean().orEmpty(),
                source = json.optString("source"),
                sourceCount = json.optInt("sourceCount", 0),
                sourceNames = (0 until namesArr.length())
                    .mapNotNull { namesArr.optString(it).asClean() },
                latestAt = json.optString("latestAt").asClean()
            )
        }
    }
}

