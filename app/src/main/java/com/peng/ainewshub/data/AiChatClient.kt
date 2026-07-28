package com.peng.ainewshub.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容 chat 调用统一出口 —— App 内所有端侧 AI 功能(翻译及后续新功能)
 * 都经此访问用户在「设置 → AI 服务」里配置的服务([AiConfig]),不各自实现 HTTP。
 *
 * URL 约定:[AiConfig.effectiveBaseUrl](含版本段,如 `https://api.deepseek.com/v1`)
 * 拼 `/chat/completions`;鉴权 `Authorization: Bearer <apiKey>`。
 *
 * 超时配置与 [NewsRepository] 一致;JSON 用内置 org.json。
 */
class AiChatClient {

    /** 一次调用的结果:正文 + token 用量(响应缺 usage 时两侧为 0,调用方跳过统计)。 */
    data class ChatResult(
        val content: String,
        val promptTokens: Int,
        val completionTokens: Int
    )

    private val client = HttpClients.base

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * 发起一次 chat completions 请求。
     *
     * @param config 用户配置(必须 [AiConfig.isReady],由调用方保证)
     * @param system system prompt
     * @param user 用户输入文本
     * @param temperature 采样温度,默认 0.3(翻译等确定性场景)
     * @param readTimeoutSeconds 本次请求的 read 超时(秒),默认跟随 client 的 20s;
     * 长输出场景(如今日总览的综合分析,输出可达数千 token)由调用方显式放宽。
     */
    suspend fun chat(
        config: AiConfig,
        system: String,
        user: String,
        temperature: Double = 0.3,
        readTimeoutSeconds: Long = 20
    ): Result<ChatResult> = runCatching { request(config, system, user, temperature, readTimeoutSeconds) }
        // runCatching 会吞 CancellationException:取消须照常抛出(结构化取消),
        // 否则整页翻译取消时当批请求仍跑完才停
        .onFailure { if (it is CancellationException) throw it }

    private suspend fun request(
        config: AiConfig,
        system: String,
        user: String,
        temperature: Double,
        readTimeoutSeconds: Long
    ): ChatResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", config.model)
            put("temperature", temperature)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", system)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", user)
                })
            })
        }

        val req = Request.Builder()
            .url("${config.effectiveBaseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        val callClient = if (readTimeoutSeconds == 20L) client
        else client.newBuilder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build()
        callClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw AppException.AiService()
            }
            val root = JSONObject(resp.body?.string().orEmpty())
            val content = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: throw AppException.AiService()
            val usage = root.optJSONObject("usage")
            ChatResult(
                content = content,
                promptTokens = usage?.optInt("prompt_tokens") ?: 0,
                completionTokens = usage?.optInt("completion_tokens") ?: 0
            )
        }
    }
}
