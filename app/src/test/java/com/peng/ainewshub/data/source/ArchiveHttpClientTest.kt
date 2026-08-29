package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.AppException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.nio.charset.StandardCharsets.UTF_8

/**
 * [ArchiveHttpClient] 取数骨架回归(MockWebServer + 真实归档 fixture)。
 *
 * fixture 取自数据仓库真实批次(repo/ 裁剪,见 app/src/test/resources/fixtures/),
 * 覆盖四组语义:
 *  1. index 寻址 → 快照拉取 → items 映射(fetchItemsList)
 *  2. 缓存与刷新:TTL 缓存复用 / force 的锁内去重窗口 / 窗口外真实重打
 *  3. 错误分野:HTTP 错误直接抛(不兜底)vs 传输层失败读盘兜底并置 offlineMode;
 *     networkOnly 探测永远不兜底
 *  4. 根级独立文件:trends「成功才写」404 → null;history 三索引结构映射
 */
@RunWith(RobolectricTestRunner::class)
class ArchiveHttpClientTest {

    /** gitcode REST 路径前缀(与被测端点拼法一致,fixture 按去掉前缀后的仓库相对路径存放)。 */
    private val pathPrefix = "/api/v5/repos/peng1818/AI-News-Hub-Data/raw/"

    /** 按请求路径读取 fixture;支持逐用例覆写(错误码 / 内容替换)。 */
    private inner class FixtureDispatcher : Dispatcher() {

        /** 已命中的仓库相对路径记录(统计请求数用)。 */
        val hits = mutableListOf<String>()

        /** 覆写规则:相对路径 → 响应;命中即短路 fixture。 */
        val overrides = mutableMapOf<String, MockResponse>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val rel = (request.path ?: "").substringAfter(pathPrefix).substringBefore("?")
            overrides[rel]?.let { return it }
            val stream = javaClass.classLoader?.getResourceAsStream("fixtures/$rel")
                ?: return MockResponse().setResponseCode(404)
            hits += rel
            return MockResponse().setResponseCode(200)
                .setBody(String(stream.readBytes(), UTF_8))
        }
    }

    /** 断网模拟:指向必然连接拒绝的本地端口,传输层失败即 IOException(与真实断网同语义)。 */
    private val deadBaseUrl = "http://127.0.1:1${pathPrefix.trimEnd('/')}"

    private lateinit var server: MockWebServer
    private lateinit var dispatcher: FixtureDispatcher

    @Before
    fun setUp() {
        server = MockWebServer()
        dispatcher = FixtureDispatcher()
        server.dispatcher = dispatcher
        server.start()
        // 磁盘缓存初始化到 Robolectric 应用(断网兜底用例依赖真实落盘)
        ArchiveDiskCache.init(RuntimeEnvironment.getApplication())
        ArchiveHttpClient.reconfigureForTest(server.url(pathPrefix.trimEnd('/')).toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun indexHits(): Int = dispatcher.hits.count { it == "index.json" }

    // ===== 1. 基本取数 =====

    @Test
    fun `fetchItemsList 经 index 寻址拉快照并映射条目`() = runBlocking {
        val (fetchedAt, titles) = ArchiveHttpClient.fetchItemsList("hackernews") { obj, _ ->
            obj.optString("title").takeIf { it.isNotBlank() }
        }
        assertEquals(5, titles.size) // fixture 裁剪为 5 条
        assertTrue(titles.first().isNotBlank())
        // fetched_at_ms 取快照字段(非当前时刻兜底)
        assertEquals(1_787_972_486_201L, fetchedAt)
        // 两次请求:index.json + 快照本体
        assertEquals(2, dispatcher.hits.size)
    }

    @Test
    fun `fetchLatestPaths 返回全量源指针`() = runBlocking {
        val paths = ArchiveHttpClient.fetchLatestPaths()
        assertEquals(8, paths.size) // fixture index 保留全部 8 源 latest 指针
        assertTrue(paths.getValue("hackernews").endsWith("-data.json"))
    }

    // ===== 2. 缓存与刷新语义 =====

    @Test
    fun `index 两分钟 TTL 内复用不重打网络`() = runBlocking {
        ArchiveHttpClient.fetchLatestSnapshot("hackernews")
        ArchiveHttpClient.fetchLatestSnapshot("hackernews")
        ArchiveHttpClient.fetchLatestSnapshot("github-trending") // 快照不同,index 仍复用
        assertEquals(1, indexHits())
        assertEquals(3, dispatcher.hits.size) // index 1 次 + 两个快照各 1 次
    }

    @Test
    fun `force 在锁内去重窗口内复用刚刷新的 index`() = runBlocking {
        ArchiveHttpClient.fetchLatestSnapshot("hackernews")
        // 紧接着 force:窗口 2s 内不重复打网络(8 源并发下拉只发 1 次 index)
        ArchiveHttpClient.fetchLatestSnapshot("hackernews", force = true)
        assertEquals(1, indexHits())
    }

    @Test
    fun `force 超过去重窗口后真实重打网络`() = runBlocking {
        ArchiveHttpClient.fetchLatestSnapshot("hackernews")
        Thread.sleep(2_100) // 越过 FORCE_FETCH_DEDUP_MS(2s)
        ArchiveHttpClient.fetchLatestSnapshot("hackernews", force = true)
        assertEquals(2, indexHits())
        assertFalse(ArchiveHttpClient.offlineMode.value)
    }

    // ===== 3. 错误分野与兜底 =====

    @Test
    fun `HTTP 层错误直接抛 Network 不读盘兜底`() = runBlocking {
        dispatcher.overrides["index.json"] = MockResponse().setResponseCode(500)
        val error = runCatching { ArchiveHttpClient.fetchLatestSnapshot("hackernews") }.exceptionOrNull()
        assertTrue(error is AppException.Network)
        assertFalse(ArchiveHttpClient.offlineMode.value)
    }

    @Test
    fun `快照 items 为空抛 NoData`() = runBlocking {
        dispatcher.overrides["hackernews/2026-08-29/11-01-data.json"] = MockResponse().setResponseCode(200)
            .setBody("""{"fetched_at_ms":1,"items":[]}""")
        val error = runCatching { ArchiveHttpClient.fetchLatestSnapshot("hackernews") }.exceptionOrNull()
        assertTrue(error is AppException.NoData)
    }

    @Test
    fun `断网时读盘兜底返回旧数据并置 offlineMode`() = runBlocking {
        // 1) 网络成功一次 → index/快照 write-through 落盘(Robolectric cacheDir)
        ArchiveHttpClient.fetchLatestSnapshot("hackernews")
        // 2) 清内存缓存(等价进程重启后仅剩磁盘)并断网(连接拒绝 = IOException)
        ArchiveHttpClient.reconfigureForTest(deadBaseUrl)
        // 3) force 走网络失败 → 传输层 IOException → 读盘命中,返回旧快照
        val snapshot = ArchiveHttpClient.fetchLatestSnapshot("hackernews", force = true)
        assertEquals(5, snapshot.optJSONArray("items")!!.length())
        assertTrue(ArchiveHttpClient.offlineMode.value)
    }

    @Test
    fun `networkOnly 探测跳过缓存与磁盘兜底失败即抛`() = runBlocking {
        ArchiveHttpClient.fetchLatestOverview() // 正常路径先写入磁盘
        assertTrue(ArchiveHttpClient.fetchLatestOverview() != null)
        ArchiveHttpClient.reconfigureForTest(deadBaseUrl)
        // 探测语义:盘上有旧数据也绝不能当新批次,必须失败
        val error = runCatching {
            ArchiveHttpClient.fetchLatestOverview(networkOnly = true)
        }.exceptionOrNull()
        assertNotNull(error)
    }

    // ===== 4. 根级独立文件 =====

    @Test
    fun `trends 成功才写语义 404 返回 null 存在则返回内容`() = runBlocking {
        dispatcher.overrides["trends.json"] = MockResponse().setResponseCode(404)
        assertNull(ArchiveHttpClient.fetchLatestTrends())
        dispatcher.overrides.remove("trends.json")
        val trends = ArchiveHttpClient.fetchLatestTrends()
        assertNotNull(trends)
        assertTrue(trends!!.has("keywords"))
    }

    @Test
    fun `history 与 overview_history 结构映射`() = runBlocking {
        val history = ArchiveHttpClient.fetchHistory()
        assertEquals(setOf("hackernews", "github-trending"), history.keys) // fixture 裁剪为 2 源
        assertEquals(3, history.getValue("hackernews").size) // 每源 3 个日期

        val overview = ArchiveHttpClient.fetchOverviewHistory()
        assertEquals(3, overview.size)
        assertEquals("2026-08-29/11-01-data.json", overview.getValue("2026-08-29"))
    }
}
