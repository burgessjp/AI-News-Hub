package com.peng.ainewshub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * [PipelineSchedule] 批次时刻推导回归。
 *
 * 钉住三条契约:
 *  - 内置默认批次表与流水线调度一致(docs/agents/pipeline.md 的北京时间
 *    08:00/18:00),且进程初始生效表即默认表;
 *  - nextBatchEpoch 的边界语义:恰好在批次时刻 → 该批已过,指向下一批;全天过完 → 明天第一批;
 *  - applyBatchSlots(远程 app_config.json 到达入口)的校验与归一语义。
 */
class PipelineScheduleTest {

    /** 单例状态隔离:每个用例从内置默认表出发(testing.md 的 object 单例重置约定)。 */
    @Before
    fun setUp() {
        PipelineSchedule.resetForTest()
    }

    /** 构造北京时间某年月日时分(秒/毫秒归零)的 epoch 毫秒。 */
    private fun beijing(h: Int, m: Int, day: Int = 29, month: Int = Calendar.AUGUST, year: Int = 2026): Long =
        Calendar.getInstance(PipelineSchedule.BEIJING).apply {
            set(year, month, day, h, m, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `内置默认批次表与流水线约定一致`() {
        assertEquals(listOf(8 to 0, 18 to 0), PipelineSchedule.DEFAULT_BATCH_SLOTS)
        assertEquals(PipelineSchedule.DEFAULT_BATCH_SLOTS, PipelineSchedule.batchSlots)
    }

    @Test
    fun `批次前返回当天下一批`() {
        assertEquals(beijing(8, 0), PipelineSchedule.nextBatchEpoch(beijing(7, 0)))
        assertEquals(beijing(18, 0), PipelineSchedule.nextBatchEpoch(beijing(12, 34)))
        // 18:00 是今天最后一批,20:00 已过 → 明天第一批
        assertEquals(beijing(8, 0, day = 30), PipelineSchedule.nextBatchEpoch(beijing(20, 0)))
    }

    @Test
    fun `恰好到达批次时刻视为已过该批`() {
        assertEquals(beijing(18, 0), PipelineSchedule.nextBatchEpoch(beijing(8, 0)))
        assertEquals(beijing(8, 0, day = 30), PipelineSchedule.nextBatchEpoch(beijing(18, 0)))
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

    @Test
    fun `应用自定义批次表后 nextBatchEpoch 按新表计算`() {
        assertTrue(PipelineSchedule.applyBatchSlots(listOf(6 to 30, 12 to 0)))
        assertEquals(listOf(6 to 30, 12 to 0), PipelineSchedule.batchSlots)
        assertEquals(beijing(6, 30), PipelineSchedule.nextBatchEpoch(beijing(5, 0)))
        assertEquals(beijing(12, 0), PipelineSchedule.nextBatchEpoch(beijing(6, 30)))
        // 全天过完 → 明天新表第一批(6:30)
        assertEquals(beijing(6, 30, day = 30), PipelineSchedule.nextBatchEpoch(beijing(23, 0)))
    }

    @Test
    fun `非法批次表被拒绝且保持原值`() {
        assertTrue(PipelineSchedule.applyBatchSlots(listOf(6 to 0)))
        assertFalse(PipelineSchedule.applyBatchSlots(emptyList()))
        assertFalse(PipelineSchedule.applyBatchSlots(listOf(24 to 0)))
        assertFalse(PipelineSchedule.applyBatchSlots(listOf(8 to 60)))
        assertEquals(listOf(6 to 0), PipelineSchedule.batchSlots)
    }

    @Test
    fun `批次表去重排序归一且值未变化时返回 false`() {
        // 先换到非默认表,再喂「归一后与当前一致」的重复输入 → 无变化
        assertTrue(PipelineSchedule.applyBatchSlots(listOf(20 to 0)))
        assertFalse(PipelineSchedule.applyBatchSlots(listOf(20 to 0, 20 to 0)))
        // 乱序 + 重复输入归一为新表(与当前不同)→ 生效且排序
        assertTrue(PipelineSchedule.applyBatchSlots(listOf(22 to 0, 8 to 0, 8 to 0, 18 to 0)))
        assertEquals(listOf(8 to 0, 18 to 0, 22 to 0), PipelineSchedule.batchSlots)
        // 归一后与当前一致 → 无变化
        assertFalse(PipelineSchedule.applyBatchSlots(listOf(18 to 0, 8 to 0, 22 to 0)))
    }
}
