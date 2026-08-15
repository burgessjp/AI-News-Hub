package com.peng.ainewshub.data.source

import android.content.Context
import java.io.File

/**
 * 归档数据磁盘缓存 —— 断网/冷启动兜底([ArchiveHttpClient] 网络失败时回退读取盘上旧数据)。
 *
 * 设计要点:
 *  - 目录 cacheDir/archives/:自动纳入 CacheManager 的占用统计与「清理缓存」,
 *    「清缓存即清离线数据」语义自然,无需新增设置项;被清理后下次写入按需重建。
 *  - 文件名由缓存键 sanitize 而来('/' → '_',目录扁平可读,便于排查);
 *    键形如 "index.json" / "<source>/<日期>/<时间>-data.json",均为固定字符集,无穿越风险。
 *  - 总量护栏 [MAX_BYTES]:写入后超限按 lastModified 从最旧开始淘汰。单条快照数百 KB,
 *    64MB 足以容纳 8 源近期快照 + 全部根级索引文件;淘汰最旧即等价于淘汰过时日期。
 *  - 线程模型:读写为普通阻塞 IO,调用方([ArchiveHttpClient])已在 Dispatchers.IO 上;
 *    init 仅建目录引用(懒建目录本身),主线程调用也轻量。
 *  - 所有失败静默:落盘是尽力而为,盘上数据缺失只是少了兜底,不影响主流程。
 */
object ArchiveDiskCache {

    /** 缓存根目录名(位于 cacheDir 下)。 */
    private const val DIR_NAME = "archives"

    /** 总量护栏:超出后从最旧开始淘汰。 */
    private const val MAX_BYTES = 64L * 1024 * 1024

    @Volatile
    private var dir: File? = null

    /** 初始化缓存目录(Application.onCreate 调用,幂等)。 */
    fun init(context: Context) {
        if (dir != null) return
        synchronized(this) {
            if (dir == null) {
                dir = File(context.applicationContext.cacheDir, DIR_NAME)
            }
        }
    }

    /** 读缓存;未初始化/文件不存在/读取失败一律返回 null(调用方按无兜底处理)。 */
    fun read(key: String): String? = runCatching {
        fileOf(key).takeIf { it.isFile }?.readText()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 写缓存(write-through);失败静默。 */
    fun write(key: String, text: String) {
        runCatching {
            val d = dir ?: return
            d.mkdirs()
            fileOf(key).writeText(text)
            evictIfOverLimit(d)
        }
    }

    /** 键 → 文件:路径分隔符替换为 '_',保持扁平。未初始化时抛错,由调用方 runCatching 吞掉。 */
    private fun fileOf(key: String): File {
        val d = dir ?: error("ArchiveDiskCache 未初始化")
        return File(d, key.replace('/', '_'))
    }

    /** 超量淘汰:按 lastModified 升序(最旧先删)直至回到限额内。 */
    private fun evictIfOverLimit(d: File) {
        val files = d.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
