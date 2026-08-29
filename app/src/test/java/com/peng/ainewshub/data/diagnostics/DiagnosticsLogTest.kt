package com.peng.ainewshub.data.diagnostics

import com.peng.ainewshub.data.AppException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * [DiagnosticsLog] 环形记录与 [formatReport] 拼装回归。
 *
 * 落盘走真实 IO 协程(fire-and-forget),断言前用 [DiagnosticsLog.awaitWrites]
 * 排空在途写入(Mutex 公平排队);时间戳经 record 的测试入参注入,
 * 保证「新→旧」排序断言不受同毫秒记录影响。
 */
class DiagnosticsLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        DiagnosticsLog.reconfigureForTest(null)
    }

    private fun boot() {
        DiagnosticsLog.reconfigureForTest(File(tmp.root, "diagnostics/recent_errors.json"))
    }

    @Test
    fun `NoData 例行异常不记录其余记录`() = runTest {
        boot()
        DiagnosticsLog.record(AppException.NoData(), atMs = 1L)
        DiagnosticsLog.awaitWrites()
        assertTrue(DiagnosticsLog.snapshot().isEmpty())

        DiagnosticsLog.record(IOException("connection reset"), atMs = 2L)
        DiagnosticsLog.awaitWrites()
        val snapshot = DiagnosticsLog.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals("IOException", snapshot.first().kind)
        assertTrue("connection reset" in snapshot.first().detail)
    }

    @Test
    fun `环形容量 20 超出丢最旧`() = runTest {
        boot()
        // 每条落定后再记下一条:IO 线程池并发调度下追加顺序不保证等于 launch 顺序,
        // 逐条排空让「丢最旧」按注入的时间戳序确定成立,专注测环形语义本身
        repeat(25) { i ->
            DiagnosticsLog.record(IOException("e$i"), atMs = 1_000L + i)
            DiagnosticsLog.awaitWrites()
        }
        val snapshot = DiagnosticsLog.snapshot()
        assertEquals(20, snapshot.size)
        assertEquals("e24", snapshot.first().detail) // 新→旧
        assertEquals("e5", snapshot.last().detail)
    }

    @Test
    fun `落盘后重初始化可恢复`() = runTest {
        boot()
        DiagnosticsLog.record(IOException("persist-me"), atMs = 123L)
        DiagnosticsLog.awaitWrites()
        // 模拟进程重启:全新内存态指向同一文件,首次 snapshot 懒加载历史
        DiagnosticsLog.reconfigureForTest(File(tmp.root, "diagnostics/recent_errors.json"))
        val snapshot = DiagnosticsLog.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals("persist-me", snapshot.first().detail)
    }

    @Test
    fun `clear 清空内存与磁盘`() = runTest {
        boot()
        DiagnosticsLog.record(IOException("x"), atMs = 1L)
        DiagnosticsLog.awaitWrites()
        DiagnosticsLog.clear()
        assertTrue(DiagnosticsLog.snapshot().isEmpty())
        assertFalse(File(tmp.root, "diagnostics/recent_errors.json").isFile)
    }

    @Test
    fun `formatReport 拼装环境信息崩溃与错误`() {
        val env = ReportEnv(
            generatedAtMs = 1_756_000_000_000L,
            versionName = "1.2.13",
            versionCode = 10213L,
            device = "Google Pixel 8",
            android = "Android 15 (API 35)",
            language = "zh-CN",
            offline = true
        )
        val errors = listOf(
            DiagEntry(1L, "Network", "HTTP 404"),
            DiagEntry(2L, "AiAuth", "401 unauthorized")
        )
        val text = formatReport(env, "java.lang.RuntimeException: boom", errors)
        assertTrue("1.2.13 (10213)" in text)
        assertTrue("Google Pixel 8" in text)
        assertTrue("Android 15 (API 35)" in text)
        assertTrue("离线兜底中: 是" in text)
        assertTrue("RuntimeException: boom" in text)
        assertTrue("Network: HTTP 404" in text)
        assertTrue("AiAuth: 401 unauthorized" in text)
        // 空分支:无崩溃无错误时两节都显示「无」
        val empty = formatReport(env.copy(offline = false), null, emptyList())
        assertTrue("离线兜底中: 否" in empty)
        assertTrue("最近崩溃:\n无" in empty)
        assertTrue("最近错误(最多 20 条,新→旧):\n无" in empty)
    }

    @Test
    fun `未初始化时 record 静默丢弃不抛错`() {
        DiagnosticsLog.reconfigureForTest(null)
        DiagnosticsLog.record(IOException("before-init")) // 不抛即通过
    }
}
