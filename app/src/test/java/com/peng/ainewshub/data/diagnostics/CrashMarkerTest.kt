package com.peng.ainewshub.data.diagnostics

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [CrashMarker] 崩溃钩子回归(Robolectric 提供 filesDir 与 Application context)。
 * 直接调用 handler 的 uncaughtException 模拟崩溃;previous handler 用假桩验证链式委托。
 */
@RunWith(RobolectricTestRunner::class)
class CrashMarkerTest {

    private val originalHandler = Thread.getDefaultUncaughtExceptionHandler()

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    @Test
    fun `崩溃现场写入 last_crash 且委托 previous handler`() {
        val context = RuntimeEnvironment.getApplication()
        val chained = AtomicBoolean(false)
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> chained.set(true) }
        CrashMarker.install(context)

        val handler = checkNotNull(Thread.getDefaultUncaughtExceptionHandler())
        handler.uncaughtException(Thread.currentThread(), RuntimeException("boom-diag"))

        val text = File(context.filesDir, "last_crash.txt").readText()
        assertTrue("RuntimeException" in text)
        assertTrue("boom-diag" in text)
        assertTrue("thread=" in text)
        assertTrue(chained.get())
    }

    @Test
    fun `重复 install 幂等不重复垫层`() {
        CrashMarker.install(RuntimeEnvironment.getApplication())
        val first = Thread.getDefaultUncaughtExceptionHandler()
        CrashMarker.install(RuntimeEnvironment.getApplication())
        assertTrue(first === Thread.getDefaultUncaughtExceptionHandler())
    }
}
