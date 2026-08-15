package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.AppException
import com.peng.ainewshub.data.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * gitcode 归档数据 HTTP 客户端 —— 各 Archive Repository / SummaryRepository / OverviewRepository 共用。
 *
 * 数据仓库:https://gitcode.com/peng1818/AI-News-Hub-Data
 * 分支:news-hub-data
 * 端点:gitcode 官方 REST API 的 raw 文件接口(见 [API_BASE])
 *
 * 取数流程(对齐 docs/news-hub-data-usage.md):
 *  1. GET `index.json?ref=news-hub-data` → 读 `latest.<source>` 拿最新快照路径
 *     (或读 `history.<source>.<date>` 拿指定日期的快照路径 —— 「历史摘要」用)
 *  2. 拼 `<API_BASE>/<source>/<相对路径>?ref=news-hub-data` GET 该快照 JSON
 *  3. 解析顶层 `fetched_at_ms` 与 `items[]`,交由各 Repository 做字段映射
 *
 * 缓存与刷新:index.json 有 2 分钟内存缓存(多源并发去重,见 fetchIndex);快照本体
 * 按路径缓存(内容不可变,见 fetchSnapshot)+ 同 URL in-flight 去重,重进 tab 不重复下载。
 * 手动刷新(根 Tab 下拉)经 force=true 绕过 index 缓存,保证流水线刚推送时立即可见
 * (锁内秒级去重窗口把同一波并发 force 收敛为 1 次网络);自动加载/重击 tab 走缓存
 * (2 分钟外自然失效)。
 * 任一步失败(HTTP 错误 / index 无该源 / items 为空)抛 RuntimeException,
 * 交由 ViewModel 显示 Error 态(归档模式明确提示,不回退实时)。
 *
 * 走 gitcode 官方 REST API(api.gitcode.com/api/v5/.../raw/)而非 raw 直链:
 * raw.gitcode.com 背后是华为云 WAF,部分网络环境(数据中心/特定地区 IP)被拦 403;
 * 官方 API 走独立服务,公开仓库匿名可读,稳定性更好(实测连发无 403)。
 *
 * 客户端配置对齐 App 其余 Repository:connect 15s / read 20s / 跟随重定向。
 * cookieJar 保留:API 端也可能下发会话 cookie,带上无害且降低潜在限流概率。
 */
object ArchiveHttpClient {

    /**
     * gitcode 官方 API 的 raw 文件端点根(分支 news-hub-data)。
     * 完整 URL 形如:
     *   <API_BASE>/index.json?ref=news-hub-data          ← 读 index
     *   <API_BASE>/<source>/<date>/<time>-data.json?ref=news-hub-data  ← 读快照
     */
    private const val API_BASE =
        "https://api.gitcode.com/api/v5/repos/peng1818/AI-News-Hub-Data/raw"

    /** 分支名(API 用 ref 查询参数指定)。 */
    private const val REF = "news-hub-data"

    private const val INDEX_URL = "$API_BASE/index.json?ref=$REF"

    /** index.json 内存缓存有效期:2 分钟(index 实际几小时才更新一次,短 TTL 足够)。 */
    private const val INDEX_TTL_MS = 2L * 60 * 1000

    /**
     * force 请求的锁内去重窗口:摘要页下拉刷新对 8 个源并发 force,在 Mutex 上排队;
     * 首个请求打完网络后,等待者在此窗口内直接复用刚刷新的 index,不再逐个真打
     * (否则一次下拉 = 串行下载 8 次 index.json)。窗口只需覆盖「首个完成 → 等待者
     * 依次获锁醒来」的毫秒级间隙,2s 已很宽裕 —— 不能放宽,否则用户进 tab 后几秒内
     * 的手动下拉刷新会被吞掉(瞬间返回旧缓存,刷新形同未触发)。
     */
    private const val FORCE_FETCH_DEDUP_MS = 2_000L

    /**
     * 快照内存缓存条数上限:覆盖 latest 8 源 + 一页历史摘要(8+8)。
     * 快照内容按路径不可变(路径含日期+时间,流水线只追加不覆写),无需 TTL;
     * 超限时淘汰任意一条,仅作内存容量护栏(非严格 LRU,单条数百 KB 级)。
     */
    private const val SNAPSHOT_CACHE_LIMIT = 16

    private val client by lazy {
        // 共享 base 派生:连接池/线程池与全 App 复用,仅覆盖 cookieJar
        HttpClients.base.newBuilder()
            // gitcode raw 背后是华为云 WAF,会下发 HWWAFSESID 等会话 cookie;不带 cookie
            // 的裸请求容易被 WAF 判为可疑流量返回 403。配 cookieJar 让客户端像浏览器一样
            // 记住并回传会话 cookie,降低被拦概率。内存存储(进程级,随 App 生命周期)。
            .cookieJar(InMemoryCookieJar())
            .build()
    }

