package com.peng.ainewshub.data

import java.util.Calendar
import java.util.TimeZone

/**
 * 流水线批次时刻表 —— 批次时间的唯一真相源(北京时间)。
 *
 * 流水线每天三批:08:00 / 18:00 由仓库外机器调度,22:00 由仓库 workflow 承载
 * (见 docs/agents/pipeline.md)。**改动批次时间只改这里**:
 *  - 每日通知的检查时刻(notify/DailyUpdateNotifier 的 CHECK_SLOTS)由
 *    [BATCH_SLOTS] + 40 分钟余量派生;
 *  - 总览页「下一批」展示与「已是最新」胶囊文案经 [nextBatchEpoch] 计算。
 */
object PipelineSchedule {

    /** 北京时区(批次调度与「同一天」判定都用它,不随设备时区漂移)。 */
    val BEIJING: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    /** 批次时刻(北京时间,小时 to 分钟),顺序即当日先后。 */
    val BATCH_SLOTS: List<Pair<Int, Int>> = listOf(8 to 0, 18 to 0, 22 to 0)

    /**
     * 下一个未来批次的绝对时间(epoch 毫秒):今天批次已全部过完 → 明天第一批。
     * 返回 epoch,展示侧自行按设备时区格式化(与全 App「北京定义、本地显示」
     * 的时间口径一致,如总览「数据截至」)。
     */
    fun nextBatchEpoch(nowMillis: Long = System.currentTimeMillis()): Long {
        val now = Calendar.getInstance(BEIJING).apply { timeInMillis = nowMillis }
        for ((hour, minute) in BATCH_SLOTS) {
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
            set(Calendar.HOUR_OF_DAY, BATCH_SLOTS.first().first)
            set(Calendar.MINUTE, BATCH_SLOTS.first().second)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return tomorrowFirst.timeInMillis
    }
}
