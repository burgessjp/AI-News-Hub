package com.peng.ainewshub.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 翻译缓存条目。仅存译文 —— 原文不可变,以原文纯文本的 sha256 为 key,
 * 命中即用,永不过期。结构与 [com.peng.ainewshub.data.HackerNewsStoriesCache] 同范式。
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
 * 翻译 Repository:经 [AiChatClient] 调用用户在「设置 → AI 服务」配置的服务,带持久化缓存。
 *
 * 设计要点(复刻项目既有范式):
 *  - 网络统一走 [AiChatClient](OpenAI 兼容,超时同 [NewsRepository]);
 *  - 缓存存 `cacheDir/hn_translations.json`,key=原文纯文本 sha256 前 16 位;
 *  - [Mutex] 按 key 串行化,防同一条内容被并发点两次;
 *  - 短内容(<5 字符或无字母)跳过,返回 [ShortContentException];
 *  - 目标语言固定简体中文(见 system prompt);
 *  - 请求成功后把 token 用量写入 [AiUsageStore](缓存命中不发请求,自然不统计)。
 */
class TranslationRepository(context: Context) {

    private val appContext = context.applicationContext
    private val cacheDir: File? = appContext.cacheDir
    private val chatClient = AiChatClient()
    private val usageStore = AiUsageStore(appContext)

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
     * @param config 用户的 AI 服务配置(必须 [AiConfig.isReady],由调用方保证)
     */
    suspend fun translate(text: String, config: AiConfig): Result<String> {
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

            chatClient.chat(config, SYSTEM_PROMPT, plain)
                .onSuccess { result ->
                    // 统计 token 用量(响应无 usage 时 record 内部跳过)
                    runCatching {
                        usageStore.record(config.model, result.promptTokens, result.completionTokens)
                    }
                    // 写缓存:读最新全量 → put → 写回,避免覆盖并发写入的其他 key
                    val map = withContext(Dispatchers.IO) { TranslationCache.read(cacheDir) }
                    map[key] = result.content
                    withContext(Dispatchers.IO) { TranslationCache.write(cacheDir, map) }
                }
                .map { it.content }
        }
        return got
    }

    private fun sha256Short(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "You are a translator. Translate the user's text into Simplified Chinese. " +
                "Preserve technical terms, code, and proper nouns in their original form. " +
                "Output ONLY the translation, no explanations, no quotes."
    }
}
