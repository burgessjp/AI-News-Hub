package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.AppException
import com.peng.ainewshub.data.HttpClients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *     (index 只含即时字段:updated_at / latest / latest_overview)
 *  2. 拼 `<API_BASE>/<source>/<相对路径>?ref=news-hub-data` GET 该快照 JSON
 *  3. 解析顶层 `fetched_at_ms` 与 `items[]`,交由各 Repository 做字段映射
 *  4. 按需另拉根级独立文件:趋势 `trends.json`(趋势 Tab)与历史索引
 *     `history.json`(历史摘要)/ `overview_history.json`(历史总览)——均已拆出
 *     index.json,不随保留期增长,未用到对应页面的 tab 不必下载
 *
 * 缓存与刷新:index.json 有 2 分钟内存缓存(多源并发去重,见 fetchIndex);快照本体
 * 按路径缓存(内容不可变,见 fetchSnapshot)+ 同 URL in-flight 去重,重进 tab 不重复下载。
 * 手动刷新(根 Tab 下拉)经 force=true 绕过 index 缓存,保证流水线刚推送时立即可见
 * (锁内秒级去重窗口把同一波并发 force 收敛为 1 次网络);自动加载/重击 tab 走缓存
 * (2 分钟外自然失效)。
 * 任一步失败(HTTP 错误 / index 无该源 / items 为空)抛 RuntimeException,
 * 交由 ViewModel 显示 Error 态(归档模式明确提示,不回退实时)。
 *
 * 断网兜底:index / 快照 / 根级文件网络成功后均 write-through 落盘
 * ([ArchiveDiskCache],cacheDir/archives/);网络失败时先读盘,命中则置
 * [offlineMode] 为 true 并返回盘上旧数据(各 Repository 签名零改动),
 * 未命中才照常抛错。列表页自带的「数据更新时间」头可让用户感知数据新旧。
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

    /** 根级独立历史索引(拆出 index.json,内容与原内联字段同构)。 */
    private const val HISTORY_URL = "$API_BASE/history.json?ref=$REF"
    private const val OVERVIEW_HISTORY_URL = "$API_BASE/overview_history.json?ref=$REF"

    /** 根级独立趋势文件(拆出 index.json,内容与原内联 latest_trends 字段同构)。 */
    private const val TRENDS_URL = "$API_BASE/trends.json?ref=$REF"

    /** 根级独立趋势词云文件(专用数据文件,「趋势词云」页按需拉取)。 */
    private const val TRENDS_CLOUD_URL = "$API_BASE/trends_cloud.json?ref=$REF"

    /** 根级独立趋势历史索引(拆出 index.json,历史热词按日期寻址)。 */
    private const val TRENDS_HISTORY_URL = "$API_BASE/trends_history.json?ref=$REF"

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

    /**
     * 离线兜底状态(进程内):true = 最近一次取数走了盘上旧数据(网络失败但兜底命中)。
     * 任一请求网络成功后复位为 false。UI(AiNewsHubApp)订阅它在离线切换时提示用户
     * 「正在展示缓存数据」。进程级内存态,不做持久化。
     */
    private val _offlineMode = MutableStateFlow(false)

    /** 公开只读离线状态。 */
    val offlineMode: StateFlow<Boolean> = _offlineMode.asStateFlow()

    // ===== 快照路径缓存 + 同 URL in-flight 去重 =====
    // 快照内容按路径不可变(见 SNAPSHOT_CACHE_LIMIT 注释),缓存命中即零网络。
    // 不同源/不同路径互不阻塞,保持 8 源并发加载;同 URL 并发经 per-key Mutex 去重。

    /** 快照缓存:source/relPath → 解析后的 JSON(只读共享)。 */
    private val snapshotCache = ConcurrentHashMap<String, JSONObject>()

    /** in-flight 去重锁:同 URL 并发只打 1 次网络,其余等锁后复用缓存。 */
    private val snapshotLocks = ConcurrentHashMap<String, Mutex>()

    // ===== 根级独立索引/内容文件(history.json / overview_history.json / trends.json / trends_history.json)=====
    // 拆出 index.json 的历史索引与趋势:更新节奏与 index 相同(每批次),复用同款
    // 2 分钟 TTL + Mutex 并发去重;仅对应页面按需拉取,单文件持有单实例。

    private val historyFileFetcher = CachedFileJson("history.json", HISTORY_URL, "读取历史索引失败")
    private val overviewHistoryFileFetcher =
        CachedFileJson("overview_history.json", OVERVIEW_HISTORY_URL, "读取总览历史索引失败")
    private val trendsHistoryFileFetcher =
        CachedFileJson("trends_history.json", TRENDS_HISTORY_URL, "读取热词历史索引失败")

    // trends.json 由 write_trends「成功才写」,生成失败的批次会暂缺文件(下次自愈),
    // 404 是正常暂态 → absentAsNull 返回 null(UI 走 NoData 空态);history 两索引
    // 由流水线无条件恒写,404 属异常,应抛错走错误态。trends_cloud.json 与 trends.json
    // 同批生成、同为「成功才写」语义(词云文件写入失败仅告警,热词榜不受影响)。
    private val trendsFileFetcher = CachedFileJson("trends.json", TRENDS_URL, "读取趋势数据失败", absentAsNull = true)
    private val trendsCloudFileFetcher =
        CachedFileJson("trends_cloud.json", TRENDS_CLOUD_URL, "读取趋势词云失败", absentAsNull = true)

    /**
     * 根级独立文件的单实例缓存拉取器(history / overview_history / trends 用)。
     *
     * 机制与 fetchIndex 同款:Mutex 串行化 + [INDEX_TTL_MS] 短 TTL 缓存(各文件与
     * index 同批次更新,节奏一致);[force] 语义也与 fetchIndex 一致 —— 绕过 TTL
     * 强制打网络,但保留 [FORCE_FETCH_DEDUP_MS] 锁内去重窗口(趋势 Tab 下拉刷新用;
     * 历史页无下拉刷新,恒走默认 false)。
     *
     * [absentAsNull]:true 时文件 404(尚未生成)返回 null 而非抛错(NoData 语义);
     * false 时 404 与其它失败一样抛 [AppException.Network](错误态语义)。
     * 网络失败时经 [fetchJsonWithDiskFallback] 读盘兜底(同 index / 快照)。
     */
    private class CachedFileJson(
        private val cacheKey: String,
        private val url: String,
        private val hint: String,
        private val absentAsNull: Boolean = false
    ) {

        private val mutex = Mutex()
        private var cached: JSONObject? = null
        private var cachedAt: Long = 0L

        suspend fun fetch(force: Boolean = false): JSONObject? = mutex.withLock {
            val c = cached
            val freshWithin = if (force) FORCE_FETCH_DEDUP_MS else INDEX_TTL_MS
            if (c != null && System.currentTimeMillis() - cachedAt < freshWithin) {
                return@withLock c
            }
            val parsed = fetchJsonWithDiskFallback(cacheKey, url, hint, tolerateMissing = absentAsNull)
                ?: return@withLock null
            cached = parsed
            cachedAt = System.currentTimeMillis()
            parsed
        }
    }

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
        // 2) 走网络刷新(仅一次,并发其余调用在此等待后复用结果;网络失败读盘兜底)
        //    tolerateMissing=false 时不会返回 null,elvis 仅为类型兜底
        val parsed = fetchJsonWithDiskFallback("index.json", INDEX_URL, "读取归档索引失败")
            ?: throw AppException.ServerError()
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
     * 拉根级独立文件 `trends.json`(跨源热词趋势榜,流水线 trend_keywords.py 在
     * push 阶段纯统计预生成;内容与原 index 内联 `latest_trends` 字段同构)。
     * 独立 2 分钟缓存 + 并发去重,与 index 互不影响。
     *
     * 文件「成功才写」:生成失败的批次暂缺(下次批次自愈),404 视为正常暂态 →
     * 返回 null;文件存在但无 keywords 同样返回 null(语义均为「趋势尚未生成」,
     * UI 走 NoData 空态)。其余网络/解析失败照常抛错(UI 错误态)。
     *
     * @param force true 绕过缓存(趋势 Tab 下拉刷新;锁内秒级去重窗口与 fetchIndex 同款)
     */
    suspend fun fetchLatestTrends(force: Boolean = false): JSONObject? = withContext(Dispatchers.IO) {
        trendsFileFetcher.fetch(force)?.takeIf { it.has("keywords") }
    }

    /**
     * 拉根级独立文件 `trends_cloud.json`(趋势词云,流水线 trend_keywords.py 与
     * trends.json 同批生成的纯统计词云候选;专用数据文件,不进按日归档)。
     * 独立 2 分钟缓存 + 并发去重,与 index / trends 互不影响——未进词云页不下载。
     *
     * 与 [fetchLatestTrends] 同款「成功才写」语义:404(尚未生成)或无 words 数组
     * 返回 null(UI 走 NoData 空态);其余网络/解析失败照常抛错(UI 错误态)。
     */
    suspend fun fetchTrendsCloud(): JSONObject? = withContext(Dispatchers.IO) {
        trendsCloudFileFetcher.fetch()?.takeIf { it.has("words") }
    }

    /**
     * 读根级独立索引文件 `history.json`(拆出 index.json,历史摘要按日期寻址用)。
     * 自带 2 分钟缓存 + 并发去重,与 index 互不影响(未进历史页的 tab 不下载本文件)。
     *
     * @return source → (date → 相对源目录的快照路径);由流水线每次运行时合并写入,
     * 每源仅保留最近 31 天且自 2026-07-18 起;文件存在但为空对象时返回空 map(UI 空态)。
     * 本文件由流水线无条件恒写,404/拉取失败属异常 → 抛错(UI 错误态)
     */
    suspend fun fetchHistory(): Map<String, Map<String, String>> = withContext(Dispatchers.IO) {
        val history = historyFileFetcher.fetch() ?: throw AppException.Network()
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
     * 读根级独立索引文件 `overview_history.json`(拆出 index.json,历史总览按日期寻址)。
     * 自带 2 分钟缓存 + 并发去重。
     *
     * @return date → 相对 overview/ 目录的归档文件路径(形如 `2026-08-15/11-49-data.json`,
     *         取当日最后一次批次)。由流水线每次运行时合并写入,仅保留最近 90 天;
     *         文件存在但为空对象时返回空 map(UI 空态)。本文件由流水线无条件恒写,
     *         404/拉取失败属异常 → 抛错(UI 错误态)
     */
    suspend fun fetchOverviewHistory(): Map<String, String> = withContext(Dispatchers.IO) {
        val history = overviewHistoryFileFetcher.fetch() ?: throw AppException.Network()
        val result = mutableMapOf<String, String>()
        history.keys().forEach { date ->
            val rel = history.optString(date)
            if (rel.isNotBlank()) result[date] = rel
        }
        result
    }

    /**
     * 读根级独立索引文件 `trends_history.json`(拆出 index.json,历史热词按日期寻址)。
     * 自带 2 分钟缓存 + 并发去重。
     *
     * @return date → 相对 trends/ 目录的归档文件路径(形如 `2026-08-15/18-00-data.json`,
     *         取当日最后一次批次)。由流水线逐批次合并写入,仅保留最近 90 天;
     *         文件存在但为空对象时返回空 map(UI 空态)。索引自回填起持续存在,
     *         404/拉取失败属异常 → 抛错(UI 错误态)
     */
    suspend fun fetchTrendsHistory(): Map<String, String> = withContext(Dispatchers.IO) {
        val history = trendsHistoryFileFetcher.fetch() ?: throw AppException.Network()
        val result = mutableMapOf<String, String>()
        history.keys().forEach { date ->
            val rel = history.optString(date)
            if (rel.isNotBlank()) result[date] = rel
        }
        result
    }

    /**
     * 按相对路径拉归档文件,返回解析后的顶层 JSON(历史日期寻址入口)。
     * 带 source/relPath 路径缓存(内容不可变)与同 URL in-flight 去重:2 分钟内重进
     * tab / 历史日期页来回翻不再重复下载同一归档。
     *
     * @param source 目录标识(源目录 / `overview` / `trends`)
     * @param relPath 相对该目录的归档文件路径(形如 `2026-07-19/10-12-data.json`,
     *                取自 latest / history 索引)
     * @param arrayField 顶级内容数组字段名:源快照与总览归档为 `items`,趋势归档为
     *                `keywords`(字段缺失或为空视为无数据,抛 [AppException.NoData])
     * @return 该归档文件的 JSON 对象
     */
    suspend fun fetchSnapshot(source: String, relPath: String, arrayField: String = "items"): JSONObject {
        val cacheKey = "$source/$relPath"
        snapshotCache[cacheKey]?.let { return it }
        val lock = snapshotLocks.computeIfAbsent(cacheKey) { Mutex() }
        val snapshot = lock.withLock {
            // 二次检查:等锁期间可能已被同 URL 的并发请求拉完
            snapshotCache[cacheKey]
                ?: withContext(Dispatchers.IO) {
                    val snapshotUrl = "$API_BASE/$source/$relPath?ref=$REF"
                    val snapshot = fetchJsonWithDiskFallback(cacheKey, snapshotUrl, "读取归档快照失败")
                        ?: throw AppException.NoData()

                    // 顶级内容数组为空视为无数据(失败不缓存,下次重试)
                    val arr = snapshot.optJSONArray(arrayField)
                    if (arr == null || arr.length() == 0) {
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
     * 网络取 JSON + 磁盘兜底的统一骨架(fetchIndex / fetchSnapshot / 根级独立文件共用):
     *  1. 网络成功 → 解析 → write-through 落盘(静默失败)→ 复位 [offlineMode]
     *  2. 网络失败(任意非取消异常)→ 读盘兜底:命中且可解析则置 [offlineMode] 为 true
     *     并返回盘上旧数据;未命中或盘上数据损坏则抛回原异常(走原错误态)
     *  3. [tolerateMissing] 语义不变:404 返回 null,不落盘也不兜底(「成功才写」的
     *     文件尚未生成时盘上本就不会有)
     *
     * 网络成功但解析失败(HTML 乱码等)照旧抛 [AppException.ServerError],不读盘 ——
     * 与原行为一致,兜底只针对「连不上」的场景。
     *
     * @param cacheKey 磁盘缓存键(与内存缓存同键:index.json / source/relPath / 根级文件名)
     */
    private suspend fun fetchJsonWithDiskFallback(
        cacheKey: String,
        url: String,
        hint: String,
        tolerateMissing: Boolean = false
    ): JSONObject? {
        val text = try {
            getRaw(url, hint, tolerateMissing) ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 连不上:读盘兜底(盘上是上次网络成功时落下的旧数据)
            val disk = withContext(Dispatchers.IO) { ArchiveDiskCache.read(cacheKey) }
                ?: throw e
            return runCatching { JSONObject(disk) }
                .map { parsed -> parsed.also { _offlineMode.value = true } }
                .getOrElse { throw e }
        }
        val parsed = runCatching { JSONObject(text) }
            .getOrElse { throw AppException.ServerError() }
        withContext(Dispatchers.IO) { ArchiveDiskCache.write(cacheKey, text) }
        _offlineMode.value = false
        return parsed
    }

    /**
     * GET 一个 URL,返回响应正文;非 2xx 或空响应抛 [AppException.Network]。
     * [hint] 仅用于日志诊断(toUiError 会把原始异常记入 logcat)。
     *
     * [tolerateMissing] 为 true 时 404 → null(语义:文件尚未生成,调用方走 NoData;
     * 仅 trends.json 的「成功才写」暂态语义用),其余非 2xx 照常抛错。
     *
     * suspend 自管 [Dispatchers.IO]:所有调用方(含 [fetchIndex] 的 Mutex 锁内)
     * 均无需再外层切 IO,与 [HttpClients.get] 行为一致。
     */
    private suspend fun getRaw(
        url: String,
        @Suppress("UNUSED_PARAMETER") hint: String,
        tolerateMissing: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.DEFAULT_BROWSER_UA)
            .header("Accept", "application/json,text/plain,*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 && tolerateMissing -> null
                !resp.isSuccessful -> throw AppException.Network()
                else -> resp.body?.string()?.takeIf { it.isNotBlank() }
                    ?: throw AppException.Network()
            }
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