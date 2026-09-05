package com.peng.ainewshub.data.net

import com.peng.ainewshub.data.AppException
import com.peng.ainewshub.data.prefs.AiConfig
import com.peng.ainewshub.data.prefs.AiProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [AiChatClient.chat] 成功路径与错误分类回归(纯 JVM + MockWebServer,对齐
 * UpdateDownloaderTest 惯例;URL 取自 [AiConfig.effectiveBaseUrl],把 baseUrl
 * 指向 MockWebServer 即可,无需注入改造)。
 *
 * 钉住「设置 → AI 服务 → 测试连接」依赖的契约:
 *  1. 200 + 合法 choices → 成功,正文/usage 解析正确(请求头鉴权、路径拼接);
 *  2. 401/403 → AppException.AiAuth(测试连接据此提示检查 Key);
 *  3. 500 / 空 content → AppException.AiService(不把服务端故障伪装成鉴权问题)。
 */
class AiChatClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config() = AiConfig(
        provider = AiProvider.CUSTOM,
        baseUrl = server.url("/v1").toString(),
        apiKey = "sk-test",
        model = "test-model"
    )

    @Test
    fun `成功响应解析正文与用量且请求带鉴权头`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":"pong"}}],
                    "usage":{"prompt_tokens":5,"completion_tokens":2}}"""
            )
        )

        val result = AiChatClient().chat(config(), "You are a connectivity probe.", "ping", temperature = 0.0)

        assertTrue(result.isSuccess)
        result.onSuccess { r ->
            assertEquals("pong", r.content)
            assertEquals(5, r.promptTokens)
            assertEquals(2, r.completionTokens)
        }
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
    }

    @Test
    fun `401 归因为鉴权失败`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = AiChatClient().chat(config(), "s", "u")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AppException.AiAuth)
    }

    @Test
    fun `500 与空 content 均归因为服务故障`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"content":"  "}}]}""")
        )

        val first = AiChatClient().chat(config(), "s", "u")
        assertTrue(first.exceptionOrNull() is AppException.AiService)

        val second = AiChatClient().chat(config(), "s", "u")
        assertTrue(second.exceptionOrNull() is AppException.AiService)
    }
}
