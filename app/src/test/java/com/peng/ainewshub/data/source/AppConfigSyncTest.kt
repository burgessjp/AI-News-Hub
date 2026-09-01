package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.PipelineSchedule
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Calendar

/**
 * [AppConfigSync] 远程配置同步回归(MockWebServer + 内联 JSON 样例)。
 *
 * 覆盖两组契约:
 *  - parseBatchSlots 纯函数:格式/范围校验整体拒绝、兼容单位数、保留原始顺序;
 *  - refresh 端到端:成功应用并按新表推算下一批、404/非法表保持默认、
 *    断网读盘兜底沿用上次成功配置(write-through)。
 */
@RunWith(RobolectricTestRunner::class)
class AppConfigSyncTest {

    /** gitcode REST 路径前缀(与被测端点拼法一致,见 ArchiveHttpClientTest)。 */
    private val pathPrefix = "/api/v5/repos/peng1818/AI-News-Hub-Data/raw/"

    /** 断网模拟:指向必然连接拒绝的本地端口,传输层失败即 IOException(与真实断网同语义)。 */
    private val deadBaseUrl = "http://127.0.1:1${pathPrefix.trimEnd('/')}"

    /** 覆写型 dispatcher:默认 404(app_config.json 尚未创建的暂态),逐用例放内容。 */
    private inner class ConfigDispatcher : Dispatcher() {
        val overrides = mutableMapOf<String, MockResponse>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val rel = (request.path ?: "").substringAfter(pathPrefix).substringBefore("?")
            return overrides[rel] ?: MockResponse().setResponseCode(404)
        }
    }

    private lateinit var server: MockWebServer
    private lateinit var dispatcher: ConfigDispatcher

    @Before
    fun setUp() {
        PipelineSchedule.resetForTest()
        server = MockWebServer()
        dispatcher = ConfigDispatcher()
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

    private fun configResponse(body: String): MockResponse =
        MockResponse().setResponseCode(200).setBody(body)

    /** 构造北京时间某年月日时分(秒/毫秒归零)的 epoch 毫秒。 */
    private fun beijing(h: Int, m: Int, day: Int = 29, month: Int = Calendar.AUGUST, year: Int = 2026): Long =
        Calendar.getInstance(PipelineSchedule.BEIJING).apply {
            set(year, month, day, h, m, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // ===== parseBatchSlots 纯函数契约 =====

    @Test
    fun `parseBatchSlots 解析合法时刻表`() {
        val json = JSONObject("""{"batch_slots": ["08:00", "18:00"]}""")
        assertEquals(listOf(8 to 0, 18 to 0), AppConfigSync.parseBatchSlots(json))
    }

    @Test
    fun `parseBatchSlots 兼容单位数小时与分钟`() {
        val json = JSONObject("""{"batch_slots": ["9:5", "18:00"]}""")
        assertEquals(listOf(9 to 5, 18 to 0), AppConfigSync.parseBatchSlots(json))
    }

    @Test
    fun `parseBatchSlots 保留原始顺序不去重`() {
        // 去重与排序是 PipelineSchedule.applyBatchSlots 的归一职责,解析层只忠实返回
        val json = JSONObject("""{"batch_slots": ["22:00", "08:00", "22:00"]}""")
        assertEquals(listOf(22 to 0, 8 to 0, 22 to 0), AppConfigSync.parseBatchSlots(json))
    }

    @Test
    fun `parseBatchSlots 缺失字段或空数组返回 null`() {
        assertNull(AppConfigSync.parseBatchSlots(JSONObject("""{"foo": 1}""")))
        assertNull(AppConfigSync.parseBatchSlots(JSONObject("""{"batch_slots": []}""")))
    }

    @Test
    fun `parseBatchSlots 任一条目非法整体拒绝`() {
        assertNull(AppConfigSync.parseBatchSlots(JSONObject("""{"batch_slots": ["24:00"]}""")))
        assertNull(AppConfigSync.parseBatchSlots(JSONObject("""{"batch_slots": ["8:60"]}""")))
        assertNull(AppConfigSync.parseBatchSlots(JSONObject("""{"batch_slots": ["08:00", "abc"]}""")))
        assertNull(AppConfigSync.parseBatchSlots(JSONObject("""{"batch_slots": ["8点"]}""")))
    }

    // ===== refresh 端到端(MockWebServer) =====

    @Test
    fun `refresh 应用远程配置并按新表计算下一批`() = runBlocking {
        dispatcher.overrides["app_config.json"] =
            configResponse("""{"batch_slots": ["12:00", "06:30"]}""")
        assertTrue(AppConfigSync.refresh())
        // 去重排序归一后生效
        assertEquals(listOf(6 to 30, 12 to 0), PipelineSchedule.batchSlots)
        assertEquals(beijing(6, 30), PipelineSchedule.nextBatchEpoch(beijing(5, 0)))
        // 恰好到达 12:00 视为已过该批,今日无后续 → 明天第一批 6:30
        assertEquals(beijing(6, 30, day = 30), PipelineSchedule.nextBatchEpoch(beijing(12, 0)))
    }

    @Test
    fun `文件缺失 404 返回 false 保持默认表`() = runBlocking {
        // dispatcher 默认 404:app_config.json 尚未创建的正常暂态
        assertFalse(AppConfigSync.refresh())
        assertEquals(PipelineSchedule.DEFAULT_BATCH_SLOTS, PipelineSchedule.batchSlots)
    }

    @Test
    fun `非法时刻表被拒绝保持默认表`() = runBlocking {
        dispatcher.overrides["app_config.json"] =
            configResponse("""{"batch_slots": ["25:00", "08:00"]}""")
        assertFalse(AppConfigSync.refresh())
        assertEquals(PipelineSchedule.DEFAULT_BATCH_SLOTS, PipelineSchedule.batchSlots)
    }

    @Test
    fun `断网时读盘兜底沿用上次成功配置`() = runBlocking {
        dispatcher.overrides["app_config.json"] = configResponse("""{"batch_slots": ["09:00"]}""")
        assertTrue(AppConfigSync.refresh()) // 网络成功,write-through 落盘
        ArchiveHttpClient.reconfigureForTest(deadBaseUrl) // 断网(连接拒绝 = IOException)
        // 传输层失败 → 读盘兜底返回上次成功配置;值未变化 → false,生效表不变
        assertFalse(AppConfigSync.refresh())
        assertEquals(listOf(9 to 0), PipelineSchedule.batchSlots)
    }
}
