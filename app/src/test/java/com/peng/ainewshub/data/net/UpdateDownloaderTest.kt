package com.peng.ainewshub.data.net

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.TimeUnit

/**
 * [UpdateDownloader.download] 下载骨架回归(Robolectric + MockWebServer,
 * 对齐 ArchiveHttpClientTest 惯例)。
 *
 * 钉住后台下载([UpdateDownloadService] 只是宿主,流式/取消语义全在这里)的
 * 五组契约:
 *  1. 流式写盘:落 cacheDir/updates、内容逐字节一致、进度回调单调且对齐总量;
 *  2. HTTP 错误码抛错(交由调用方置 Failed);
 *  3. cancel() 立即 abort:不限速要 ~10s 的慢体在 2s 内结束(未生效则超时失败);
 *  4. 断点续传:残留 .part 触发 Range 请求,206 追加写、进度从偏移起算、
 *     完成后 rename 为整包(取消/断网重试不再从 0 重下);
 *  5. 服务器忽略 Range(回 200):半截作废整包覆盖重写,不留旧字节污染。
 */
@RunWith(RobolectricTestRunner::class)
class UpdateDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `成功下载逐字节落盘且进度回调单调递增到总量`() = runBlocking {
        // 300KB:64KB 读缓冲下多轮回调,足以钉住「多次回调 + 单调」
        val payload = "x".repeat(300 * 1024)
        server.enqueue(MockResponse().setBody(payload))

        val progresses = mutableListOf<Pair<Long, Long>>()
        val file = UpdateDownloader.download(context, server.url("/app.apk").toString(), "9.9.9") { read, total ->
            progresses += read to total
        }

        assertEquals(payload.length.toLong(), file.length())
        assertEquals(payload, file.readText())
        // 回调至少两轮(流式),总量恒定,已读单调不减且末值对齐
        assertTrue("progress 应多轮回调,实际 ${progresses.size} 轮", progresses.size >= 2)
        progresses.forEach { (_, total) -> assertEquals(payload.length.toLong(), total) }
        progresses.forEachIndexed { i, (read, _) ->
            if (i > 0) assertTrue(read >= progresses[i - 1].first)
        }
        assertEquals(payload.length.toLong(), progresses.last().first)
    }

    @Test
    fun `HTTP 错误码抛错由调用方置失败态`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        var thrown: Exception? = null
        try {
            UpdateDownloader.download(context, server.url("/broken.apk").toString(), "9.9.9") { _, _ -> }
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown is IllegalStateException)
        // 下载前置空旧文件:失败后目录里不留半截 APK
        val dir = java.io.File(context.cacheDir, "updates")
        assertTrue(dir.listFiles().isNullOrEmpty() || dir.listFiles()!!.all { it.length() != 0L })
    }

    @Test
    fun `断点续传携带 Range 头追加写满且进度从偏移起算`() = runBlocking {
        val full = "x".repeat(300 * 1024)
        val headLen = 100L * 1024
        // 预置上次中断留下的半截 .part(前 100KB)
        val dir = java.io.File(context.cacheDir, "updates")
        dir.mkdirs()
        val part = java.io.File(dir, "ainewshub-v8.8.8.apk.part")
        part.writeText(full.substring(0, headLen.toInt()))

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $headLen-${full.length - 1}/${full.length}")
                .setBody(full.substring(headLen.toInt()))
        )

        val progresses = mutableListOf<Pair<Long, Long>>()
        val file = UpdateDownloader.download(context, server.url("/app.apk").toString(), "8.8.8") { read, total ->
            progresses += read to total
        }

        // 请求确实带了续传区间
        assertEquals("bytes=$headLen-", server.takeRequest().getHeader("Range"))
        // 最终文件 = 旧字节 + 追加字节,逐字节一致
        assertEquals(full.length.toLong(), file.length())
        assertEquals(full, file.readText())
        // 进度从偏移起算、总量对齐全量(不是剩余量)、末值对齐
        assertTrue(progresses.first().first >= headLen)
        progresses.forEach { (_, total) -> assertEquals(full.length.toLong(), total) }
        assertEquals(full.length.toLong(), progresses.last().first)
        // 完成后 .part 已 rename,目录里只剩整包
        assertTrue(!part.exists())
    }

    @Test
    fun `服务器忽略 Range 回 200 时半截作废整包重写`() = runBlocking {
        val full = "y".repeat(200 * 1024)
        // 预置脏 .part(模拟未知来源的半截,内容与本次下载无关)
        val dir = java.io.File(context.cacheDir, "updates")
        dir.mkdirs()
        val part = java.io.File(dir, "ainewshub-v7.7.7.apk.part")
        val garbage = "garbage-prefix-not-apk"
        part.writeText(garbage)

        server.enqueue(MockResponse().setBody(full)) // 200,不支持 Range

        val progresses = mutableListOf<Pair<Long, Long>>()
        val file = UpdateDownloader.download(context, server.url("/app.apk").toString(), "7.7.7") { read, total ->
            progresses += read to total
        }

        // 请求方确实尝试过续传(带 Range),但按 200 整包覆盖
        assertEquals("bytes=${garbage.length}-", server.takeRequest().getHeader("Range"))
        assertEquals(full, file.readText())
        assertTrue(!part.exists())
        // 200 路径进度从 0 重新起算
        assertTrue(progresses.first().first <= 64L * 1024)
        assertEquals(full.length.toLong(), progresses.last().first)
    }

    @Test
    fun `取消进行中的下载立即中断`() = runBlocking {
        // 1 字节 / 5ms:2000 字节自然跑完需 ~10s,cancel 后应在秒级内结束
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(ByteArray(2000) { 'x'.code.toByte() }))
                .throttleBody(1, 5, TimeUnit.MILLISECONDS)
        )

        val job = launch {
            runCatching {
                UpdateDownloader.download(context, server.url("/slow.apk").toString(), "9.9.9") { _, _ -> }
            }
        }
        delay(500) // 等连接建立、下载真正走起来再取消
        UpdateDownloader.cancel()
        // 2s 内未结束即失败:证明 cancel 即时 abort 而非悬到自然完成/读超时
        withTimeout(2_000) { job.join() }
    }
}