    // ===== index.json 并发去重 + 短 TTL 缓存 =====
    // 4 个归档源同时加载时,各自都要先读 index.json 拿最新路径。若无去重,会发 4 次
    // 完全相同的 index 请求(文档明确建议「不要高频轮询」)。这里用 Mutex + 内存缓存:
    //  1. 命中未过期缓存 → 直接复用,不打网络(4 个源共享一份)
    //  2. 否则进 Mutex,串行化网络刷新(并发调用只触发 1 次真实请求,其余等待复用)
    // 对齐 App 现有实时 Repository 的 Mutex.withLock 套路。TTL 2 分钟(index 几小时才更新一次)。

    /** index.json 缓存:解析后的 JSON + 落盘时刻(毫秒)。null 表示无缓存。 */
    private var indexCache: JSONObject? = null
    private var indexCacheAt: Long = 0L

    /** 串行化 index 刷新,避免并发重复打网络。 */
    private val indexMutex = Mutex()

    // ===== 快照路径缓存 + 同 URL in-flight 去重 =====
    // 快照内容按路径不可变(见 SNAPSHOT_CACHE_LIMIT 注释),缓存命中即零网络。
    // 不同源/不同路径互不阻塞,保持 8 源并发加载;同 URL 并发经 per-key Mutex 去重。

    /** 快照缓存:source/relPath → 解析后的 JSON(只读共享)。 */
    private val snapshotCache = ConcurrentHashMap<String, JSONObject>()

    /** in-flight 去重锁:同 URL 并发只打 1 次网络,其余等锁后复用缓存。 */
    private val snapshotLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * 拉 index.json(带 2 分钟缓存 + 并发去重)。
     *
     * @param force true 时绕过 TTL 强制打网络(手动刷新路径);并发去重的 Mutex 仍生效,
     *              且锁内做短窗口二次校验 —— 排队等锁期间 index 已被首个 force 刷新过
     *              则直接复用,同一波并发 force 收敛为 1 次网络请求
     * @return 解析后的 index JSON;读取或解析失败抛 RuntimeException
     */
    private suspend fun fetchIndex(force: Boolean = false): JSONObject = indexMutex.withLock {
        // 1) 命中缓存:非 force 看 2 分钟 TTL;force 只看秒级去重窗口
        //    (等待锁期间已被刷新 → 复用,不重复打网络)。
        val cached = indexCache
        val freshWithin = if (force) FORCE_FETCH_DEDUP_MS else INDEX_TTL_MS
        if (cached != null && System.currentTimeMillis() - indexCacheAt < freshWithin) {
            return@withLock cached
        }
        // 2) 走网络刷新(仅一次,并发其余调用在此等待后复用结果)
        val text = getRaw(INDEX_URL, "读取归档索引失败")
        val parsed = runCatching { JSONObject(text) }
            .getOrElse { throw AppException.ServerError() }
        indexCache = parsed
        indexCacheAt = System.currentTimeMillis()
        parsed
    }

    /**
     * 拉某源的最新归档快照,返回解析后的顶层 JSON(含 fetched_at_ms / items 等)。
     *
     * @param source 源标识,对应 index.json 的 latest 键与目录名
     * @return 该源最新快照的 JSON 对象;index 无该源或快照缺失抛 RuntimeException
     */
    suspend fun fetchLatestSnapshot(source: String, force: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        // 1) 读 index.json(带 2 分钟缓存 + 并发去重,force 绕过 TTL,见 fetchIndex)拿最新路径
        val index = fetchIndex(force)
        val latest = index.optJSONObject("latest")
            ?: throw AppException.ServerError()
        val relPath = latest.optString(source).takeIf { it.isNotBlank() }
            ?: throw AppException.NoData()

        // 2) 拉该源最新快照(API raw 端点,带 ref 指定分支)
        fetchSnapshot(source, relPath)
    }

