package com.peng.ainewshub.data

import androidx.compose.runtime.Immutable


import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.json.JSONArray
import org.json.JSONObject

/**
 * HackerNews 数据模型(Firebase 公开 API)。
 *
 * 来源:https://github.com/HackerNews/API (基于 Firebase,匿名免费,无速率限制)。
 *
 * 独立于 [NewsItem] / [HotTopic]:HN 与 aihot 后端是两个完全不同的数据源,
 * 字段语义、时间格式(Unix 秒)、内容形态(评论为 HTML)均不同,故单独建模。
 */

/**
 * HackerNews 单条 story(/v0/item/<id>.json)。
 *
 * - score:    HN 点赞数
 * - descendants: 评论总数
 * - url:      外部原文链接;为空时是站内帖(Ask HN 等),走 [discussionUrl]
 * - time:     Unix 秒级时间戳(非 ISO 8601)
 * - kids:     一级评论 id 列表(按 HN 排名顺序),用于拉取评论树
 */
@Immutable
@Parcelize
data class HackerNewsStory(
    val id: Long,
    val title: String = "",
    val url: String = "",
    val by: String = "",
    val score: Int = 0,
    val descendants: Int = 0,
    val time: Long = 0,
    val kids: List<Long> = emptyList()
) : Parcelable {
    /** 站内讨论页;无 url 的 Ask HN / 文本帖走此链接。 */
    val discussionUrl: String
        get() = "https://news.ycombinator.com/item?id=$id"

    /** 可点击的目标链接:优先 url(外部原文),否则站内讨论页。 */
    val targetUrl: String
        get() = url.ifBlank { discussionUrl }

    companion object {
        // asClean 逻辑收口于顶层扩展(data/JsonExt.kt),各 companion 直接引用。

        fun fromJson(json: JSONObject): HackerNewsStory {
            val kidsArr = json.optJSONArray("kids")
            val kids = if (kidsArr != null) {
                (0 until kidsArr.length()).map { kidsArr.optLong(it) }
            } else emptyList()
            return HackerNewsStory(
                id = json.optLong("id"),
                title = json.optString("title"),
                url = json.optString("url").asClean().orEmpty(),
                by = json.optString("by").asClean().orEmpty(),
                score = json.optInt("score", 0),
                descendants = json.optInt("descendants", 0),
                time = json.optLong("time", 0L),
                kids = kids
            )
        }
    }
}

/**
 * HackerNews Top Stories 拉取结果(带数据新鲜度)。
 *
 * [fetchedAt] 是这批 stories 实际从网络落盘的时刻(System.currentTimeMillis()):
 *  - 命中缓存秒回 → 是缓存写入时刻(可能已过去若干分钟,正是「上次刷新时间」)
 *  - 走网络刷新 → 是刚才
 *
 * UI 据此在顶栏显示「上次刷新 N 分钟前」,与 30 分钟缓存策略语义一致:
 * 用户关心的是「这份数据有多旧」,而非「ViewModel 何刻拿到数据」。
 *
 * @param fetchedAt 数据落盘时刻(缓存写入或刚抓取)
 * @param stories   story 列表(已排序,按下标即排名)
 */
data class HackerNewsTopStories(
    override val fetchedAt: Long,
    val stories: List<HackerNewsStory>
) : SourceListResult<HackerNewsStory> {
    override val items: List<HackerNewsStory> get() = stories
}

/**
 * HackerNews Top Stories 列表缓存条目。
 *
 * 持久化为 cacheDir 下的 JSON 文件,带写入时刻 [fetchedAt],用于计算是否过期。
 * 读取时按 30 分钟([HackerNewsRepository.CACHE_TTL_MS])判断新鲜度;
 * 网络失败时回退到过期数据兜底(有总比报错强)。
 *
 * @param fetchedAt 缓存写入时刻(System.currentTimeMillis())
 * @param stories   story 列表(已排序,按下标即排名)
 */
data class HackerNewsStoriesCache(
    val fetchedAt: Long,
    val stories: List<HackerNewsStory>
) {
    /** 序列化为可写入文件的 JSON(`{ fetchedAt, stories: [...] }`)。 */
    fun toJson(): JSONObject {
        val arr = JSONArray().apply {
            stories.forEach { put(JSONObject().apply {
                put("id", it.id)
                put("title", it.title)
                put("url", it.url)
                put("by", it.by)
                put("score", it.score)
                put("descendants", it.descendants)
                put("time", it.time)
                put("kids", JSONArray().apply { it.kids.forEach { k -> put(k) } })
            }) }
        }
        return JSONObject().apply {
            put("fetchedAt", fetchedAt)
            put("stories", arr)
        }
    }

    companion object {
        /** 从文件 JSON 反序列化;结构不符返回 null(调用方视为无缓存)。 */
        fun fromJson(json: JSONObject): HackerNewsStoriesCache? = runCatching {
            val arr = json.optJSONArray("stories") ?: return null
            val stories = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                HackerNewsStory.fromJson(o)
            }
            HackerNewsStoriesCache(
                fetchedAt = json.optLong("fetchedAt", 0L),
                stories = stories
            )
        }.getOrNull()
    }
}

/**
 * HackerNews 单条评论(Firebase item)。
 *
 * - text:   评论 HTML 原文(`<p>`/`<a>`/`<i>` 等),由 UI 层用 fromHtml 渲染
 * - kids:   子评论 id 列表(按 HN 排名顺序)。懒加载策略下仅记录 id,
 *           不预取整棵子树;UI 展开该评论时才按需拉取
 * - dead:   被 HN 折叠/标记的评论,UI 可弱化显示
 *
 * 层级(depth)不由数据模型持有 —— 懒加载树中层级由 UI 展开路径决定。
 */
@Immutable
data class HackerNewsComment(
    val id: Long,
    val by: String = "",
    val text: String = "",
    val time: Long = 0,
    val kids: List<Long> = emptyList(),
    val dead: Boolean = false
)

