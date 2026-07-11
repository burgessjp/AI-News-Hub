package com.example.aihot.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 翻译缓存条目。仅存译文 —— 原文不可变,以原文纯文本的 sha256 为 key,
 * 命中即用,永不过期。结构与 [com.example.aihot.data.HackerNewsStoriesCache] 同范式。
 *
 * 序列化为 `cacheDir/hn_translations.json`:`{ "<sha256前16>": { "text": "..." } }`。
 */
private object TranslationCache {
    private const val FILE = "hn_translations.json"

    fun file(cacheDir: File?): File? = cacheDir?.let { File(it, FILE) }

    /** 读全量缓存为 mutableMap。文件缺失或损坏返回空 map(不阻塞翻译)。 */
    fun read(cacheDir: File?): MutableMap<String, String> {
        val f = file(cacheDir) ?: return mutableMapOf()
        if (!f.exists()) return mutableMapOf()
        val json = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return mutableMapOf()
        val map = mutableMapOf<String, String>()
        json.keys().forEach { k ->
            val text = json.optJSONObject(k)?.optString("text")?.takeIf { it.isNotBlank() }
            if (text != null) map[k] = text
        }
        return map
    }

    /** 全量写回(append 后调用)。IO 异常用 runCatching 吞掉,缓存写失败不影响翻译结果。 */
    fun write(cacheDir: File?, map: Map<String, String>) {
        val dir = cacheDir ?: return
        if (!dir.exists()) dir.mkdirs()
        val root = JSONObject()
        map.forEach { (k, v) -> root.put(k, JSONObject().put("text", v)) }
        runCatching { file(dir)?.writeText(root.toString()) }
    }
}

/** 翻译内容过短(纯链接/纯代码/无字母),不值得调 API。 */
class ShortContentException : RuntimeException("content_too_short")

/**
 * 翻译 Repository:调用用户配置的 OpenAI 兼容服务,带持久化缓存。
 *
 * 设计要点(复刻项目既有范式):
 *  - 自带 [OkHttpClient](同 [NewsRepository] 的超时配置);
 *  - 缓存存 `cacheDir/hn_translations.json`,key=原文纯文本 sha256 前 16 位;
 *  - [Mutex] 按 key 串行化,防同一条内容被并发点两次;
 *  - 短内容(<5 字符或无字母)跳过,返回 [ShortContentException];
 *  - 目标语言固定简体中文(见 system prompt)。
 */
class TranslationRepository(
    private val cacheDir: File?
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** key 粒度的并发锁:同一条内容正在翻译时,后来的请求等待复用结果。 */
    private val locks = mutableMapOf<String, Mutex>()
    private val locksGuard = Mutex()

    private suspend fun lockFor(key: String): Mutex = locksGuard.withLock {
        locks.getOrPut(key) { Mutex() }
    }

    /**
     * 翻译一段文本。返回 [Result]:成功为译文,失败为异常([ShortContentException] /
     * HTTP 错误 / 解析失败 / 网络)。
     *
     * @param text 原文(评论为 HTML,标题为纯文本);内部会先 [HtmlUtil.stripHtml]
     * @param config 用户配置(必须 [TranslationConfig.isReady],由调用方保证)
     */
    suspend fun translate(text: String, config: TranslationConfig): Result<String> {
        val plain = HtmlUtil.stripHtml(text)
        // 短内容/无字母(纯链接、纯符号)不浪费 token
        if (plain.length < 5 || plain.none { it.isLetter() }) {
            return Result.failure(ShortContentException())
        }
        val key = sha256Short(plain)

        // 命中缓存直接返回
        val cached = withContext(Dispatchers.IO) { TranslationCache.read(cacheDir)[key] }
        if (cached != null) return Result.success(cached)

        // 同一 key 串行,避免并发重复请求
        val mutex = lockFor(key)
        val got = mutex.withLock {
            // 二次查缓存(可能在等锁期间已被别的请求写入了)
            val cached2 = withContext(Dispatchers.IO) { TranslationCache.read(cacheDir)[key] }
            if (cached2 != null) return@withLock Result.success(cached2)

            runCatching { requestTranslation(plain, config) }
                .onSuccess { translated ->
                    // 写缓存:读最新全量 → put → 写回,避免覆盖并发写入的其他 key
                    val map = withContext(Dispatchers.IO) { TranslationCache.read(cacheDir) }
                    map[key] = translated
                    withContext(Dispatchers.IO) { TranslationCache.write(cacheDir, map) }
                }
        }
        return got
    }

    /** 发起一次 OpenAI 兼容 `/v1/chat/completions` 请求,返回译文文本。 */
    private suspend fun requestTranslation(plain: String, config: TranslationConfig): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("model", config.model)
                put("temperature", 0.3)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put(
                            "content",
                            "You are a translator. Translate the user's text into Simplified Chinese. " +
                                "Preserve technical terms, code, and proper nouns in their original form. " +
                                "Output ONLY the translation, no explanations, no quotes."
                        )
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", plain)
                    })
                })
            }

            val req = Request.Builder()
                .url("${config.baseUrl.trimEnd('/')}/v1/chat/completions")
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMedia))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    throw RuntimeException("HTTP ${resp.code}${errBody.take(120).let { ": $it" }}")
                }
                val root = JSONObject(resp.body?.string().orEmpty())
                root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?.optString("content")?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: throw RuntimeException("响应解析失败")
            }
        }

    private fun sha256Short(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
}