    /**
     * 拉某源最新快照并按 [mapper] 映射成领域对象列表 —— 收敛 7 个 ArchiveRepository
     * 完全同构的 `fetched_at_ms` / `items` / mapNotNull / 空判 骨架。
     *
     * 各 ArchiveRepository 退化到只提供 [source] 与 [mapper],由本方法统一处理:
     *  1. 拉最新快照(经 index 缓存 + 并发去重)
     *  2. 取 fetched_at_ms(缺失回退当前时刻)
     *  3. 遍历 items[],对每个 JSONObject 调 [mapper];返回 null 的条目被跳过
     *  4. 映射后为空(全被过滤)抛 [AppException.NoData]
     *
     * @param source 源标识(目录名 / index latest 键)
     * @param mapper (items[i] 的 JSON, 索引 i) → 领域对象;返回 null 跳过该条。
     *               索引从 0 起,供 fallbackRank = i+1 等场景使用
     * @return (数据落盘时刻, 领域对象列表)
     */
    suspend fun <T> fetchItemsList(
        source: String,
        mapper: (org.json.JSONObject, Int) -> T?
    ): Pair<Long, List<T>> = withContext(Dispatchers.IO) {
        val snapshot = fetchLatestSnapshot(source)
        val fetchedAt = snapshot.optLong("fetched_at_ms", System.currentTimeMillis())
        val items = snapshot.optJSONArray("items")
            ?: throw AppException.NoData()
        val list = (0 until items.length()).mapNotNull { i ->
            val obj = items.optJSONObject(i) ?: return@mapNotNull null
            mapper(obj, i)
        }
        if (list.isEmpty()) throw AppException.NoData()
        fetchedAt to list
    }

    /**
     * 读 index.json 的 latest 指针表(与快照共享 2 分钟缓存),返回 source → 相对路径。
     * 供 OverviewRepository 计算数据指纹(路径含日期+时间,数据更新即变化)——只读指针,
     * 不拉快照本体。index 无 latest 字段时返回空 map。
     */
    suspend fun fetchLatestPaths(): Map<String, String> = withContext(Dispatchers.IO) {
        val index = fetchIndex()
        val latest = index.optJSONObject("latest") ?: return@withContext emptyMap()
        val result = mutableMapOf<String, String>()
        latest.keys().forEach { k ->
            val v = latest.optString(k)
            if (v.isNotBlank()) result[k] = v
        }
        result
    }

    /**
     * 读 index.json 顶层的 `latest_overview` 字段(今日总览,流水线预生成的跨源综合分析)。
     * 与 latest/history 共享 2 分钟缓存。字段缺失或无 items 时返回 null
     * (语义:今日总览尚未生成,UI 走 NoData 态)。OverviewRepository 据此反序列化为 OverviewDigest。
     */
    suspend fun fetchLatestOverview(force: Boolean = false): JSONObject? = withContext(Dispatchers.IO) {
        fetchIndex(force).optJSONObject("latest_overview")?.takeIf { it.has("items") }
    }

    /**
     * 读 index.json 顶层的 `latest_trends` 字段(跨源热词趋势榜,流水线 trend_keywords.py
     * 纯统计预生成)。与 latest/history/latest_overview 共享 2 分钟缓存,零额外请求。
     * 字段缺失或无 keywords 时返回 null(语义:趋势尚未生成,UI 走 NoData 态)。
     * TrendsRepository 据此反序列化为 TrendsDigest。
     */
    suspend fun fetchLatestTrends(force: Boolean = false): JSONObject? = withContext(Dispatchers.IO) {
        fetchIndex(force).optJSONObject("latest_trends")?.takeIf { it.has("keywords") }
    }

    /**
     * 读 index.json 的 `history` 索引(与 latest 共享 2 分钟缓存),供「历史摘要」按日期寻址。
     *
     * @return source → (date → 相对源目录的快照路径);history 由流水线每次运行时合并写入,
     * 每源仅保留最近 31 天且自 2026-07-18 起;旧版 index 无该字段时返回空 map(功能上线初期即如此)
     */
    suspend fun fetchHistory(): Map<String, Map<String, String>> = withContext(Dispatchers.IO) {
        val index = fetchIndex()
        val history = index.optJSONObject("history") ?: return@withContext emptyMap()
        val result = mutableMapOf<String, Map<String, String>>()
        history.keys().forEach { source ->
            val dates = history.optJSONObject(source) ?: return@forEach
            val dateMap = mutableMapOf<String, String>()
            dates.keys().forEach { date ->
                val rel = dates.optString(date)
                if (rel.isNotBlank()) dateMap[date] = rel
            }
            if (dateMap.isNotEmpty()) result[source] = dateMap
        }
        result
    }

