package com.peng.ainewshub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
 *
 * 缓存:[cacheDir] 非空时启用 Top Stories 文件缓存(见 [CACHE_TTL_MS]),
 * 4 小时内直接读缓存不打网络。网络失败时回退过期缓存兜底。
 *
 * @param cacheDir 缓存根目录,通常传 application.cacheDir;传 null 关闭缓存
 */
class HackerNewsRepository(
    private val cacheDir: File? = null
) : com.peng.ainewshub.data.source.HackerNewsSource {

    private val base = "https://hacker-news.firebaseio.com/v0"

    /** 串行化 [fetchTopStories],避免短时间内并发刷新重复打网络。 */
    private val refreshMutex = Mutex()

    /**
     * 接口 [HackerNewsSource.fetch] 的桥接实现 —— 转调既有 [fetchTopStories]。
     *
     * HN 历史 API 方法名是 fetchTopStories(带 limit),与其余 4 个源的 fetch() 不同。
     * 为让 ViewModel 统一依赖 [HackerNewsSource] 接口,这里提供一个同义的 fetch(),
     * 默认 limit=20(对齐 HackerNewsViewModel 既有的 20 条约定,而非 Repository 原默认 10)。
     */
    override suspend fun fetch(limit: Int): HackerNewsTopStories = fetchTopStories(limit)

    /**
     * HackerNews Top N(默认 10),带文件缓存。
     *
     * 缓存策略(仅在 [cacheDir] 非空时生效):
     *  1. 缓存存在且未过 4 小时 → 直接返回(不打网络)
     *  2. 否则走网络;成功则更新缓存后返回
     *  3. 网络失败但有缓存(无论是否过期)→ 回退缓存兜底,避免空报错
     *  4. 网络失败且无缓存 → 抛出原异常,交由 UI 显示错误态
     *
     * [refreshMutex] 保证并发调用只触发一次真实网络请求,其余等待复用结果。
     *
     * 返回值带 [HackerNewsTopStories.fetchedAt]:命中缓存时是缓存写入时刻
     * (即「上次刷新时间」),走网络时是当前时刻。UI 据此显示数据新鲜度。
     *
     * @param limit 取前 N 条(1-100,默认 10)
     */
    suspend fun fetchTopStories(limit: Int = 10): HackerNewsTopStories {
        // 无缓存目录:退化为原始直连网络行为,fetchedAt 取当前时刻。
        if (cacheDir == null) {
            return HackerNewsTopStories(System.currentTimeMillis(), fetchTopStoriesFromNetwork(limit))
        }

        return refreshMutex.withLock {
            val cached = readCache()
            // 1) 命中新缓存:秒回,不打网络。fetchedAt 用缓存写入时刻。
            if (cached != null && !isStale(cached)) {
                return@withLock HackerNewsTopStories(cached.fetchedAt, cached.stories)
            }
            // 2) 走网络刷新(仅一次)。
            val result = runCatching { fetchTopStoriesFromNetwork(limit) }
            if (result.isSuccess) {
                val fresh = result.getOrThrow()
                val now = System.currentTimeMillis()
                writeCache(HackerNewsStoriesCache(now, fresh))
                return@withLock HackerNewsTopStories(now, fresh)
            }
            // 3) 网络失败:有过期缓存就兜底,优先保可用。fetchedAt 仍用缓存时刻,
            //    让用户知道「这份数据其实已经过期 N 分钟」。
            if (cached != null && cached.stories.isNotEmpty()) {
                return@withLock HackerNewsTopStories(cached.fetchedAt, cached.stories)
            }
            // 4) 既没缓存又没网络:把原始失败抛出去,让 UI 显示重试。
            throw result.exceptionOrNull() ?: RuntimeException("未知错误")
        }
    }

    /**
     * 强制忽略缓存重新拉取(下拉刷新等场景)。
     * 拉取成功后仍会刷新缓存,使后续命中。fetchedAt 取当前时刻。
     */
    // 注:这里加 override 是为满足 [HackerNewsSource] 接口(forceRefresh(limit))。
    // 既有调用方(如 HackerNewsCommentsViewModel)直接调本方法,签名不变,零影响。
    override suspend fun forceRefresh(limit: Int): HackerNewsTopStories {
        val fresh = fetchTopStoriesFromNetwork(limit)
        val now = System.currentTimeMillis()
        if (cacheDir != null) {
            writeCache(HackerNewsStoriesCache(now, fresh))
        }
        return HackerNewsTopStories(now, fresh)
    }

    private suspend fun fetchTopStoriesFromNetwork(limit: Int): List<HackerNewsStory> = withContext(Dispatchers.IO) {
        val n = limit.coerceIn(1, 100)
        val ids = fetchIds("$base/topstories.json").take(n)
        if (ids.isEmpty()) return@withContext emptyList()

        // 并发拉取每条详情,单条失败(返回 null)被跳过,不拖垮整体(与 fetchComments 一致)。
        coroutineScope {
            ids.map { id -> async { fetchItemJson(id) } }.awaitAll()
        }.mapNotNull { obj -> obj?.let { HackerNewsStory.fromJson(it) } }
    }

    /** 拉取一个仅含 id 的 JSON 数组端点(topstories / newstories / beststories 等)。 */
    private suspend fun fetchIds(url: String): List<Long> {
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

    /**
     * 按 story id 实时拉取其一级评论 id 列表(kids)。
     *
     * 用途:归档模式下列表项没有 kids(归档快照未存评论树),进入评论页时用它补全,
     * 再走 [fetchComments] 拉评论详情。Firebase API 匿名免费不限速,几乎不会失败。
     *
     * @return kids 列表;item 不存在或无评论时返回空列表
     */
    suspend fun fetchStoryKids(storyId: Long): List<Long> = withContext(Dispatchers.IO) {
        val obj = fetchItemJson(storyId) ?: return@withContext emptyList()
        val arr = obj.optJSONArray("kids") ?: return@withContext emptyList()
        (0 until arr.length()).map { arr.optLong(it) }
    }

    /** 拉取单个 item 的 JSON;失败/404 返回 null(调用方跳过该条)。 */
    private suspend fun fetchItemJson(id: Long): JSONObject? = runCatching {
        JSONObject(getRaw("$base/item/$id.json"))
    }.getOrNull()?.takeIf { it.optLong("id", -1L) != -1L }

    private companion object {
        /** 每个节点最多展开的子评论数(按 HN 排名截断)。 */
        private const val MAX_CHILDREN_PER_NODE = 15

        /** Top Stories 缓存有效期:4 小时。 */
        const val CACHE_TTL_MS = 4L * 60 * 60 * 1000

        /** 缓存文件名(放在 [cacheDir] 下)。 */
        private const val CACHE_FILE = "hackernews_topstories.json"
    }

    // ===== 缓存读写 =====

    private fun cacheFile(): File = File(cacheDir, CACHE_FILE)

    private fun readCache(): HackerNewsStoriesCache? {
        val file = cacheFile()
        if (!file.exists()) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        return HackerNewsStoriesCache.fromJson(json)
    }

    private fun writeCache(cache: HackerNewsStoriesCache) {
        val dir = cacheDir ?: return
        if (!dir.exists()) dir.mkdirs()
        runCatching { cacheFile().writeText(cache.toJson().toString()) }
    }

    private fun isStale(cache: HackerNewsStoriesCache): Boolean =
        System.currentTimeMillis() - cache.fetchedAt > CACHE_TTL_MS

    // optString 在遇到 JSON null 时返回字面字符串 "null"(非空),需过滤。
    private fun String?.asClean(): String? = this?.takeIf { it.isNotBlank() && it != "null" }

    private suspend fun getRaw(url: String): String =
        // Firebase API 匿名免费不限速,无需浏览器 UA(与第三方反爬源不同)。
        HttpClients.get(url, mapOf("Accept" to "application/json"))
}
