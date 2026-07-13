package com.example.aihot.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GitHub Trending 抓取客户端。
 * 来源:https://github.com/trending(无官方 API,抓 HTML 用 jsoup 解析)。
 *
 * 与 [HackerNewsRepository] 同构:OkHttp 直连 + 文件缓存 + stale 兜底 + Mutex
 * 防并发重复请求。区别在:
 *  - 数据是 HTML 不是 JSON → 用 jsoup 解析
 *  - 缓存原始 HTML 字符串(而非结构化 JSON):trending 字段未来可能扩,
 *    缓存原文让解析逻辑单点维护,无需同步缓存 schema
 *  - TTL 4 小时(与 [HackerNewsRepository] 对齐)
 *
 * UA:GitHub 对默认 OkHttp UA 偶尔差异对待(可能裁剪条目),统一带浏览器 UA,
 * 复用 [NewsRepository] 同款字串。
 *
 * @param cacheDir 缓存根目录,通常传 application.cacheDir;传 null 关闭缓存
 */
class GitHubTrendingRepository(
    private val cacheDir: File? = null
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val trendingUrl = "https://github.com/trending"
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** 串行化 [fetch],避免短时间内并发刷新重复打网络。 */
    private val refreshMutex = Mutex()

    /**
     * 拉取 trending 仓库列表,带 4 小时文件缓存。
     *
     * 缓存策略(仅在 [cacheDir] 非空时生效):
     *  1. 缓存存在且未过 4 小时 → 直接返回(不打网络)
     *  2. 否则走网络;成功则更新缓存后返回
     *  3. 网络失败但有缓存(无论是否过期)→ 回退缓存兜底,优先保可用
     *  4. 网络失败且无缓存 → 抛出原异常,交由 UI 显示错误态
     *
     * [refreshMutex] 保证并发调用只触发一次真实网络请求。
     *
     * @return 带数据落盘时刻的 trending 结果
     */
    suspend fun fetch(): TrendingResult {
        // 无缓存目录:退化为直连,fetchedAt 取当前时刻。
        if (cacheDir == null) {
            val repos = fetchFromNetwork()
            return TrendingResult(System.currentTimeMillis(), repos)
        }

        return refreshMutex.withLock {
            val cached = readCache()
            // 1) 命中新缓存:秒回,不打网络。fetchedAt 用缓存写入时刻。
            if (cached != null && !isStale(cached.first)) {
                return@withLock TrendingResult(cached.first, parse(cached.second))
            }
            // 2) 走网络刷新(仅一次)。
            val result = runCatching { fetchFromNetwork() }
            if (result.isSuccess) {
                val fresh = result.getOrThrow()
                val now = System.currentTimeMillis()
                // 缓存原文 HTML(已在 fetchFromNetwork 内取到),这里复用解析后结果即可。
                // 注意:需重新抓一次原文写盘会浪费请求,故 fetchFromNetwork 内部缓存原文。
                writeCache(now, lastRawHtml)
                return@withLock TrendingResult(now, fresh)
            }
            // 3) 网络失败:有过期缓存就兜底。fetchedAt 仍用缓存时刻,提示数据已旧。
            if (cached != null && cached.second.isNotBlank()) {
                return@withLock TrendingResult(cached.first, parse(cached.second))
            }
            // 4) 既没缓存又没网络:抛出原始失败。
            throw result.exceptionOrNull() ?: RuntimeException("未知错误")
        }
    }

    /**
     * 强制忽略缓存重新抓取(下拉刷新等场景)。
     * 抓取成功后刷新缓存,fetchedAt 取当前时刻。
     */
    suspend fun forceRefresh(): TrendingResult {
        val fresh = fetchFromNetwork()
        val now = System.currentTimeMillis()
        if (cacheDir != null) {
            writeCache(now, lastRawHtml)
        }
        return TrendingResult(now, fresh)
    }

    /** 最近一次抓取到的原始 HTML(供 [fetch] 在写入缓存时复用,避免重复抓取)。 */
    private var lastRawHtml: String = ""

    private suspend fun fetchFromNetwork(): List<TrendingRepo> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(trendingUrl)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}")
            }
            val html = resp.body?.string() ?: throw RuntimeException("空响应")
            lastRawHtml = html
            parse(html)
        }
    }

    /** 用 jsoup 解析 trending HTML;条目数动态,返回多少解析多少。 */
    private fun parse(html: String): List<TrendingRepo> {
        val doc = Jsoup.parse(html)
        return doc.select("article.Box-row").mapIndexed { index, el ->
            // index 未命中时 fromArticle 返回 null,mapNotNull 跳过,rank 可能不连续;
            // 这里用 index+1 作为初始 rank,失败项不影响后续排名(排名本质是页面顺序)。
            TrendingRepo.fromArticle(el, index + 1)
        }.filterNotNull()
    }

    private companion object {
        /** Trending 缓存有效期:4 小时(与 HackerNewsRepository 对齐)。 */
        const val CACHE_TTL_MS = 4L * 60 * 60 * 1000

        /** 缓存文件名(放在 [cacheDir] 下)。 */
        const val CACHE_FILE = "github_trending.html"
    }

    // ===== 缓存读写(存原始 HTML + 写入时刻,首行注入时间戳头) =====

    private fun cacheFile(): File = File(cacheDir, CACHE_FILE)

    /**
     * 缓存格式:`<fetchedAt 毫秒>\n<原始 HTML>`。
     * 用首行时间戳头简单拼接,避免引入 JSON 序列化(原文已是 HTML,再包 JSON 转义冗长)。
     */
    private fun readCache(): Pair<Long, String>? {
        val file = cacheFile()
        if (!file.exists()) return null
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        val nl = raw.indexOf('\n')
        if (nl <= 0) return null
        val ts = raw.substring(0, nl).trim().toLongOrNull() ?: return null
        val html = raw.substring(nl + 1)
        return ts to html
    }

    private fun writeCache(fetchedAt: Long, html: String) {
        val dir = cacheDir ?: return
        if (!dir.exists()) dir.mkdirs()
        if (html.isBlank()) return
        runCatching { cacheFile().writeText("$fetchedAt\n$html") }
    }

    private fun isStale(fetchedAt: Long): Boolean =
        System.currentTimeMillis() - fetchedAt > CACHE_TTL_MS
}

/**
 * Trending 拉取结果(带数据新鲜度),与 [HackerNewsTopStories] 同构。
 *
 * [fetchedAt] 是这批数据实际落盘的时刻:命中缓存秒回时是缓存写入时刻(即「上次刷新时间」),
 * 走网络时是当前时刻。UI 据此在顶栏显示「上次刷新 N 分钟前」。
 */
data class TrendingResult(
    val fetchedAt: Long,
    val repos: List<TrendingRepo>
)
