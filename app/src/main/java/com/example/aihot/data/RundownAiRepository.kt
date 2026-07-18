package com.example.aihot.data

import com.example.aihot.data.source.RundownAiResult
import com.example.aihot.data.source.RundownAiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Rundown AI 首页文章卡片墙抓取客户端(实时源)。
 * 来源:https://www.therundown.ai/(beehiiv 托管的 AI newsletter,首页静态 HTML)。
 *
 * 与 [StormzhangAiNewsRepository] 同构:OkHttp 直连 + 文件缓存 + stale 兜底 + Mutex
 * 防并发重复请求。区别在:
 *  - 选择器 `a[href^="/p/"]`,每张卡片含「标题 | PLUS:副标题 | 作者」三段文本 +
 *    一张封面图(beehiiv cdn-cgi 图,需排除 width=256 的作者头像)
 *  - 列表页无日期字段(beehiiv 首页不放文章日期),不抽 pageDate
 *  - TTL 4 小时(与 [StormzhangAiNewsRepository] 对齐)
 *
 * UA:站点对默认 OkHttp UA 偶尔差异对待,统一带浏览器 UA,复用 [NewsRepository] 同款字串。
 *
 * @param cacheDir 缓存根目录,通常传 application.cacheDir;传 null 关闭缓存
 */
class RundownAiRepository(
    private val cacheDir: File? = null
) : RundownAiSource {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val homeUrl = "https://www.therundown.ai/"
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** 串行化 [fetch],避免短时间内并发刷新重复打网络。 */
    private val refreshMutex = Mutex()

    /**
     * 拉取文章列表,带 4 小时文件缓存。
     *
     * 缓存策略(仅在 [cacheDir] 非空时生效):
     *  1. 缓存存在且未过 4 小时 → 直接返回(不打网络)
     *  2. 否则走网络;成功则更新缓存后返回
     *  3. 网络失败但有缓存(无论是否过期)→ 回退缓存兜底,优先保可用
     *  4. 网络失败且无缓存 → 抛出原异常,交由 UI 显示错误态
     *
     * [refreshMutex] 保证并发调用只触发一次真实网络请求。
     */
    override suspend fun fetch(): RundownAiResult {
        if (cacheDir == null) {
            val parsed = fetchFromNetwork()
            return RundownAiResult(System.currentTimeMillis(), parsed)
        }

        return refreshMutex.withLock {
            val cached = readCache()
            if (cached != null && !isStale(cached.first)) {
                return@withLock RundownAiResult(cached.first, parse(cached.second))
            }
            val result = runCatching { fetchFromNetwork() }
            if (result.isSuccess) {
                val fresh = result.getOrThrow()
                val now = System.currentTimeMillis()
                writeCache(now, lastRawHtml)
                return@withLock RundownAiResult(now, fresh)
            }
            if (cached != null && cached.second.isNotBlank()) {
                return@withLock RundownAiResult(cached.first, parse(cached.second))
            }
            throw result.exceptionOrNull() ?: RuntimeException("未知错误")
        }
    }

    /**
     * 强制忽略缓存重新抓取(下拉刷新等场景)。
     * 抓取成功后刷新缓存,fetchedAt 取当前时刻。
     */
    override suspend fun forceRefresh(): RundownAiResult {
        val fresh = fetchFromNetwork()
        val now = System.currentTimeMillis()
        if (cacheDir != null) {
            writeCache(now, lastRawHtml)
        }
        return RundownAiResult(now, fresh)
    }

    /** 最近一次抓取到的原始 HTML(供 [fetch] 在写入缓存时复用,避免重复抓取)。 */
    private var lastRawHtml: String = ""

    private suspend fun fetchFromNetwork(): List<RundownAiArticle> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(homeUrl)
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

    /**
     * 用 jsoup 解析首页文章卡片墙。
     *
     * 选择器 `a[href^="/p/"]`:beehiiv 首页每张文章卡是 `<a href="/p/<slug>">`,
     * 卡内 getText(" | ") 合并出「标题 | PLUS:副标题 | 作者, +N」三段文本。
     * 同一 slug 可能被多个锚点引用(如「最新」「精选」重复),按 slug 去重取首次出现。
     *
     * 封面图:卡内 `<img>` 中,作者头像是 `width=256` 规格的图,排除后取首张
     * beehiiv cdn-cgi 图作为封面。
     */
    private fun parse(html: String): List<RundownAiArticle> {
        val doc = Jsoup.parse(html)
        val seenSlugs = mutableSetOf<String>()
        val articles = mutableListOf<RundownAiArticle>()
        var rank = 0
        for (el in doc.select("a[href^=/p/]")) {
            val href = el.attr("href").trim()
            val slug = href.substringBefore("?").substringBefore("#")
                .removePrefix("/p/").trim('/')
            if (slug.isBlank() || slug in seenSlugs) continue

            val rawText = el.textNodes().joinToString(" | ") { it.text().trim() }
                .replace(Regex("\\s+"), " ")
                .trim()
            val (title, subtitle, authors) = splitCardText(rawText)
            if (title.isBlank()) continue

            // 封面图:排除作者头像(width=256 是 beehiiv 头像规格特征),
            // 取首张 beehiiv cdn-cgi 图(对齐 fetch_data.py 的判别逻辑)。
            val coverUrl = el.select("img").firstOrNull { img ->
                val src = img.attr("src")
                src.isNotBlank() &&
                    !src.contains("width=256") &&
                    (src.contains("beehiiv.com/cdn-cgi/image") || src.contains("beehiiv-images-production"))
            }?.attr("src")?.trim().orEmpty()

            rank += 1
            seenSlugs.add(slug)
            articles.add(
                RundownAiArticle(
                    rank = rank,
                    slug = slug,
                    url = "https://www.therundown.ai/p/$slug",
                    title = title,
                    subtitle = subtitle,
                    authors = authors,
                    coverUrl = coverUrl
                )
            )
        }
        return articles
    }

    /**
     * 拆 The Rundown AI 首页卡片的合并文本「标题 | PLUS: 副标题 | 作者, +N」。
     *
     * @return (title, subtitle, authors),任何一段缺失返回空串
     */
    private fun splitCardText(text: String): Triple<String, String, String> {
        if (text.isBlank()) return Triple("", "", "")
        val parts = text.split(" | ").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return Triple("", "", "")
        val title = parts.first()
        var subtitle = ""
        var authors = ""
        for (p in parts.drop(1)) {
            if (p.uppercase().startsWith("PLUS")) {
                subtitle = Regex("^PLUS:\\s*", RegexOption.IGNORE_CASE).replace(p, "").trim()
            } else {
                authors = p
            }
        }
        return Triple(title, subtitle, authors)
    }

    private companion object {
        /** 文章缓存有效期:4 小时(与 StormzhangAiNewsRepository 对齐)。 */
        const val CACHE_TTL_MS = 4L * 60 * 60 * 1000

        /** 缓存文件名(放在 [cacheDir] 下)。 */
        const val CACHE_FILE = "rundown_ai.html"
    }

    // ===== 缓存读写(存原始 HTML + 写入时刻,首行注入时间戳头) =====

    private fun cacheFile(): File = File(cacheDir, CACHE_FILE)

    /**
     * 缓存格式:`<fetchedAt 毫秒>\n<原始 HTML>`(与 StormzhangAiNewsRepository 同套路)。
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
