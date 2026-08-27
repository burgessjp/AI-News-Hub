package com.peng.ainewshub.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

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
     * @param longOutput 是否长输出场景(今日总览综合分析等,输出可达数千 token)。
     * true 用 [HttpClients.longRead](read 120s);false 用 [HttpClients.base](read 20s)。
     * 预建常驻 client 复用连接池,避免每次按超时值 newBuilder 建一次性 client。
     */
    suspend fun chat(
        config: AiConfig,
        system: String,
        user: String,
        temperature: Double = 0.3,
        longOutput: Boolean = false
    ): Result<ChatResult> = runCatching { request(config, system, user, temperature, longOutput) }
        // runCatching 会吞 CancellationException:取消须照常抛出(结构化取消),
        // 否则整页翻译取消时当批请求仍跑完才停
        .onFailure { if (it is CancellationException) throw it }

    private suspend fun request(
        config: AiConfig,
        system: String,
        user: String,
        temperature: Double,
        longOutput: Boolean
    ): ChatResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", config.model)
            put("temperature", temperature)
            // DeepSeek 推理模型(deepseek-v4-flash/pro)默认 thinking=high,
            // 单次翻译请求会因思维链耗时 60-100s 撞穿 20s readTimeout。
            // 全 provider 一律注入 thinking=disabled:对 DeepSeek 关闭推理链回到秒级;
            // GLM / 自建 OpenAI 兼容服务按规范忽略未知字段(实测无 400)。
            put("thinking", JSONObject().apply { put("type", "disabled") })
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

        // 长输出用预建 longRead(read 120s),其余用 base(read 20s),复用同一连接池。
        val callClient = if (longOutput) HttpClients.longRead else client
        callClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                // 401/403 单独归因鉴权失败,其余按服务故障;错误文案见 toUiError 的 error_ai_auth
                if (resp.code == 401 || resp.code == 403) throw AppException.AiAuth()
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
