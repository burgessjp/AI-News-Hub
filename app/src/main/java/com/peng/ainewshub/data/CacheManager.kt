package com.peng.ainewshub.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 缓存统一清理入口 —— 集中处理 App 所有「可安全清理」的本地数据。
 *
 * 清理范围(均为可恢复数据,不影响用户配置与 AI key):
 *  - WebView Cookie / Web Storage(随浏览不断累积的持久化数据)
 *  - Coil 图片缓存(磁盘 + 内存)
 *  - 翻译缓存文件 `hn_translations.json`
 *  - 5 个榜单源列表缓存文件(HackerNews/GitHub/HuggingFace/stormzhang/Rundown)
 *  - 浏览历史([BrowseHistoryRepository.clearAll])
 *  - 搜索历史([SettingsStore.clearSearchHistory])
 *
 * **不清**:主题/字体/字号/数据源模式、AI 配置与 API key、token 用量统计(另有独立入口)。
 *
 * 每个清理点均独立 runCatching 吞异常 —— 单点失败不阻断其余清理(各源互不依赖)。
 */
object CacheManager {

    /**
     * 计算 [context.cacheDir] 占用字节数。
     *
     * cacheDir 覆盖 Coil 图片缓存、翻译缓存、6 源列表缓存等所有文件类缓存。
     * WebView 缓存不在此目录(AndroidX 内部路径),故该值为近似占用,符合常见 App 惯例。
     */
    suspend fun sizeBytes(context: Context): Long = withContext(Dispatchers.IO) {
        dirSize(context.cacheDir)
    }

    /**
     * 全量清理(在 IO 线程执行文件/DB/DataStore 操作,WebView 三件套切回主线程)。
     *
     * @param browseHistoryRepository 浏览历史仓库(清空 browse_history 表)
     * @param settingsStore 设置存储(只清 search_history key,不动其它偏好)
     */
    @OptIn(ExperimentalCoilApi::class)
    suspend fun clear(
        context: Context,
        browseHistoryRepository: BrowseHistoryRepository,
        settingsStore: com.peng.ainewshub.ui.more.SettingsStore
    ) {
        // WebView 的 Cookie / Web Storage 持久化数据必须在主线程清理。
        // (WebView 的 HTTP 缓存是 per-application 的内存/磁盘缓存,随浏览累积,
        //  下面的 cacheDir 清理会一并删掉其磁盘部分;WebStorage/Cookie 才是需要主动清的持久态。)
        withContext(Dispatchers.Main) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().removeSessionCookies(null)
                CookieManager.getInstance().flush()
            }
            runCatching { WebStorage.getInstance().deleteAllData() }
        }

        withContext(Dispatchers.IO) {
            // Coil 两级缓存(默认全局 ImageLoader)
            runCatching {
                val loader = Coil.imageLoader(context)
                loader.diskCache?.clear()
                loader.memoryCache?.clear()
            }
            // 翻译缓存 + 6 源列表缓存(清空 cacheDir 下所有内容,但保留 cacheDir 根目录本身 ——
            // 若连根目录一起删,WebView 等组件下次访问 cacheDir/WebView 子目录时会打 warning。
            // 各组件会按需重建自己的子目录,删后 App 正常运行。)
            runCatching {
                context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            }
            // 浏览历史(Room)
            runCatching { browseHistoryRepository.clearAll() }
            // 搜索历史(DataStore,只 remove search_history key)
            runCatching { settingsStore.clearSearchHistory() }
        }
    }

    /** 递归计算目录总字节数。 */
    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 字节数格式化为人类可读大小。
     *
     * - < 1 KB 显示「< 1 KB」(避免显示 0 KB 误导)
     * - < 1 MB 显示「X.X KB」
     * - ≥ 1 MB 显示「X.X MB」
     */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "< 1 KB"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        return String.format("%.1f MB", kb / 1024.0)
    }
}
