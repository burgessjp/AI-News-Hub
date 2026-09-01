package com.peng.ainewshub.data

import java.util.Calendar
import java.util.TimeZone

/**
 * 流水线批次时刻表 —— 批次时间的唯一真相源(北京时间)。
 *
 * 实际生效表 = 内置默认 [DEFAULT_BATCH_SLOTS](08:00 / 18:00,与流水线调度
 * 一致,见 docs/agents/pipeline.md),可被数据仓库根级 `app_config.json` 的
 * `batch_slots` 远程覆盖(见 [com.peng.ainewshub.data.source.AppConfigSync]:
 * 每次 App 启动与每日通知 Worker 运行前尝试刷新,拉取/解析失败静默保持当前值)。
 *  - 每日通知的检查时刻(notify/DailyUpdateNotifier 的 CHECK_SLOTS)由
 *    [batchSlots] + 40 分钟余量派生;
 *  - 总览页「下一批」展示与「已是最新」胶囊文案经 [nextBatchEpoch] 计算。
 */
object PipelineSchedule {

    /** 北京时区(批次调度与「同一天」判定都用它,不随设备时区漂移)。 */
    val BEIJING: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    /** 内置默认批次(北京时间,小时 to 分钟):远程配置缺失/失败时的兜底。 */
    val DEFAULT_BATCH_SLOTS: List<Pair<Int, Int>> = listOf(8 to 0, 18 to 0)

    /** 当前生效批次表(初始为内置默认,远程配置应用后覆盖;派生方从这里取值)。 */
    @Volatile
    private var activeSlots: List<Pair<Int, Int>> = DEFAULT_BATCH_SLOTS

    /** 当前生效批次表的只读视图。 */
    val batchSlots: List<Pair<Int, Int>> get() = activeSlots

    /**
     * 校验并应用新的批次表(远程配置到达的唯一入口)。
     * 空表或任一小时不在 0..23、分钟不在 0..59 拒绝应用,保持当前生效表不变;
     * 通过校验的表去重并升序归一后生效。加锁:启动 Host 与通知 Worker 两个触发
     * 点可能并发刷新,check-then-set 须原子。
     *
     * @return true 表示生效表发生了变化(调用方可据此重排定时任务)
     */
    @Synchronized
    fun applyBatchSlots(slots: List<Pair<Int, Int>>): Boolean {
        if (slots.isEmpty() || slots.any { (h, m) -> h !in 0..23 || m !in 0..59 }) return false
        val normalized = slots.distinct().sortedBy { (h, m) -> h * 60 + m }
        if (normalized == activeSlots) return false
        activeSlots = normalized
        return true
    }

    /** 重置为内置默认表(仅测试重置入口调用)。 */
    @Synchronized
    internal fun resetForTest() {
        activeSlots = DEFAULT_BATCH_SLOTS
    }

    /**
     * 下一个未来批次的绝对时间(epoch 毫秒):今天批次已全部过完 → 明天第一批。
     * 返回 epoch,展示侧自行按设备时区格式化(与全 App「北京定义、本地显示」
     * 的时间口径一致,如总览「数据截至」)。
     */
    fun nextBatchEpoch(nowMillis: Long = System.currentTimeMillis()): Long {
        val now = Calendar.getInstance(BEIJING).apply { timeInMillis = nowMillis }
        for ((hour, minute) in activeSlots) {
            val candidate = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (candidate.after(now)) return candidate.timeInMillis
        }
        val tomorrowFirst = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, activeSlots.first().first)
            set(Calendar.MINUTE, activeSlots.first().second)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return tomorrowFirst.timeInMillis
    }
}
