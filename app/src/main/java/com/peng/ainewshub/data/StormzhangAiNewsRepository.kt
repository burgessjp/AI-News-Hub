package com.peng.ainewshub.data

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
 * stormzhang AI Daily 抓取客户端。
 * 来源:https://news.stormzhang.ai(每日 AI 资讯聚合,HTML 静态页,无 API)。
 *
 * 与 [GitHubTrendingRepository] 同构:OkHttp 直连 + 文件缓存 + stale 兜底 + Mutex
 * 防并发重复请求。区别在:
 *  - 数据是 HTML → 用 jsoup 解析,缓存原始 HTML
 *  - 额外抽取页面的「当日日期」(取自 `<title>` 如 "AI Daily — 2026.07.13"),
 *    供 UI 在顶栏副标题展示这批数据是哪一天的
 *  - TTL 4 小时(与 [GitHubTrendingRepository] 对齐)
 *
 * UA:站点对默认 OkHttp UA 偶尔差异对待,统一带浏览器 UA,复用 [NewsRepository] 同款字串。
 *
 * @param cacheDir 缓存根目录,通常传 application.cacheDir;传 null 关闭缓存
 */
class StormzhangAiNewsRepository(
    private val cacheDir: File? = null
) : com.peng.ainewshub.data.source.StormzhangAiNewsSource {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val newsUrl = "https://news.stormzhang.ai"
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** 串行化 [fetch],避免短时间内并发刷新重复打网络。 */
    private val refreshMutex = Mutex()

    /**
     * 拉取 AI 资讯列表,带 4 小时文件缓存。
     *
     * 缓存策略(仅在 [cacheDir] 非空时生效):
     *  1. 缓存存在且未过 4 小时 → 直接返回(不打网络)
     *  2. 否则走网络;成功则更新缓存后返回
     *  3. 网络失败但有缓存(无论是否过期)→ 回退缓存兜底,优先保可用
     *  4. 网络失败且无缓存 → 抛出原异常,交由 UI 显示错误态
     *
     * [refreshMutex] 保证并发调用只触发一次真实网络请求。
     *
     * @return 带数据落盘时刻的结果(含页面日期)
     */
    override suspend fun fetch(): StormzhangAiNewsResult {
        // 无缓存目录:退化为直连,fetchedAt 取当前时刻。
        if (cacheDir == null) {
            val parsed = fetchFromNetwork()
            return StormzhangAiNewsResult(System.currentTimeMillis(), parsed.first, parsed.second)
        }

        return refreshMutex.withLock {
            val cached = readCache()
            // 1) 命中新缓存:秒回,不打网络。fetchedAt 用缓存写入时刻。
            if (cached != null && !isStale(cached.first)) {
                val (news, date) = parse(cached.second)
                return@withLock StormzhangAiNewsResult(cached.first, news, date)
            }
            // 2) 走网络刷新(仅一次)。
            val result = runCatching { fetchFromNetwork() }
            if (result.isSuccess) {
                val (fresh, date) = result.getOrThrow()
                val now = System.currentTimeMillis()
                writeCache(now, lastRawHtml)
                return@withLock StormzhangAiNewsResult(now, fresh, date)
            }
            // 3) 网络失败:有过期缓存就兜底。fetchedAt 仍用缓存时刻,提示数据已旧。
            if (cached != null && cached.second.isNotBlank()) {
                val (news, date) = parse(cached.second)
                return@withLock StormzhangAiNewsResult(cached.first, news, date)
            }
            // 4) 既没缓存又没网络:抛出原始失败。
            throw result.exceptionOrNull() ?: RuntimeException("未知错误")
        }
    }

    /**
     * 强制忽略缓存重新抓取(下拉刷新等场景)。
     * 抓取成功后刷新缓存,fetchedAt 取当前时刻。
     */
    override suspend fun forceRefresh(): StormzhangAiNewsResult {
        val (fresh, date) = fetchFromNetwork()
        val now = System.currentTimeMillis()
        if (cacheDir != null) {
            writeCache(now, lastRawHtml)
        }
        return StormzhangAiNewsResult(now, fresh, date)
    }

    /** 最近一次抓取到的原始 HTML(供 [fetch] 在写入缓存时复用,避免重复抓取)。 */
    private var lastRawHtml: String = ""

    private suspend fun fetchFromNetwork(): Pair<List<StormzhangAiNews>, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(newsUrl)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw AppException.Network()
            }
            val html = resp.body?.string() ?: throw AppException.Network()
            lastRawHtml = html
            parse(html)
        }
    }

    /**
     * 用 jsoup 解析页面。
     *  - 资讯条目:`a.item`,按页面顺序编号
     *  - 页面日期:取自 `<title>`(形如 "AI Daily — 2026.07.13")的日期段
     *
     * @return (资讯列表, 页面日期字符串;解析不到时为空)
     */
    private fun parse(html: String): Pair<List<StormzhangAiNews>, String> {
        val doc = Jsoup.parse(html)
        val news = doc.select("a.item").mapIndexed { index, el ->
            StormzhangAiNews.fromItem(el, index + 1)
        }.filterNotNull()

        // 页面日期:优先 <title> 中 "— YYYY.MM.DD" 段;取不到再回退 <meta property="article:...">。
        val title = doc.title()
        val date = TITLE_DATE_RE.find(title)?.value
            ?: ""
        return news to date
    }

    private companion object {
        /** 资讯缓存有效期:4 小时(与 GitHubTrendingRepository 对齐)。 */
        const val CACHE_TTL_MS = 4L * 60 * 60 * 1000

        /** 缓存文件名(放在 [cacheDir] 下)。 */
        const val CACHE_FILE = "stormzhang_ai_news.html"

        /** 从 title 中提取日期段,如 "AI Daily — 2026.07.13" → "2026.07.13"。 */
        val TITLE_DATE_RE = Regex("\\d{4}\\.\\d{2}\\.\\d{2}")
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
 * AI 资讯拉取结果(带数据新鲜度),与 [TrendingResult] 同构。
 *
 * [fetchedAt] 是这批数据实际落盘的时刻:命中缓存秒回时是缓存写入时刻(即「上次刷新时间」),
 * 走网络时是当前时刻。UI 据此在顶栏显示「上次刷新 N 分钟前」。
 *
 * [pageDate] 是页面声明的资讯日期(如 "2026.07.13"),取自 title,解析不到时为空。
 */
data class StormzhangAiNewsResult(
    val fetchedAt: Long,
    val news: List<StormzhangAiNews>,
    val pageDate: String = ""
)
