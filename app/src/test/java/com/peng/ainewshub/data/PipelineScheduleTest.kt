package com.peng.ainewshub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [PipelineSchedule] 批次时刻推导回归。
 *
 * 钉住两条契约:
 *  - 批次表与流水线调度一致(docs/agents/pipeline.md 的北京时间 08:00/18:00/22:00);
 *  - nextBatchEpoch 的边界语义:恰好在批次时刻 → 该批已过,指向下一批;全天过完 → 明天第一批。
 */
class PipelineScheduleTest {

    /** 构造北京时间某年月日时分(秒/毫秒归零)的 epoch 毫秒。 */
    private fun beijing(h: Int, m: Int, day: Int = 29, month: Int = Calendar.AUGUST, year: Int = 2026): Long =
        Calendar.getInstance(PipelineSchedule.BEIJING).apply {
            set(year, month, day, h, m, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `批次表与流水线约定一致`() {
        assertEquals(listOf(8 to 0, 18 to 0, 22 to 0), PipelineSchedule.BATCH_SLOTS)
    }

    @Test
    fun `批次前返回当天下一批`() {
        assertEquals(beijing(8, 0), PipelineSchedule.nextBatchEpoch(beijing(7, 0)))
        assertEquals(beijing(18, 0), PipelineSchedule.nextBatchEpoch(beijing(12, 34)))
        assertEquals(beijing(22, 0), PipelineSchedule.nextBatchEpoch(beijing(20, 0)))
    }

    @Test
    fun `恰好到达批次时刻视为已过该批`() {
        assertEquals(beijing(18, 0), PipelineSchedule.nextBatchEpoch(beijing(8, 0)))
        assertEquals(beijing(22, 0), PipelineSchedule.nextBatchEpoch(beijing(18, 0)))
    }

    @Test
    fun `全天批次过完指向明天第一批`() {
        val next = PipelineSchedule.nextBatchEpoch(beijing(23, 59))
        assertEquals(beijing(8, 0, day = 30), next)
    }

    @Test
    fun `返回值恒为未来时刻`() {
        val now = beijing(9, 30)
        assertTrue(PipelineSchedule.nextBatchEpoch(now) > now)
    }
}
