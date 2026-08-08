package com.peng.ainewshub.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * HTML 直连源 + 文件缓存的模板基类。
 *
 * 收敛 GitHub Trending / stormzhang AI / The Rundown AI / HuggingFace Papers 四个实时
 * Repository 完全同构的「缓存 + Mutex + stale 兜底」四步逻辑(此前逐字复制 4 份)。
 * 子类只需提供:
 *  - [cacheFileName]:缓存文件名(放 [cacheDir] 下)
 *  - [fetchHtml]:抓取原始 HTML(含各源专属 URL / headers / CF 挑战检测等)
 *  - [packResult]:把原始 HTML 解析并包装成各源的 Result 类型
 *
 * 缓存策略(仅在 [cacheDir] 非空时生效):
 *  1. 缓存存在且未过 [CACHE_TTL_MS] → 直接返回(不打网络)
 *  2. 否则走网络;成功则更新缓存后返回
 *  3. 网络失败但有缓存(无论是否过期)→ 回退缓存兜底,优先保可用
 *  4. 网络失败且无缓存 → 抛出原异常,交由 UI 显示错误态
 *
 * [refreshMutex] 保证并发调用只触发一次真实网络请求。
 *
 * 缓存格式:`<fetchedAt 毫秒>\n<原始 HTML>`(首行时间戳头简单拼接,避免 JSON 再包一层)。
 *
 * @param R 各源的 Result 类型(含 fetchedAt + 解析后的数据)
 * @param cacheDir 缓存根目录,通常传 application.cacheDir;传 null 关闭缓存(退化为直连)
 */
abstract class BaseHtmlCacheRepository<R>(
    private val cacheDir: File? = null
) {

    /** 串行化 [fetch],避免短时间内并发刷新重复打网络。 */
    private val refreshMutex = Mutex()

    /** 缓存文件名(放在 [cacheDir] 下)。 */
    protected abstract val cacheFileName: String

    /**
     * 抓取原始 HTML。子类实现各源的请求(URL / headers / CF 挑战检测等专属逻辑)。
     * 在 [Dispatchers.IO] 上执行(内部 HTTP 调用自行切 IO)。
     */
    protected abstract suspend fun fetchHtml(): String

    /**
     * 把原始 HTML 解析并包装成 Result 类型。
     *
     * @param fetchedAt 数据落盘时刻(命中缓存时是缓存写入时刻,走网络时是当前时刻)
     * @param rawHtml 原始 HTML(来自网络或缓存)
     */
    protected abstract fun packResult(fetchedAt: Long, rawHtml: String): R

    /**
     * 拉取数据,带文件缓存。
     *
     * 命中缓存秒回时 [R] 的 fetchedAt 用缓存写入时刻(即「上次刷新时间」),
     * 走网络时用当前时刻。UI 据此显示数据新鲜度。
     */
    suspend fun fetch(): R {
        // 无缓存目录:退化为直连,fetchedAt 取当前时刻。
        if (cacheDir == null) {
            val html = fetchHtml()
            return packResult(System.currentTimeMillis(), html)
        }

        return refreshMutex.withLock {
            val cached = readCache()
            // 1) 命中新缓存:秒回,不打网络。fetchedAt 用缓存写入时刻。
            if (cached != null && !isStale(cached.first)) {
                return@withLock packResult(cached.first, cached.second)
            }
            // 2) 走网络刷新(仅一次)。
            val result = runCatching { fetchHtml() }
            if (result.isSuccess) {
                val html = result.getOrThrow()
                val now = System.currentTimeMillis()
                writeCache(now, html)
                return@withLock packResult(now, html)
            }
            // 3) 网络失败:有过期缓存就兜底。fetchedAt 仍用缓存时刻,提示数据已旧。
            if (cached != null && cached.second.isNotBlank()) {
                return@withLock packResult(cached.first, cached.second)
            }
            // 4) 既没缓存又没网络:抛出原始失败。
            throw result.exceptionOrNull() ?: RuntimeException("未知错误")
        }
    }

    /**
     * 强制忽略缓存重新抓取(下拉刷新等场景)。
     * 抓取成功后刷新缓存,fetchedAt 取当前时刻。
     */
    suspend fun forceRefresh(): R {
        val html = fetchHtml()
        val now = System.currentTimeMillis()
        if (cacheDir != null) {
            writeCache(now, html)
        }
        return packResult(now, html)
    }

    private companion object {
        /** HTML 列表缓存有效期:4 小时(四个 HTML 源统一对齐)。 */
        const val CACHE_TTL_MS = 4L * 60 * 60 * 1000
    }

    // ===== 缓存读写(存原始 HTML + 写入时刻,首行注入时间戳头) =====

    private fun cacheFile(): File = File(cacheDir, cacheFileName)

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
