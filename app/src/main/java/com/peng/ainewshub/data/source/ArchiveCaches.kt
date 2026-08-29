package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.AppException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 归档 JSON 的「TTL 内存缓存 + Mutex 并发去重」通用组件 —— index.json 与
 * history/trends 等根级独立文件共用同一套机制。
 *
 * 机制(对齐 docs/news-hub-data-usage.md「不要高频轮询」):
 *  1. 命中未过期缓存 → 直接复用,不打网络(多源并发调用共享一份)
 *  2. 否则进 Mutex 串行化网络刷新(并发调用只触发 1 次真实请求,其余等待复用)
 *
 * 两种时效窗口:
 *  - 常规([force]=false):[TTL_MS](2 分钟 —— index 与各根级文件同批次更新,
 *    实际几小时才更一次,短 TTL 足够);
 *  - 手动刷新([force]=true):绕过 TTL 强制打网络,但保留 [FORCE_DEDUP_MS] 锁内
 *    去重窗口 —— 摘要页下拉对 8 个源并发 force,首个请求打完网络后,等待者在窗口内
 *    直接复用刚刷新的结果,一次下拉只发 1 次 index 请求。窗口只需覆盖「首个完成 →
 *    等待者依次获锁醒来」的毫秒级间隙,2s 已很宽裕 —— 不能放宽,否则用户进 tab 后
 *    几秒内的手动下拉会被吞掉(瞬间返回旧缓存,刷新形同未触发)。
 *
 * @param url 网络地址(按需经 lambda 拼出,基址运行时可变,见 [ArchiveEndpoints])
 */
internal class ArchiveJsonCache(
    private val fetcher: ArchiveFetcher,
    private val cacheKey: String,
    private val url: () -> String,
    private val hint: String,
    private val absentAsNull: Boolean = false
) {

    private val mutex = Mutex()
    private var cached: JSONObject? = null
    private var cachedAt: Long = 0L

    /**
     * 拉取(带缓存与并发去重)。
     *
     * @param force true 绕过 TTL 强制打网络(手动刷新路径),锁内去重窗口仍生效
     * @param allowDiskFallback false(index/总览的 networkOnly 探测)时:跳过内存缓存
     *        早退(缓存里可能混有断网时读盘写入的旧 index),传输层失败也不读盘兜底,
     *        保证「要么真实网络数据、要么失败」
     * @return 解析后的 JSON;[absentAsNull] 且文件 404(尚未生成)时返回 null
     */
    suspend fun fetch(force: Boolean = false, allowDiskFallback: Boolean = true): JSONObject? = mutex.withLock {
        if (allowDiskFallback) {
            val c = cached
            val freshWithin = if (force) FORCE_DEDUP_MS else TTL_MS
            if (c != null && System.currentTimeMillis() - cachedAt < freshWithin) {
                return@withLock c
            }
        }
        val parsed = fetcher.fetchJsonWithDiskFallback(
            cacheKey, url(), hint,
            tolerateMissing = absentAsNull,
            allowDiskFallback = allowDiskFallback
        )
            ?: return@withLock null
        cached = parsed
        cachedAt = System.currentTimeMillis()
        parsed
    }

    /** 清空内存缓存(仅 [ArchiveHttpClient.reconfigureForTest] 使用)。 */
    fun clearForTest() {
        cached = null
        cachedAt = 0L
    }

    companion object {
        /** 内存缓存有效期:2 分钟(index 与根级文件每批次更新,几小时一次)。 */
        internal const val TTL_MS = 2L * 60 * 1000

        /** force 请求的锁内去重窗口(见类注释)。 */
        internal const val FORCE_DEDUP_MS = 2_000L
    }
}

/**
 * 快照按路径缓存 + 同 URL in-flight 去重 —— 历史日期寻址与「最新快照」共用。
 *
 * 快照内容按路径不可变(路径含日期+时间,流水线只追加不覆写),缓存命中即零网络;
 * 不同源/不同路径互不阻塞,保持 8 源并发加载;同 URL 并发经 per-key Mutex 去重。
 * 内存条数上限覆盖 latest 8 源 + 一页历史摘要(8+8);超限淘汰任意一条,仅作容量
 * 护栏(非严格 LRU,单条数百 KB 级)。
 */
internal class ArchiveSnapshotCache(private val fetcher: ArchiveFetcher) {

    private val snapshotCache = ConcurrentHashMap<String, JSONObject>()

    /** in-flight 去重锁:同 URL 并发只打 1 次网络,其余等锁后复用缓存。 */
    private val snapshotLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * 按相对路径拉归档文件,返回解析后的顶层 JSON。
     *
     * @param source 目录标识(源目录 / `overview` / `trends`)
     * @param relPath 相对该目录的归档文件路径(形如 `2026-07-19/10-12-data.json`)
     * @param arrayField 顶级内容数组字段名:源快照与总览归档为 `items`,趋势归档为
     *        `keywords`(字段缺失或为空视为无数据,抛 [AppException.NoData])
     */
    suspend fun fetch(source: String, relPath: String, arrayField: String = "items"): JSONObject {
        val cacheKey = "$source/$relPath"
        snapshotCache[cacheKey]?.let { return it }
        val lock = snapshotLocks.computeIfAbsent(cacheKey) { Mutex() }
        val snapshot = lock.withLock {
            // 二次检查:等锁期间可能已被同 URL 的并发请求拉完
            snapshotCache[cacheKey]
                ?: withContext(Dispatchers.IO) {
                    val snapshot = fetcher.fetchJsonWithDiskFallback(
                        cacheKey, ArchiveEndpoints.fileUrl(source, relPath), "读取归档快照失败"
                    )
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

    /** 清空全部缓存(仅 [ArchiveHttpClient.reconfigureForTest] 使用)。 */
    fun clearForTest() {
        snapshotCache.clear()
        snapshotLocks.clear()
    }

    companion object {
        /** 快照内存缓存条数上限。 */
        internal const val SNAPSHOT_CACHE_LIMIT = 16
    }
}
