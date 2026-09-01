package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.AppException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * gitcode 归档数据 HTTP 客户端(门面) —— 各 Archive Repository / SummaryRepository /
 * OverviewRepository / TrendsRepository / BroadcastRepository 共用的唯一入口。
 *
 * 数据仓库:https://gitcode.com/peng1818/AI-News-Hub-Data
 * 分支:news-hub-data
 *
 * 取数流程(对齐 docs/news-hub-data-usage.md):
 *  1. GET `index.json?ref=news-hub-data` → 读 `latest.<source>` 拿最新快照路径
 *     (index 只含即时字段:updated_at / latest / latest_overview / latest_audio)
 *  2. 拼 `<source>/<相对路径>?ref=news-hub-data` GET 该快照 JSON
 *  3. 解析顶层 `fetched_at_ms` 与 `items[]`,交由各 Repository 做字段映射
 *  4. 按需另拉根级独立文件:趋势 `trends.json`(趋势 Tab)与历史索引
 *     `history.json`(历史摘要)/ `overview_history.json`(历史总览)——均已拆出
 *     index.json,不随保留期增长,未用到对应页面的 tab 不必下载
 *
 * 内部组件(2026-08-29 拆分,公共 API 保持逐字不变):
 *  - [ArchiveEndpoints]:端点拼接与基址唯一可变点(单测指向 MockWebServer)
 *  - [ArchiveFetcher]:网络 + 磁盘兜底骨架 + [offlineMode] 离线状态
 *  - [ArchiveJsonCache]:index 与根级文件的 TTL 缓存 + Mutex 并发去重
 *  - [ArchiveSnapshotCache]:快照路径缓存 + 同 URL in-flight 去重
 *
 * 缓存与刷新:index 与各根级文件有 2 分钟内存缓存(多源并发去重);快照本体按路径
 * 缓存(内容不可变)。手动刷新(根 Tab 与源列表二级页下拉)经 force=true 绕过 TTL,
 * 锁内秒级去重窗口把同一波并发 force 收敛为 1 次网络请求;自动加载/重击 tab 走缓存。
 * 断网兜底:仅传输层失败读盘([ArchiveDiskCache],7 天时效,write-through 落盘),
 * HTTP 层错误不兜底直接走 Error 态 —— 盘上旧数据不能冒充最新数据。
 * 任一步失败(HTTP 错误 / index 无该源 / items 为空)抛 RuntimeException,
 * 交由 ViewModel 显示 Error 态。
 */
object ArchiveHttpClient {

    private val indexCache = ArchiveJsonCache(
        fetcher = ArchiveFetcher,
        cacheKey = "index.json",
        url = { ArchiveEndpoints.rootUrl("index.json") },
        hint = "读取归档索引失败"
    )

    /** 根级独立历史索引(拆出 index.json,内容与原内联字段同构)。 */
    private val historyFileCache = ArchiveJsonCache(
        fetcher = ArchiveFetcher,
        cacheKey = "history.json",
        url = { ArchiveEndpoints.rootUrl("history.json") },
        hint = "读取历史索引失败"
    )

    private val overviewHistoryFileCache = ArchiveJsonCache(
        fetcher = ArchiveFetcher,
        cacheKey = "overview_history.json",
        url = { ArchiveEndpoints.rootUrl("overview_history.json") },
        hint = "读取总览历史索引失败"
    )

    private val trendsHistoryFileCache = ArchiveJsonCache(
        fetcher = ArchiveFetcher,
        cacheKey = "trends_history.json",
        url = { ArchiveEndpoints.rootUrl("trends_history.json") },
        hint = "读取热词历史索引失败"
    )

    // trends.json 由 write_trends「成功才写」,生成失败的批次会暂缺文件(下次自愈),
    // 404 是正常暂态 → absentAsNull 返回 null(UI 走 NoData 空态);history 两索引
    // 由流水线无条件恒写,404 属异常,应抛错走错误态。trends_cloud.json 与 trends.json
    // 同批生成、同为「成功才写」语义(词云文件写入失败仅告警,热词榜不受影响)。
    private val trendsFileCache = ArchiveJsonCache(
        fetcher = ArchiveFetcher,
        cacheKey = "trends.json",
        url = { ArchiveEndpoints.rootUrl("trends.json") },
        hint = "读取趋势数据失败",
        absentAsNull = true
    )

