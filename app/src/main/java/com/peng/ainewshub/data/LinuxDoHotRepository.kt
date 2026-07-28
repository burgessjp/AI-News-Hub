package com.peng.ainewshub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * LinuxDo 热榜抓取客户端。
 * 来源:https://linux.do/hot.json(Discourse JSON API)
 *
 * 与 [GitHubTrendingRepository] / [NewsRepository] 同构:OkHttp 直连 + 文件缓存 +
 * stale 兜底 + Mutex 防并发重复请求。区别在:
 *  - 缓存原始 JSON 字符串(而非结构化对象):字段未来可能扩,缓存原文让解析逻辑
 *    单点维护,无需同步缓存 schema
 *  - TTL 4 小时(与 [GitHubTrendingRepository] 对齐)
 *  - 带 CF 挑战页检测:linux.do 套 Cloudflare,异常情况下可能返回 HTML 挑战页
 *    而非 JSON,此时给出明确错误而非泛泛解析失败
 *
 * UA:Discourse 对默认 OkHttp UA 偶尔差异对待,统一带浏览器 UA(复用 [NewsRepository]
 * 同款字串)。
 *
 * @param cacheDir 缓存根目录,通常传 application.cacheDir;传 null 关闭缓存
 */
class LinuxDoHotRepository(
    private val cacheDir: File? = null
) {

    private val client = HttpClients.base

    private val hotUrl = "https://linux.do/c/develop/4/l/hot.json"
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** 串行化 [fetch],避免短时间内并发刷新重复打网络。 */
    private val refreshMutex = Mutex()

    /**
     * 拉取热榜,带 4 小时文件缓存。
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
    suspend fun fetch(): LinuxDoResult {
        if (cacheDir == null) {
            val topics = fetchFromNetwork()
            return LinuxDoResult(System.currentTimeMillis(), topics)
        }

        return refreshMutex.withLock {
            val cached = readCache()
            // 1) 命中新缓存:秒回,不打网络。fetchedAt 用缓存写入时刻。
            if (cached != null && !isStale(cached.first)) {
                return@withLock LinuxDoResult(cached.first, parse(cached.second))
            }
            // 2) 走网络刷新(仅一次)。
            val result = runCatching { fetchFromNetwork() }
            if (result.isSuccess) {
                val fresh = result.getOrThrow()
                val now = System.currentTimeMillis()
                writeCache(now, lastRawJson)
                return@withLock LinuxDoResult(now, fresh)
            }
            // 3) 网络失败:有过期缓存就兜底。fetchedAt 仍用缓存时刻,提示数据已旧。
            if (cached != null && cached.second.isNotBlank()) {
                return@withLock LinuxDoResult(cached.first, parse(cached.second))
            }
            // 4) 既没缓存又没网络:抛出原始失败。
            throw result.exceptionOrNull() ?: RuntimeException("未知错误")
        }
    }

    /** 强制忽略缓存重新抓取(下拉刷新等场景)。 */
    suspend fun forceRefresh(): LinuxDoResult {
        val fresh = fetchFromNetwork()
        val now = System.currentTimeMillis()
        if (cacheDir != null) {
            writeCache(now, lastRawJson)
        }
        return LinuxDoResult(now, fresh)
    }

    /** 最近一次抓取到的原始 JSON(供 [fetch] 写缓存复用,避免重复抓)。 */
    private var lastRawJson: String = ""

    private suspend fun fetchFromNetwork(): List<LinuxDoTopic> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(hotUrl)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw AppException.Network()
            }
            val raw = resp.body?.string() ?: throw AppException.Network()
            // CF 挑战页检测:linux.do 套 Cloudflare,异常时返回 HTML 挑战页而非 JSON。
            // 常见特征:body 以 "<" 开头(<html / <!doctype)或含 "Just a moment"。
            // 此时给出明确错误,而非让 JSONObject 解析抛泛泛的"非 JSON"。
            val trimmed = raw.trimStart()
            if (trimmed.startsWith("<") || raw.contains("Just a moment", ignoreCase = true)) {
                throw AppException.RateLimited()
            }
            lastRawJson = raw
            parse(raw)
        }
    }

    /**
     * 解析 hot.json:
     *  - users[]:扁平用户列表,按 id 建索引供 topic 查作者
     *  - topic_list.topics[]:话题列表,保留页面顺序作为热度排序
     *
     * 排名规则:置顶帖(pinned_globally) rank=0 不参与编号;
     * 非置顶帖按出现顺序编 1, 2, 3...(Discourse hot 已按热度排序)。
     */
    private fun parse(rawJson: String): List<LinuxDoTopic> {
        val root = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: throw AppException.ServerError()
        val usersById = root.optJSONArray("users")?.let { arr ->
            (0 until arr.length()).associate { arr.getJSONObject(it).optInt("id") to arr.getJSONObject(it) }
        } ?: emptyMap()
        val topics = root.optJSONObject("topic_list")?.optJSONArray("topics") ?: return emptyList()
        var rank = 0
        return (0 until topics.length()).mapNotNull { i ->
            val t = topics.getJSONObject(i)
            val isPinned = t.optBoolean("pinned_globally", false) || t.optBoolean("pinned", false)
            // 置顶帖不占编号;非置顶帖自增排名。
            val r = if (isPinned) 0 else ++rank
            LinuxDoTopic.fromJson(t, usersById, r)
        }
    }

    private companion object {
        /** 热榜缓存有效期:4 小时(与 GitHubTrendingRepository 对齐)。 */
        const val CACHE_TTL_MS = 4L * 60 * 60 * 1000

        /** 缓存文件名(放在 [cacheDir] 下)。 */
        const val CACHE_FILE = "linuxdo_hot.json"
    }

    // ===== 缓存读写(存原始 JSON + 写入时刻,首行注入时间戳头) =====

    private fun cacheFile(): File = File(cacheDir, CACHE_FILE)

    /**
     * 缓存格式:`<fetchedAt 毫秒>\n<原始 JSON>`(同 GitHubTrendingRepository 套路)。
     * 用首行时间戳头简单拼接,避免引入 JSON 再包一层(原文已是 JSON)。
     */
    private fun readCache(): Pair<Long, String>? {
        val file = cacheFile()
        if (!file.exists()) return null
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        val nl = raw.indexOf('\n')
        if (nl <= 0) return null
        val ts = raw.substring(0, nl).trim().toLongOrNull() ?: return null
        val json = raw.substring(nl + 1)
        return ts to json
    }

    private fun writeCache(fetchedAt: Long, json: String) {
        val dir = cacheDir ?: return
        if (!dir.exists()) dir.mkdirs()
        if (json.isBlank()) return
        runCatching { cacheFile().writeText("$fetchedAt\n$json") }
    }

    private fun isStale(fetchedAt: Long): Boolean =
        System.currentTimeMillis() - fetchedAt > CACHE_TTL_MS
}

/**
 * LinuxDo 热榜拉取结果(带数据新鲜度),与 [TrendingResult] 同构。
 *
 * [fetchedAt] 是这批数据实际落盘的时刻:命中缓存秒回时是缓存写入时刻(即「上次刷新时间」),
 * 走网络时是当前时刻。UI 据此在顶栏显示「上次刷新 N 分钟前」。
 */
data class LinuxDoResult(
    val fetchedAt: Long,
    val topics: List<LinuxDoTopic>
)
