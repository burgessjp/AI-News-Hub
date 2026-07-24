package com.peng.ainewshub.data

import org.json.JSONObject

/**
 * LinuxDo 热榜单条话题(来源:https://linux.do/hot.json)。
 *
 * 与 [TrendingRepo] / [NewsItem] 平行:LinuxDo 是第四个独立数据源,
 * 字段语义(浏览/回复/点赞/标签/置顶)与前两者完全不同,故单独建模。
 *
 * 不加 @Parcelize:点击话题走 [url](内置 WebView),URL 是普通字符串,
 * 无需把整个对象跨页面传递。
 *
 * @param rank        热榜排名(1 起)。置顶帖 [pinned]=true 时 rank=0,不参与编号
 * @param title       话题标题
 * @param url         话题完整 HTTPS 地址(https://linux.do/t/topic/{id})
 * @param excerpt     首帖摘要(已反转义 HTML 实体);可能为空
 * @param authorName  原始发帖人显示名(优先 name,空则回退 username);可能为空
 * @param avatarUrl   作者头像完整 URL(avatar_template 补全前缀 + {size}→48);可能为空
 * @param views       浏览数
 * @param replyCount  回复数
 * @param likeCount   点赞数
 * @param tags        标签名列表(取前 2 个,空列表则不渲染)
 * @param createdAtMs 创建时刻(毫秒),用于相对时间显示
 * @param pinned      是否全局置顶(站公告等常驻帖,单独标记不参与排名编号)
 * @param closed      是否已关闭
 */
data class LinuxDoTopic(
    val rank: Int,
    val title: String,
    val url: String,
    val excerpt: String = "",
    val authorName: String = "",
    val avatarUrl: String = "",
    val views: Int = 0,
    val replyCount: Int = 0,
    val likeCount: Int = 0,
    val tags: List<String> = emptyList(),
    val createdAtMs: Long = 0L,
    val pinned: Boolean = false,
    val closed: Boolean = false
) {
    companion object {
        /**
         * 从一个 topic JSON 对象抽取话题信息。
         *
         * 作者/头像取自 posters[0](description=="原始发帖人") 的 user_id,
         * 在 [usersById] 索引里查 name / username / avatar_template。
         *
         * @param topic     topic_list.topics[] 单个元素
         * @param usersById users[] 按 id 索引(id → user JSON)
         * @param rank      调用方计算的排名(置顶帖传 0)
         * @return 标题或 id 缺失返回 null,调用方 filterNotNull 跳过
         */
        fun fromJson(
            topic: JSONObject,
            usersById: Map<Int, JSONObject>,
            rank: Int
        ): LinuxDoTopic? {
            val id = topic.optInt("id", -1)
            if (id <= 0) return null
            val title = topic.optString("title").takeIf { it.isNotBlank() }
                ?: topic.optString("fancy_title").takeIf { it.isNotBlank() }
                ?: return null

            // 作者:user_id 取自 posters[0](原始发帖人);找不到则回退 posters 第一个。
            val opUserId = topic.optJSONArray("posters")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { it.optString("description").contains("原始发帖") }
                    ?.optInt("user_id", -1)
                    ?: arr.optJSONObject(0)?.optInt("user_id", -1)
            } ?: -1
            val user = usersById[opUserId]
            val authorName = user?.optString("name")?.takeIf { it.isNotBlank() }
                ?: user?.optString("username") ?: ""
            val avatarUrl = user?.optString("avatar_template")
                ?.takeIf { it.isNotBlank() }
                ?.let { resolveAvatar(it) } ?: ""

            val excerpt = topic.optString("excerpt")
                .takeIf { it.isNotBlank() }
                ?.let { HtmlUtil.stripHtml(it) } ?: ""

            val tags = topic.optJSONArray("tags")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
                }
            } ?: emptyList()

            return LinuxDoTopic(
                rank = rank,
                title = title,
                url = "https://linux.do/t/topic/$id",
                excerpt = excerpt,
                authorName = authorName,
                avatarUrl = avatarUrl,
                views = topic.optInt("views", 0),
                replyCount = topic.optInt("reply_count", 0),
                likeCount = topic.optInt("like_count", 0),
                tags = tags.take(2),
                createdAtMs = parseIsoMillis(topic.optString("created_at")),
                pinned = topic.optBoolean("pinned_globally", false) ||
                    topic.optBoolean("pinned", false),
                closed = topic.optBoolean("closed", false)
            )
        }

        /**
         * 补全头像 URL:
         *  - avatar_template 形如 "/user_avatar/linux.do/neo/{size}/12_2.png"
         *    或 "//linuxdo-uploads.s3.ldstatic.com/..."(协议相对)
         *    或 "https://..."(已是绝对地址)
         *  - {size} 替换为 48(列表缩略图够用,省流量)
         *  - 相对路径补 "https://linux.do" 前缀
         */
        private fun resolveAvatar(template: String): String {
            val sized = template.replace("{size}", "48")
            return when {
                sized.startsWith("https://") || sized.startsWith("http://") -> sized
                sized.startsWith("//") -> "https:$sized"
                sized.startsWith("/") -> "https://linux.do$sized"
                else -> sized
            }
        }

        /** 解析 ISO 8601(如 "2026-07-13T04:28:29.805Z")为毫秒;失败返回 0。 */
        private fun parseIsoMillis(iso: String): Long =
            runCatching {
                if (iso.isBlank()) return@runCatching 0L
                // 兼容带毫秒和不带毫秒两种形态;用 SimpleDateFormat 避免 API 26+ 依赖。
                val pattern = if (iso.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" else "yyyy-MM-dd'T'HH:mm:ss'Z'"
                java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(iso)?.time ?: 0L
            }.getOrDefault(0L)
    }
}