    private val trendsCloudFileCache = ArchiveJsonCache(
        fetcher = ArchiveFetcher,
        cacheKey = "trends_cloud.json",
        url = { ArchiveEndpoints.rootUrl("trends_cloud.json") },
        hint = "读取趋势词云失败",
        absentAsNull = true
    )

    // app_config.json 是人工维护、可能尚未创建的远程配置(批次时刻表等):
    // 404 = 正常暂态 → absentAsNull 返回 null,调用方(AppConfigSync)保持当前值
    private val appConfigFileCache = ArchiveJsonCache(
        fetcher = ArchiveFetcher,
        cacheKey = "app_config.json",
        url = { ArchiveEndpoints.rootUrl("app_config.json") },
        hint = "读取应用配置失败",
        absentAsNull = true
    )

    private val snapshotCache = ArchiveSnapshotCache(ArchiveFetcher)

    /** 公开只读离线状态(UI 订阅,断网切换时提示「正在展示缓存数据」)。 */
    val offlineMode: StateFlow<Boolean> get() = ArchiveFetcher.offlineMode

    /**
     * 单测专用:把 API 基址指向测试服务器(如 MockWebServer)并整体重置内存态,
     * 保证 object 单例跨用例无残留。生产代码不得调用;各用例在 @Before 中先调本方法。
     */
    internal fun reconfigureForTest(baseUrl: String) {
        ArchiveEndpoints.resetForTest()
        ArchiveEndpoints.apiBase = baseUrl
        indexCache.clearForTest()
        historyFileCache.clearForTest()
        overviewHistoryFileCache.clearForTest()
        trendsHistoryFileCache.clearForTest()
        trendsFileCache.clearForTest()
        trendsCloudFileCache.clearForTest()
        appConfigFileCache.clearForTest()
        snapshotCache.clearForTest()
    }

