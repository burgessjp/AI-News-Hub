package com.peng.ainewshub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File

/**
 * HuggingFace Trending Papers 抓取客户端。
 * 来源:https://huggingface.co/papers/trending(AK 每日精选 arXiv 论文,SSR HTML,无公开 JSON API)
 *
 * 与 [StormzhangAiNewsRepository] / [GitHubTrendingRepository] 同构:OkHttp 直连 +
 * 文件缓存 + stale 兜底 + Mutex 防并发重复请求。区别在:
 *  - 数据是 HTML → 用 jsoup 解析,缓存原始 HTML
 *  - 卡片选择器 `article.relative.overflow-hidden.rounded-xl.border`,字段抽取见 [HuggingFacePaper.fromArticle]
 *  - 带 CF 挑战页检测:huggingface.co 套 Cloudflare,异常时可能返回 HTML 挑战页
 *    而非论文列表,此时给出明确错误而非泛泛解析失败
 *  - TTL 4 小时(与 [GitHubTrendingRepository] 对齐)
 *
 * UA:站点对默认 OkHttp UA 偶尔差异对待,统一带浏览器 UA,复用 [NewsRepository] 同款字串。
 *
 * @param cacheDir 缓存根目录,通常传 application.cacheDir;传 null 关闭缓存
 */
class HuggingFacePapersRepository(
    private val cacheDir: File? = null
) : com.peng.ainewshub.data.source.HuggingFacePapersSource {

    private val papersUrl = "https://huggingface.co/papers/trending"

    /** 串行化 [fetch],避免短时间内并发刷新重复打网络。 */
    private val refreshMutex = Mutex()

    /**
     * 拉取 Trending Papers 列表,带 4 小时文件缓存。
     *
     * 缓存策略(仅在 [cacheDir] 非空时生效):
     *  1. 缓存存在且未过 4 小时 → 直接返回(不打网络)
     *  2. 否则走网络;成功则更新缓存后返回
     *  3. 网络失败但有缓存(无论是否过期)→ 回退缓存兜底,优先保可用
     *  4. 网络失败且无缓存 → 抛出原异常,交由 UI 显示错误态
     *
     * [refreshMutex] 保证并发调用只触发一次真实网络请求。
     *
     * @return 带数据落盘时刻的结果
     */
    override suspend fun fetch(): HuggingFacePapersResult {
        // 无缓存目录:退化为直连,fetchedAt 取当前时刻。
        if (cacheDir == null) {
            val papers = fetchFromNetwork()
            return HuggingFacePapersResult(System.currentTimeMillis(), papers)
        }

        return refreshMutex.withLock {
            val cached = readCache()
            // 1) 命中新缓存:秒回,不打网络。fetchedAt 用缓存写入时刻。
            if (cached != null && !isStale(cached.first)) {
                return@withLock HuggingFacePapersResult(cached.first, parse(cached.second))
            }
            // 2) 走网络刷新(仅一次)。
            val result = runCatching { fetchFromNetwork() }
            if (result.isSuccess) {
                val fresh = result.getOrThrow()
                val now = System.currentTimeMillis()
                writeCache(now, lastRawHtml)
                return@withLock HuggingFacePapersResult(now, fresh)
            }
            // 3) 网络失败:有过期缓存就兜底。fetchedAt 仍用缓存时刻,提示数据已旧。
            if (cached != null && cached.second.isNotBlank()) {
                return@withLock HuggingFacePapersResult(cached.first, parse(cached.second))
            }
            // 4) 既没缓存又没网络:抛出原始失败。
            throw result.exceptionOrNull() ?: RuntimeException("未知错误")
        }
    }

    /**
     * 强制忽略缓存重新抓取(下拉刷新等场景)。
     * 抓取成功后刷新缓存,fetchedAt 取当前时刻。
     */
    override suspend fun forceRefresh(): HuggingFacePapersResult {
        val fresh = fetchFromNetwork()
        val now = System.currentTimeMillis()
        if (cacheDir != null) {
            writeCache(now, lastRawHtml)
        }
        return HuggingFacePapersResult(now, fresh)
    }

    /** 最近一次抓取到的原始 HTML(供 [fetch] 在写入缓存时复用,避免重复抓取)。 */
    private var lastRawHtml: String = ""

    private suspend fun fetchFromNetwork(): List<HuggingFacePaper> = withContext(Dispatchers.IO) {
        val html = HttpClients.get(
            papersUrl,
            mapOf(
                "User-Agent" to HttpClients.DEFAULT_BROWSER_UA,
                "Accept" to "text/html,application/xhtml+xml",
                "Accept-Language" to "en-US,en;q=0.9,zh-CN,zh;q=0.8"
            )
        )
        // CF 挑战页检测:huggingface.co 套 Cloudflare,异常时返回 HTML 挑战页而非论文列表。
        // 常见特征:body 含 "Just a moment" 或标题为 CF 挑战页。
        if (html.contains("Just a moment", ignoreCase = true)) {
            throw AppException.RateLimited()
        }
        lastRawHtml = html
        parse(html)
    }

    /**
     * 用 jsoup 解析页面。
     *  - 论文卡片:`article.relative.overflow-hidden.rounded-xl.border`,按页面顺序编号
     *  - 卡片内字段抽取见 [HuggingFacePaper.fromArticle]
     *
     * HuggingFace 的 trending 列表已按热度排序,列表位置即排名,不做二次重排。
     */
    private fun parse(html: String): List<HuggingFacePaper> {
        val doc = Jsoup.parse(html)
        return doc.select("article.relative.overflow-hidden.rounded-xl.border")
            .mapIndexed { index, el ->
                HuggingFacePaper.fromArticle(el, index + 1)
            }
            .filterNotNull()
    }

    private companion object {
        /** 论文列表缓存有效期:4 小时(与 GitHubTrendingRepository 对齐)。 */
        const val CACHE_TTL_MS = 4L * 60 * 60 * 1000

        /** 缓存文件名(放在 [cacheDir] 下)。 */
        const val CACHE_FILE = "huggingface_papers.html"
    }

    // ===== 缓存读写(存原始 HTML + 写入时刻,首行注入时间戳头) =====

    private fun cacheFile(): File = File(cacheDir, CACHE_FILE)

    /**
     * 缓存格式:`<fetchedAt 毫秒>\n<原始 HTML>`(与 GitHubTrendingRepository 同套路)。
     * 用首行时间戳头简单拼接,避免引入 JSON 再包一层(原文已是 HTML)。
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
 * Trending Papers 拉取结果(带数据新鲜度),与 [TrendingResult] / [StormzhangAiNewsResult] 同构。
 *
 * [fetchedAt] 是这批数据实际落盘的时刻:命中缓存秒回时是缓存写入时刻(即「上次刷新时间」),
 * 走网络时是当前时刻。UI 据此在顶栏显示「上次刷新 N 分钟前」。
 */
data class HuggingFacePapersResult(
    val fetchedAt: Long,
    val papers: List<HuggingFacePaper>
)
