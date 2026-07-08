package com.example.aihot.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HackerNews 公开 API 客户端。
 * 文档: https://github.com/HackerNews/API (基于 Firebase,匿名免费,无速率限制)
 *
 * 与 [NewsRepository] 分离的原因:base 不同、响应结构不同(两步拉取:
 * 先取 topstories 的 id 数组,再逐条拉取 item 详情)。
 *
 * 重要约定:
 *  - 仅 HTTPS;cleartext 不需要(Firebase 默认 https)
 *  - topstories 最多 500 条,这里取前 [limit] 条
 *  - item 详情并发拉取,任一条失败则整体回退到错误态(由 ViewModel 处理)
 */
class HackerNewsRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val base = "https://hacker-news.firebaseio.com/v0"

    /**
     * HackerNews Top N(默认 10)。
     *
     * 流程:GET /topstories.json → 取前 [limit] 个 id → 并发 GET /item/<id>.json。
     * id 数组已按 HN 首页排序,topstories 的顺序即排名,故直接按下标取前 N 即可。
     *
     * @param limit 取前 N 条(1-100,默认 10)
     */
    suspend fun fetchTopStories(limit: Int = 10): List<HackerNewsStory> = withContext(Dispatchers.IO) {
        val n = limit.coerceIn(1, 100)
        val ids = fetchIds("$base/topstories.json").take(n)
        if (ids.isEmpty()) return@withContext emptyList()

        // 并发拉取每条详情;awaitAll 保证全部成功或整体失败。
        coroutineScope {
            ids.map { id ->
                async {
                    val body = getRaw("$base/item/$id.json")
                    HackerNewsStory.fromJson(JSONObject(body))
                }
            }.awaitAll()
        }
    }

    /** 拉取一个仅含 id 的 JSON 数组端点(topstories / newstories / beststories 等)。 */
    private fun fetchIds(url: String): List<Long> {
        val arr = JSONArray(getRaw(url))
        return (0 until arr.length()).map { arr.optLong(it) }
    }

    // ===== 评论树 =====

    /**
     * 拉取指定 id 列表对应的**一层**评论(懒加载,不递归)。
     *
     * 策略:评论树可能极大,按需加载 —— 首次拉取 story 的一级评论 id
     * ([HackerNewsStory.kids]),用户展开某条评论时再用其 [HackerNewsComment.kids]
     * 调本方法拉取下一层。每条评论保留其 `kids` id 列表,但不预取整棵子树。
     *
     * 同层用 coroutineScope + async 并发拉取、awaitAll 保持原顺序;
     * 拉取失败的条目(fetchItemJson 返回 null)被静默跳过,不阻断其余评论。
     *
     * @param ids 要拉取的评论 id 列表(按 HN 排名顺序)
     * @return 该层评论列表(跳过 deleted / 无 text 的);空列表表示无内容
     */
    suspend fun fetchComments(ids: List<Long>): List<HackerNewsComment> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val limited = ids.take(MAX_CHILDREN_PER_NODE)
        val objs = coroutineScope {
            limited.map { id -> async { fetchItemJson(id) } }.awaitAll()
        }
        objs.mapNotNull { obj ->
            if (obj == null) return@mapNotNull null
            // deleted 的评论 HN 不返回文本,跳过。
            if (obj.optBoolean("deleted", false)) return@mapNotNull null
            val text = obj.optString("text").asClean().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val kids = obj.optJSONArray("kids")?.let { arr ->
                (0 until arr.length()).map { arr.optLong(it) }
            } ?: emptyList()
            HackerNewsComment(
                id = obj.optLong("id"),
                by = obj.optString("by").asClean().orEmpty(),
                text = text,
                time = obj.optLong("time", 0L),
                kids = kids,
                dead = obj.optBoolean("dead", false)
            )
        }
    }

    /** 拉取单个 item 的 JSON;失败/404 返回 null(调用方跳过该条)。 */
    private fun fetchItemJson(id: Long): JSONObject? = runCatching {
        JSONObject(getRaw("$base/item/$id.json"))
    }.getOrNull()?.takeIf { it.optLong("id", -1L) != -1L }

    private companion object {
        /** 每个节点最多展开的子评论数(按 HN 排名截断)。 */
        private const val MAX_CHILDREN_PER_NODE = 15
    }

    // optString 在遇到 JSON null 时返回字面字符串 "null"(非空),需过滤。
    private fun String?.asClean(): String? = this?.takeIf { it.isNotBlank() && it != "null" }

    private fun getRaw(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}")
            }
            return resp.body?.string() ?: throw RuntimeException("空响应")
        }
    }
}
