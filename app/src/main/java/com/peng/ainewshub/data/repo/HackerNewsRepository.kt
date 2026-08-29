package com.peng.ainewshub.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.peng.ainewshub.data.model.HackerNewsComment
import com.peng.ainewshub.data.net.HttpClients
import com.peng.ainewshub.data.asClean

/**
 * HackerNews 公开 API 客户端 —— 评论页专用。
 * 文档: https://github.com/HackerNews/API (基于 Firebase,匿名免费,无速率限制)
 *
 * 与 [NewsRepository] 分离的原因:base 不同、响应结构不同(item 两步拉取)。
 *
 * 列表数据恒走归档([com.peng.ainewshub.data.source.HackerNewsArchiveRepository],
 * 原实时列表路径已随 LIVE 模式删除),本类只承担评论树的实时需求:归档快照不含
 * 评论树,进入评论页时经 [fetchStoryKids] 补全一级评论 id,再经 [fetchComments]
 * 按需懒加载各层。
 *
 * 重要约定:
 *  - 仅 HTTPS;cleartext 不需要(Firebase 默认 https)
 *  - 同层评论并发拉取,单条失败(返回 null)被静默跳过,不阻断其余
 */
class HackerNewsRepository {

    private val base = "https://hacker-news.firebaseio.com/v0"

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

    /**
     * 按 story id 实时拉取其一级评论 id 列表(kids)。
     *
     * 用途:归档快照列表项不带 kids(未存评论树),进入评论页时用它补全,
     * 再走 [fetchComments] 拉评论详情。Firebase API 匿名免费不限速,几乎不会失败。
     *
     * @return kids 列表;item 不存在或无评论时返回空列表
     */
    suspend fun fetchStoryKids(storyId: Long): List<Long> = withContext(Dispatchers.IO) {
        val obj = fetchItemJson(storyId) ?: return@withContext emptyList()
        val arr = obj.optJSONArray("kids") ?: return@withContext emptyList()
        (0 until arr.length()).map { arr.optLong(it) }
    }

    /**
     * 拉取单个 item 的 JSON;失败/404 返回 null(调用方跳过该条)。
     *
     * 经 [itemSemaphore] 节流:评论树并发拉取时限制同时在途的请求数,避免瞬间
     * 发起大量并发请求打满 OkHttp Dispatcher 连接数。
     */
    private suspend fun fetchItemJson(id: Long): JSONObject? = itemSemaphore.withPermit {
        runCatching {
            JSONObject(getRaw("$base/item/$id.json"))
        }.getOrNull()?.takeIf { it.optLong("id", -1L) != -1L }
    }

    private companion object {
        /** 每个节点最多展开的子评论数(按 HN 排名截断)。 */
        private const val MAX_CHILDREN_PER_NODE = 15

        /**
         * item 详情并发拉取上限。
         * Firebase API 匿名不限速,但 OkHttp Dispatcher 默认最多 64 并发、连接池 5 空闲,
         * 瞬间发起大量请求会排队且对端不友好。16 在并发效率与连接压力间取平衡。
         */
        private val itemSemaphore = Semaphore(16)
    }

    // asClean 逻辑收口于顶层扩展(data/JsonExt.kt),此处直接引用。

    private suspend fun getRaw(url: String): String =
        // Firebase API 匿名免费不限速,无需浏览器 UA(与第三方反爬源不同)。
        HttpClients.get(url, mapOf("Accept" to "application/json"))
}