    /**
     * 读 index.json 的 `overview_history` 索引(与 latest 共享 2 分钟缓存),供
     * 「历史总览」按日期寻址。
     *
     * @return date → 相对 overview/ 目录的归档文件路径(形如 `2026-08-15/11-49-data.json`,
     *         取当日最后一次批次)。由流水线每次运行时合并写入,仅保留最近 90 天;
     *         旧版 index 无该字段时返回空 map(功能上线初期即如此)
     */
    suspend fun fetchOverviewHistory(): Map<String, String> = withContext(Dispatchers.IO) {
        val index = fetchIndex()
        val history = index.optJSONObject("overview_history") ?: return@withContext emptyMap()
        val result = mutableMapOf<String, String>()
        history.keys().forEach { date ->
            val rel = history.optString(date)
            if (rel.isNotBlank()) result[date] = rel
        }
        result
    }

    /**
     * 按相对路径拉某源的归档快照,返回解析后的顶层 JSON(历史日期寻址入口)。
     * 带 source/relPath 路径缓存(内容不可变)与同 URL in-flight 去重:2 分钟内重进
     * tab / 历史日期页来回翻不再重复下载同一快照。
     *
     * @param source 源标识(目录名)
     * @param relPath 相对源目录的快照路径(形如 `2026-07-19/10-12-data.json`,
     *                取自 latest / history 索引)
     * @return 该快照的 JSON 对象;缺失或 items 为空抛 RuntimeException
     */
    suspend fun fetchSnapshot(source: String, relPath: String): JSONObject {
        val cacheKey = "$source/$relPath"
        snapshotCache[cacheKey]?.let { return it }
        val lock = snapshotLocks.computeIfAbsent(cacheKey) { Mutex() }
        val snapshot = lock.withLock {
            // 二次检查:等锁期间可能已被同 URL 的并发请求拉完
            snapshotCache[cacheKey]
                ?: withContext(Dispatchers.IO) {
                    val snapshotUrl = "$API_BASE/$source/$relPath?ref=$REF"
                    val snapshotText = getRaw(snapshotUrl, "读取归档快照失败")
                    val snapshot = runCatching { JSONObject(snapshotText) }
                        .getOrElse { throw AppException.ServerError() }

                    // items 为空视为无数据(失败不缓存,下次重试)
                    val items = snapshot.optJSONArray("items")
                    if (items == null || items.length() == 0) {
                        throw AppException.NoData()
                    }
                    snapshot
                }.also {
                    snapshotCache[cacheKey] = it
                    if (snapshotCache.size > SNAPSHOT_CACHE_LIMIT) {
                        snapshotCache.keys.firstOrNull()?.let { eldest -> snapshotCache.remove(eldest) }
                    }
                }
        }
        // 清掉 in-flight 锁:已拿引用的等待者仍会正常获锁并命中二次检查;
        // 后续新调用要么命中缓存,要么建新锁,均无死锁
        snapshotLocks.remove(cacheKey)
        return snapshot
    }

    /**
     * GET 一个 URL,返回响应正文;非 2xx 或空响应抛 [AppException.Network]。
     * [hint] 仅用于日志诊断(toUiError 会把原始异常记入 logcat)。
     *
     * suspend 自管 [Dispatchers.IO]:所有调用方(含 [fetchIndex] 的 Mutex 锁内)
     * 均无需再外层切 IO,与 [HttpClients.get] 行为一致。
     */
    private suspend fun getRaw(url: String, @Suppress("UNUSED_PARAMETER") hint: String): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", HttpClients.DEFAULT_BROWSER_UA)
                .header("Accept", "application/json,text/plain,*/*")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw AppException.Network()
                }
                resp.body?.string()?.takeIf { it.isNotBlank() }
                    ?: throw AppException.Network()
            }
        }
}

/**
 * 进程级内存 CookieJar —— 记住 gitcode WAF 下发的会话 cookie 并在后续请求回传。
 *
 * 仅用于 [ArchiveHttpClient](gitcode raw 域名),进程内单实例,App 退出即清空。
 * 实现极简:不做过期清理(cookie 量极小,WAF 会话 cookie 短命),用 ConcurrentHashMap
 * 保证多源并发请求时的线程安全。
 *
 * 不持久化:cookie 仅用于降低 WAF 拦截概率,无登录态意义,无需跨进程保留。
 */
private class InMemoryCookieJar : CookieJar {

    private val store: MutableMap<String, MutableList<Cookie>> = ConcurrentHashMap()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val list = store.getOrPut(url.host) { mutableListOf() }
        synchronized(list) {
            // 同名 cookie 替换(更新值/有效期),新增的追加
            for (c in cookies) {
                list.removeAll { it.name == c.name }
                list.add(c)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = store[url.host] ?: return emptyList()
        return synchronized(list) {
            // 过滤掉已过期 cookie,顺带清理
            val now = System.currentTimeMillis()
            list.filter { it.expiresAt > now }.also { valid ->
                if (valid.size != list.size) list.retainAll(valid)
            }
        }
    }
}