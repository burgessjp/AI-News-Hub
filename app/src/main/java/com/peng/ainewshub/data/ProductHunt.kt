package com.peng.ainewshub.data

import androidx.compose.runtime.Immutable


import org.json.JSONObject

/**
 * Product Hunt 当日热门产品(来源:Product Hunt V2 GraphQL API,经数据流水线归档)。
 *
 * 与 [TrendingRepo] / [HuggingFacePaper] / [StormzhangAiNews] 平行:第六个独立热榜数据源。
 * Product Hunt 是全球最大的新产品发现社区,每日榜单(Product of the Day)是跟踪
 * 创新产品与 AI/开发者工具风向的常用入口。数据由 [scripts/fetch_data.py] 通过
 * GraphQL `posts(order:VOTES, postedAfter: 今日)` 抓取归档,App 端只读归档快照
 * (Developer Token 不进 APK)。
 *
 * 不加 @Parcelize:点击走 [url](PH 产品页,内置 WebView),URL 是普通字符串,无需跨页面传整个对象。
 *
 * @param rank          1 起的排名(由列表位置决定,API dailyRank 为当日综合榜,这里按 votes 序)
 * @param id            产品 id(GraphQL Post.id,字符串形态的数字)
 * @param slug          产品 slug,用于拼 PH 产品页
 * @param name          产品名
 * @param tagline       一句话价值定位
 * @param votesCount    社区 upvote 数(热度主指标)
 * @param commentsCount 评论数
 * @param website       产品官网/落地页(PH 跳转链接,含 utm;url 为空时回退用)
 * @param url           PH 产品页(点击优先用此)
 * @param createdAt     上线时间 ISO,如 "2026-07-18T07:01:00Z";原样展示不做解析
 * @param dailyRank     PH 当日综合榜排名(0 表示当日未上榜)
 * @param topics        话题标签,如 ["Developer Tools", "Artificial Intelligence"];至多 3 个
 * @param thumbnailUrl  产品主图 URL(PH thumbnail.url,列表缩略图用);无则为空
 */
@Immutable

data class ProductHunt(
    val rank: Int,
    val id: String,
    val slug: String,
    val name: String,
    val tagline: String = "",
    val votesCount: Int = 0,
    val commentsCount: Int = 0,
    val website: String = "",
    val url: String = "",
    val createdAt: String = "",
    val dailyRank: Int = 0,
    val topics: List<String> = emptyList(),
    val thumbnailUrl: String = ""
) {
    companion object {
        /**
         * 从归档快照的一个 item JSON 抽取产品信息。
         *
         * 字段名对齐 [scripts/fetch_data.py] 的 fetch_producthunt 落盘结构。
         * id 与 name 必有(对齐抓取端:缺则跳过);其余字段缺失走类型默认值。
         *
         * @return id 或 name 为空返回 null,调用方 mapNotNull 跳过
         */
        fun fromJson(obj: JSONObject, fallbackRank: Int): ProductHunt? {
            val id = obj.optString("id").trim()
            val name = obj.optString("name").trim()
            if (id.isBlank() || name.isBlank()) return null
            val topics = obj.optJSONArray("topics")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.opt(i)?.toString()?.trim()?.takeIf { it.isNotBlank() }
                }
            } ?: emptyList()
            return ProductHunt(
                rank = obj.optInt("rank", fallbackRank),
                id = id,
                slug = obj.optString("slug").trim(),
                name = name,
                tagline = obj.optString("tagline").trim(),
                votesCount = obj.optInt("votesCount", 0),
                commentsCount = obj.optInt("commentsCount", 0),
                website = obj.optString("website").trim(),
                url = obj.optString("url").trim(),
                createdAt = obj.optString("createdAt").trim(),
                dailyRank = obj.optInt("dailyRank", 0),
                topics = topics,
                thumbnailUrl = obj.optString("thumbnailUrl").trim()
            )
        }
    }

    /**
     * 点击优先 PH 产品页(url);产品页缺失时回退产品官网(website);都缺则空串。
     * 空串时 UI 不触发跳转(见 ProductHuntScreen)。
     */
    val targetUrl: String get() = url.ifBlank { website }
}

/**
 * Product Hunt 拉取结果(带数据新鲜度),对齐 [HuggingFacePapersResult]。
 *
 * @param fetchedAt 数据落盘时刻(归档快照的 fetched_at_ms)
 * @param products  当日热门产品列表
 */
data class ProductHuntResult(
    override val fetchedAt: Long,
    val products: List<ProductHunt>
) : SourceListResult<ProductHunt> {
    override val items: List<ProductHunt> get() = products
}
