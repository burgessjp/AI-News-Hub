package com.peng.ainewshub.data.model

import androidx.compose.runtime.Immutable


import android.os.Parcelable
import androidx.annotation.StringRes
import com.peng.ainewshub.R
import kotlinx.parcelize.Parcelize
import org.json.JSONObject
import com.peng.ainewshub.data.asClean

/**
 * 分类枚举。键名严格对齐官方 API 的 5 个 enum 值
 * (见 /agent 文档:`ai-models / ai-products / industry / paper / tip`)。
 * 展示名走资源([labelRes]),UI 层 `stringResource(cat.labelRes)`。
 */
enum class NewsCategory(val api: String, @StringRes val labelRes: Int) {
    AI_MODELS("ai-models", R.string.category_model),
    AI_PRODUCTS("ai-products", R.string.category_product),
    INDUSTRY("industry", R.string.category_industry),
    PAPER("paper", R.string.category_paper),
    TIP("tip", R.string.category_tips);

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
 * 单条 AI 动态。字段对齐官方公开 API `/api/v1/items`。
 *
 * 注意:
 *  - originalTitle / summary / publishedAt / category / score 均可为空(JSON null)
 *  - score 是 0-100 总分,非排序字段(列表仍按 publishedAt 倒序)
 *  - id 是 cuid(25 字符),不是数字
 *  - permalink 是站内中文翻译阅读页,深链优先用之;url 是第三方原文
 *  - v1 嵌套字段:原文链接在 links.original,站内深链在 links.aihot,
 *    来源名在 source.name(旧版顶层平铺字段已停服)
 */
@Immutable
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
    /** 分类名资源;无法识别时返回 null(UI 层回退原始 code 展示)。 */
    @StringRes
    fun categoryLabelRes(): Int? = NewsCategory.fromApi(category)?.labelRes

    companion object {
        // asClean 逻辑收口于顶层扩展(data/JsonExt.kt),各 companion 直接引用。

        fun fromJson(json: JSONObject): NewsItem = NewsItem(
            id = json.optString("id").asClean().orEmpty(),
            title = json.optString("title").asClean().orEmpty(),
            titleEn = json.optString("originalTitle").asClean(),
            summary = json.optString("summary").asClean(),
            url = json.optJSONObject("links")?.optString("original").asClean().orEmpty(),
            permalink = json.optJSONObject("links")?.optString("aihot").asClean().orEmpty(),
            source = json.optJSONObject("source")?.optString("name").asClean().orEmpty(),
            publishedAt = json.optString("publishedAt").asClean(),
            category = json.optString("category").asClean(),
            score = json.optInt("score", 0),
            selected = json.optBoolean("selected")
        )
    }
}

// ===== 日报相关模型(/api/v1/dailies 系列,报告本体在响应 report 字段下) =====

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
 * 今日热点(/api/v1/hot-topics)的单条。
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
        // asClean 逻辑收口于顶层扩展(data/JsonExt.kt),各 companion 直接引用。

        fun fromJson(json: JSONObject): HotTopic {
            val namesArr = json.optJSONArray("sourceNames") ?: org.json.JSONArray()
            return HotTopic(
                id = json.optString("id"),
                title = json.optString("title"),
                url = json.optJSONObject("links")?.optString("original").asClean().orEmpty(),
                permalink = json.optJSONObject("links")?.optString("aihot").asClean().orEmpty(),
                source = json.optJSONObject("source")?.optString("name").asClean().orEmpty(),
                sourceCount = json.optInt("sourceCount", 0),
                sourceNames = (0 until namesArr.length())
                    .mapNotNull { namesArr.optString(it).asClean() },
                latestAt = json.optString("latestAt").asClean()
            )
        }
    }
}

