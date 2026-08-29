package com.peng.ainewshub.data

import androidx.compose.runtime.Immutable


import android.os.Parcelable
import kotlinx.parcelize.Parcelize
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
 * HackerNews Top Stories 拉取结果(带数据新鲜度),由归档 Repository 产出。
 *
 * [fetchedAt] 是归档快照顶层的 fetched_at_ms(数据落盘时刻),UI 据此在列表头
 * 显示「数据更新时间」——用户关心的是「这份数据有多旧」,而非「ViewModel
 * 何刻拿到数据」。
 *
 * @param fetchedAt 数据落盘时刻(快照 fetched_at_ms)
 * @param stories   story 列表(已排序,按下标即排名)
 */
data class HackerNewsTopStories(
    override val fetchedAt: Long,
    val stories: List<HackerNewsStory>
) : SourceListResult<HackerNewsStory> {
    override val items: List<HackerNewsStory> get() = stories
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