    /**
     * 拉某源的最新归档快照,返回解析后的顶层 JSON(含 fetched_at_ms / items 等)。
     *
     * @param source 源标识,对应 index.json 的 latest 键与目录名
     * @return 该源最新快照的 JSON 对象;index 无该源或快照缺失抛 RuntimeException
     */
    suspend fun fetchLatestSnapshot(source: String, force: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        // 1) 读 index.json(带缓存 + 并发去重,force 绕过 TTL)拿最新路径
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
     * @param force true 绕过 index 2 分钟 TTL 强制重读(源列表二级页下拉刷新);
     *               快照本体按路径不可变,路径不变时仍命中快照缓存
     * @param mapper (items[i] 的 JSON, 索引 i) → 领域对象;返回 null 跳过该条。
     *               索引从 0 起,供 fallbackRank = i+1 等场景使用
     * @return (数据落盘时刻, 领域对象列表)
     */
    suspend fun <T> fetchItemsList(
        source: String,
        force: Boolean = false,
        mapper: (org.json.JSONObject, Int) -> T?
    ): Pair<Long, List<T>> = withContext(Dispatchers.IO) {
        val snapshot = fetchLatestSnapshot(source, force)
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
     * 读 index.json 的 latest 指针表(与快照共享缓存),返回 source → 相对路径。
     * 供 SourceFreshness 计算各源数据指纹(路径含日期+时间,数据更新即变化)
     * ——只读指针,不拉快照本体。index 无 latest 字段时返回空 map。
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
     * 与 latest/history 共享缓存。字段缺失或无 items 时返回 null
     * (语义:今日总览尚未生成,UI 走 NoData 态)。OverviewRepository 据此反序列化为 OverviewDigest。
     *
     * @param force true 绕过缓存(手动刷新路径)
     * @param networkOnly true 时为「网络探测」语义(每日更新 Worker / 冷启动新数据弹窗):
     *        跳过内存缓存与磁盘兜底,必须真实打网络,传输层/HTTP/解析失败一律抛 ——
     *        调用方拿失败当信号(档内补查/放弃弹窗),绝不能把盘上旧数据当成新批次。
     *        总览 Tab / 小组件等展示路径不要传(需要断网兜底)。
     */
    suspend fun fetchLatestOverview(force: Boolean = false, networkOnly: Boolean = false): JSONObject? = withContext(Dispatchers.IO) {
        fetchIndex(force, allowDiskFallback = !networkOnly).optJSONObject("latest_overview")
            ?.takeIf { it.has("items") }
    }

    /**
     * 读 index.json 顶层的 `latest_audio` 字段(语音速报预生成音频描述,流水线
     * tts_broadcast.py 以 Qwen3-TTS 合成单段全量 MP3 后写入)。与 [fetchLatestOverview]
     * 同一份 index 缓存(一次请求双读);字段缺失或无 file 返回 null
     * (语义:预生成音频未就绪,调用方回落系统 TTS)。断网时随 index 磁盘兜底
     * 一起生效 —— 盘上旧描述由调用方按 generatedAt 新鲜度判定取舍。
     */
    suspend fun fetchLatestAudio(force: Boolean = false): JSONObject? = withContext(Dispatchers.IO) {
        fetchIndex(force).optJSONObject("latest_audio")?.takeIf { it.optString("file").isNotBlank() }
    }

    /**
     * 预生成音频文件的直读 URL(与快照的 REST API raw 端点同拼法;
     * 播放走 MediaPlayer 流式拉取,不经本客户端的 JSON 解析链路)。
     *
     * @param relPath 仓库根相对路径,即 latest_audio.file(如
     *                `audio/2026-08-22/broadcast.mp3`)
     */
    fun audioUrl(relPath: String): String = ArchiveEndpoints.rootUrl(relPath.removePrefix("/"))

    /**
     * 拉根级独立文件 `trends.json`(跨源热词趋势榜,流水线 trend_keywords.py 在
     * push 阶段纯统计预生成;内容与原 index 内联 `latest_trends` 字段同构)。
     * 独立 2 分钟缓存 + 并发去重,与 index 互不影响。
     *
     * 文件「成功才写」:生成失败的批次暂缺(下次批次自愈),404 视为正常暂态 →
     * 返回 null;文件存在但无 keywords 同样返回 null(语义均为「趋势尚未生成」,
     * UI 走 NoData 空态)。其余网络/解析失败照常抛错(UI 错误态)。
     *
     * @param force true 绕过缓存(趋势 Tab 下拉刷新;锁内秒级去重窗口同 index)
     */
    suspend fun fetchLatestTrends(force: Boolean = false): JSONObject? = withContext(Dispatchers.IO) {
        trendsFileCache.fetch(force)?.takeIf { it.has("keywords") }
    }

    /**
     * 拉根级独立文件 `trends_cloud.json`(趋势词云,流水线 trend_keywords.py 与
     * trends.json 同批生成的纯统计词云候选;专用数据文件,不进按日归档)。
     * 独立缓存 + 并发去重,与 index / trends 互不影响——未进词云页不下载。
     *
     * 与 [fetchLatestTrends] 同款「成功才写」语义:404(尚未生成)或无 words 数组
     * 返回 null(UI 走 NoData 空态);其余网络/解析失败照常抛错(UI 错误态)。
     */
    suspend fun fetchTrendsCloud(): JSONObject? = withContext(Dispatchers.IO) {
        trendsCloudFileCache.fetch()?.takeIf { it.has("words") }
    }

    /**
     * 读根级独立索引文件 `history.json`(拆出 index.json,历史摘要按日期寻址用)。
     * 自带缓存 + 并发去重,与 index 互不影响(未进历史页的 tab 不下载本文件)。
     *
     * @return source → (date → 相对源目录的快照路径);由流水线每次运行时合并写入,
     * 每源仅保留最近 31 天且自 2026-07-18 起;文件存在但为空对象时返回空 map(UI 空态)。
     * 本文件由流水线无条件恒写,404/拉取失败属异常 → 抛错(UI 错误态)
     */
    suspend fun fetchHistory(): Map<String, Map<String, String>> = withContext(Dispatchers.IO) {
        val history = historyFileCache.fetch() ?: throw AppException.Network()
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
     * 自带缓存 + 并发去重。
     *
     * @return date → 相对 overview/ 目录的归档文件路径(形如 `2026-08-15/11-49-data.json`,
     *         取当日最后一次批次)。由流水线每次运行时合并写入,仅保留最近 90 天;
     *         文件存在但为空对象时返回空 map(UI 空态)。本文件由流水线无条件恒写,
     *         404/拉取失败属异常 → 抛错(UI 错误态)
     */
    suspend fun fetchOverviewHistory(): Map<String, String> = withContext(Dispatchers.IO) {
        val history = overviewHistoryFileCache.fetch() ?: throw AppException.Network()
        val result = mutableMapOf<String, String>()
        history.keys().forEach { date ->
            val rel = history.optString(date)
            if (rel.isNotBlank()) result[date] = rel
        }
        result
    }

    /**
     * 读根级独立索引文件 `trends_history.json`(拆出 index.json,历史热词按日期寻址)。
     * 自带缓存 + 并发去重。
     *
     * @return date → 相对 trends/ 目录的归档文件路径(形如 `2026-08-15/18-00-data.json`,
     *         取当日最后一次批次)。由流水线逐批次合并写入,仅保留最近 90 天;
     *         文件存在但为空对象时返回空 map(UI 空态)。索引自回填起持续存在,
     *         404/拉取失败属异常 → 抛错(UI 错误态)
     */
    suspend fun fetchTrendsHistory(): Map<String, String> = withContext(Dispatchers.IO) {
        val history = trendsHistoryFileCache.fetch() ?: throw AppException.Network()
        val result = mutableMapOf<String, String>()
        history.keys().forEach { date ->
            val rel = history.optString(date)
            if (rel.isNotBlank()) result[date] = rel
        }
        result
    }

    /**
     * 拉根级配置文件 `app_config.json`(人工维护的远程配置,当前仅批次时刻表
     * `batch_slots`,由 [AppConfigSync] 解析后应用到 PipelineSchedule)。
     * 独立缓存 + 并发去重,与 index 等根级文件互不影响。
     *
     * 文件尚未创建(404)返回 null —— 正常暂态,语义为「暂无远程配置,保持内置
     * 默认」,与 trends 的「成功才写」同款 absentAsNull;其余网络/解析失败照常
     * 抛错,由调用方静默吞掉。断网时随磁盘兜底(7 天 write-through)读上次配置。
     *
     * @param force true 绕过缓存(本文件冷启动经进程闸门每进程只拉一次,
     *               默认 false 已足够;force 语义与其他根级文件一致)
     */
    suspend fun fetchAppConfig(force: Boolean = false): JSONObject? = withContext(Dispatchers.IO) {
        appConfigFileCache.fetch(force)
    }

    /**
     * 按相对路径拉归档文件,返回解析后的顶层 JSON(历史日期寻址入口)。
     * 带路径缓存(内容不可变)与同 URL in-flight 去重:重进 tab / 历史日期页
     * 来回翻不再重复下载同一归档。
     *
     * @param source 目录标识(源目录 / `overview` / `trends`)
     * @param relPath 相对该目录的归档文件路径(形如 `2026-07-19/10-12-data.json`,
     *                取自 latest / history 索引)
     * @param arrayField 顶级内容数组字段名:源快照与总览归档为 `items`,趋势归档为
     *                `keywords`(字段缺失或为空视为无数据,抛 [AppException.NoData])
     * @return 该归档文件的 JSON 对象
     */
    suspend fun fetchSnapshot(source: String, relPath: String, arrayField: String = "items"): JSONObject =
        snapshotCache.fetch(source, relPath, arrayField)

    /**
     * 拉 index.json(带缓存与并发去重;force/networkOnly 语义见 [ArchiveJsonCache.fetch])。
     * tolerateMissing=false 时不会返回 null,elvis 仅为类型兜底。
     */
    private suspend fun fetchIndex(force: Boolean = false, allowDiskFallback: Boolean = true): JSONObject =
        indexCache.fetch(force, allowDiskFallback)
            ?: throw AppException.ServerError()
}
