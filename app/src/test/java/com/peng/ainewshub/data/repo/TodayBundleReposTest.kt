package com.peng.ainewshub.data.repo

import com.peng.ainewshub.data.source.ArchiveDiskCache
import com.peng.ainewshub.data.source.ArchiveHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.nio.charset.StandardCharsets.UTF_8

/**
 * 「今天」页三 Repository 的聚合包(today.json)优先路径回归:
 *  - 命中聚合包:loadDigest / summarize / loadLatest 全部由 today.json 一次请求
 *    供给(零 index / 快照 / weekly.json 请求 —— 冷启动 11 → 2 的端侧依据);
 *  - 聚合包 404(流水线旧版本):三路各自回退既有路径,行为与引入前一致;
 *  - 包内缺某源键(单源失败批次):该源单独回退快照路径,其余源不受影响。
 */
@RunWith(RobolectricTestRunner::class)
class TodayBundleReposTest {

    private val pathPrefix = "/api/v5/repos/peng1818/AI-News-Hub-Data/raw/"

    private inner class FixtureDispatcher : Dispatcher() {
        /** 全部请求(含覆写 404/500 的试探)——统计聚合包试探次数用。 */
        val attempts = mutableListOf<String>()

        /** 命中 fixture 的成功请求。 */
        val hits = mutableListOf<String>()
        val overrides = mutableMapOf<String, MockResponse>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val rel = (request.path ?: "").substringAfter(pathPrefix).substringBefore("?")
            attempts += rel
            overrides[rel]?.let { return it }
            val stream = javaClass.classLoader?.getResourceAsStream("fixtures/$rel")
                ?: return MockResponse().setResponseCode(404)
            hits += rel
            return MockResponse().setResponseCode(200)
                .setBody(String(stream.readBytes(), UTF_8))
        }
    }

    private lateinit var server: MockWebServer
    private lateinit var dispatcher: FixtureDispatcher

    @Before
    fun setUp() {
        server = MockWebServer()
        dispatcher = FixtureDispatcher()
        server.dispatcher = dispatcher
        server.start()
        ArchiveDiskCache.init(RuntimeEnvironment.getApplication())
        ArchiveHttpClient.reconfigureForTest(server.url(pathPrefix.trimEnd('/')).toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `命中聚合包 三路取数只发一次请求`() = runBlocking {
        val overview = OverviewRepository().loadDigest().getOrThrow()
        val summary = SummaryRepository().summarize("hackernews").getOrThrow()
        val weekly = WeeklyRepository().loadLatest().getOrThrow()

        // 数据来自 fixture today.json:overview 与快照同构、summary 条目与快照
        // ai_summary_v2 同源、weekly 来自包内嵌段
        assertTrue(overview.items.isNotEmpty())
        assertTrue(overview.digest.isNotBlank())
        assertEquals(1_787_972_486_201L, summary.fetchedAtMs)
        assertTrue((summary.content as SummaryContent.Structured).items.isNotEmpty())
        assertEquals(1, weekly.issueNo)

        // 全程仅 today.json 一次网络请求(index / 快照 / weekly.json 均未打)
        assertEquals(1, dispatcher.hits.size)
        assertEquals(1, dispatcher.attempts.count { it == "today.json" })
    }

    @Test
    fun `聚合包缺失 三路回退既有路径`() = runBlocking {
        dispatcher.overrides["today.json"] = MockResponse().setResponseCode(404)
        // weekly.json fixture 不存在,回退路径用内联最小合法周刊
        dispatcher.overrides["weekly.json"] = MockResponse().setResponseCode(200).setBody(
            """{"weekStart":"2026-08-24","weekEnd":"2026-08-30","issueNo":1,
               "sections":[{"title":"板块","summary":"s","items":[
                 {"title":"t","url":"https://e.dev/1","source":"hackernews"}]}]}"""
        )

        val overview = OverviewRepository().loadDigest().getOrThrow()
        val summary = SummaryRepository().summarize("hackernews").getOrThrow()
        val weekly = WeeklyRepository().loadLatest().getOrThrow()

        // 数据全部来自旧路径(index latest_overview / 快照 ai_summary_v2 / weekly.json)
        assertTrue(overview.items.isNotEmpty())
        assertTrue((summary.content as SummaryContent.Structured).items.isNotEmpty())
        assertEquals(1, weekly.issueNo)

        // 404 不缓存:三路各自试探聚合包一次(overview / summary / weekly)
        assertEquals(3, dispatcher.attempts.count { it == "today.json" })
        assertTrue(dispatcher.hits.contains("index.json"))
        assertTrue(dispatcher.hits.contains("hackernews/2026-08-29/11-01-data.json"))
    }

    @Test
    fun `包内缺源键 该源单独回退快照路径`() = runBlocking {
        // today.json fixture 含 hackernews + github-trending;去掉 github-trending
        // 模拟「单源抓取失败批次流水线省略该键」
        val raw = javaClass.classLoader!!.getResourceAsStream("fixtures/today.json")
            .readBytes().toString(UTF_8)
        val org = org.json.JSONObject(raw)
        org.getJSONObject("sources").remove("github-trending")
        dispatcher.overrides["today.json"] = MockResponse().setResponseCode(200)
            .setBody(org.toString())

        val fromBundle = SummaryRepository().summarize("hackernews").getOrThrow()
        val fromSnapshot = SummaryRepository().summarize("github-trending").getOrThrow()

        // 前者零快照请求(包供给),后者回退 index + 快照
        assertTrue(fromBundle.fetchedAtMs == 1_787_972_486_201L)
        assertTrue((fromSnapshot.content as SummaryContent.Structured).items.isNotEmpty())
        assertTrue(dispatcher.hits.contains("github-trending/2026-08-29/11-01-data.json"))
        assertFalse(dispatcher.hits.contains("hackernews/2026-08-29/11-01-data.json"))
    }

    @Test
    fun `聚合包网络失败 三路如实失败不回退`() = runBlocking {
        // 5xx = 服务端故障:不得伪装离线,也不该再打旧路径重复失败
        dispatcher.overrides["today.json"] = MockResponse().setResponseCode(500)

        assertTrue(OverviewRepository().loadDigest().isFailure)
        assertTrue(SummaryRepository().summarize("hackernews").isFailure)
    }
}
