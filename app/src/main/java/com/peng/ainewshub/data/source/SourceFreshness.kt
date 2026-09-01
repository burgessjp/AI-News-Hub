package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.source.ArchiveHttpClient
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 源新鲜度 —— 从 index.json 的 latest 指针推导每个源「最后一次成功抓取」的时刻。
 *
 * 流水线单源抓取失败时 latest 指针从上一次 index 继承(fetch_data.py 的失败保留
 * 机制),因此指针路径恒指向该源**最后一次成功**抓取的快照 —— 无需额外数据,
 * 解析路径里的日期+时间(北京时间)即得断供时长。manifest.json 虽有逐源成败状态,
 * 但 App 端不拉它;指针推导共享 index 2 分钟缓存,通常零额外网络。
 *
 * 指针相对路径形如 "2026-08-22/08-00-data.json"(相对源目录),日期/时间为流水线
 * 写入时的北京时间(恒 TZ=Asia/Shanghai,见 scripts/common.py)。
 */
object SourceFreshness {

    /** 北京时间(对齐 scripts/common.py 的 BEIJING_TZ 与 DailyUpdateNotifier 的 BEIJING)。 */
    private val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")

    /**
     * 断供阈值:距最后一次成功抓取超过此时长视为断供。
     * 当前一天 2 批(08:00 / 18:00),>24h 即全天全批失败,不会误伤单批抖动。
     */
    const val STALE_THRESHOLD_MS: Long = 24L * 60 * 60 * 1000

    private val REL_PATH_REGEX = Regex("(\\d{4})-(\\d{2})-(\\d{2})/(\\d{2})-(\\d{2})")

    /**
     * 解析 latest 指针相对路径为「最后一次成功抓取」的时刻(epoch 毫秒)。
     * 路径不含日期/时间或数值异常(非法日期等)返回 null。
     */
    fun parseLastSuccessMs(relPath: String): Long? {
        val m = REL_PATH_REGEX.find(relPath) ?: return null
        return runCatching {
            val (y, mo, d, h, mi) = m.destructured
            LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt())
                .atZone(BEIJING)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    /**
     * 各源「最后一次成功抓取」时刻:source key → epoch 毫秒。
     * 读 index.json 的 latest 指针表(共享 2 分钟缓存);index 拉取失败返回空 map
     * —— 健康度是增强信息,不值得为它单独弹错误态。
     */
    suspend fun lastSuccessTimes(): Map<String, Long> = runCatching {
        ArchiveHttpClient.fetchLatestPaths().mapNotNull { (k, v) ->
            parseLastSuccessMs(v)?.let { k to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    /** [lastMs] 距 [nowMs] 是否超过断供阈值(无效时刻恒 false)。 */
    fun isStale(lastMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        lastMs > 0 && nowMs - lastMs > STALE_THRESHOLD_MS

    /**
     * 断供天数(不足一天不显示;天数向下取整,至少 1):供「已 N 天未更新」文案。
     * 非断供(未超阈值/无效时刻)返回 null。
     */
    fun staleDays(lastMs: Long, nowMs: Long = System.currentTimeMillis()): Int? {
        if (!isStale(lastMs, nowMs)) return null
        return ((nowMs - lastMs) / STALE_THRESHOLD_MS).toInt().coerceAtLeast(1)
    }
}
