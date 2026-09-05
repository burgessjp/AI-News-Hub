package com.peng.ainewshub.data.repo

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import com.peng.ainewshub.data.HtmlUtil
import com.peng.ainewshub.data.net.AiChatClient
import com.peng.ainewshub.data.prefs.AiConfig
import com.peng.ainewshub.data.prefs.AiUsageStore

/** 翻译缓存文件名 —— [com.peng.ainewshub.data.CacheManager] 的清理排除名单引用此处,防漂移。 */
internal const val TRANSLATION_CACHE_FILE = "hn_translations.json"

/**
 * 翻译缓存条目。仅存译文 —— 原文不可变,以原文纯文本的 sha256 为 key,
 * 命中即用,永不过期。结构与 [com.peng.ainewshub.data.HackerNewsStoriesCache] 同范式。
 *
 * 序列化为 `cacheDir/hn_translations.json`:`{ "<sha256前16>": { "text": "..." } }`。
 */
private object TranslationCache {
    private const val FILE = TRANSLATION_CACHE_FILE

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
 * **进程级单例**(经 [get] 访问):内存缓存副本与 per-key 锁表必须全 App 共享 ——
 * 多实例各自持内存副本时,同一文本跨实例会重复调 AI 浪费 token。
 *
 * 设计要点(复刻项目既有范式):
 *  - 网络统一走 [AiChatClient](OpenAI 兼容,超时同 [NewsRepository]);
 *  - 缓存存 `cacheDir/hn_translations.json`,key=原文纯文本 sha256 前 16 位;
 *    进程内持内存副本(懒加载一次),文件级 Mutex 串行化读-改-写,上限 1000 条按 LRU(访问序)淘汰;
 *  - 落盘走「延迟合并」:单条翻译只更新内存副本,短窗口内的连续翻译合并为一次
 *    全量写(替代每条即写,收敛写放大);批量翻译(deferPersist)结束后由调用方 flush;
 *  - per-key [Mutex] 串行化网络请求,防同一条内容被并发点两次,完成后即回收;
 *  - 短内容(<5 字符或无字母)跳过,返回 [ShortContentException];
 *  - HTML 剥离与 sha256 在 [Dispatchers.Default] 执行(长评论 HTML 解析不占主线程);
 *  - 目标语言固定简体中文(见 system prompt);
 *  - 请求成功后把 token 用量写入 [AiUsageStore](缓存命中不发请求,自然不统计)。
 */
class TranslationRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val cacheDir: File? = appContext.cacheDir
    private val chatClient = AiChatClient()
    private val usageStore = AiUsageStore(appContext)

    /** key 粒度的并发锁:同一条内容正在翻译时,后来的请求等待复用结果。 */
    private val locks = mutableMapOf<String, Mutex>()
    private val locksGuard = Mutex()

    /**
     * 缓存的文件级锁 + 进程内副本。
     * per-key Mutex 只锁同 key,锁不住跨 key 的「全量读→put→全量写」竞态
     * (整页翻译多 key 并发时后写覆盖先写);故读-改-写全程走 [cacheMutex] 一把锁,
     * 内存副本懒加载一次,之后读写不再每次全量读盘。
     */
    private val cacheMutex = Mutex()
    private var memCache: LinkedHashMap<String, String>? = null

    /** 内存副本是否有未落盘变更([cacheMutex] 守护)。 */
    private var dirty = false

    /** 延迟落盘任务:单例随进程存活,SupervisorJob 保证一次落盘失败不拖垮后续。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null

    private suspend fun lockFor(key: String): Mutex = locksGuard.withLock {
        locks.getOrPut(key) { Mutex() }
    }

    /** [cacheMutex] 锁内调用:懒加载进程内缓存(仅首次读盘)。 */
    private suspend fun cacheLocked(): LinkedHashMap<String, String> {
        memCache?.let { return it }
        val loaded = withContext(Dispatchers.IO) { TranslationCache.read(cacheDir) }
        // accessOrder=true:命中即重排,淘汰循环按「最久未用」踢出 —— 高频词条
        // 不因插入早而败给刚译过的冷词,被挤出后重译白烧 token
        val map = LinkedHashMap<String, String>(16, 0.75f, true)
        map.putAll(loaded)
        memCache = map
        return map
    }

    /**
     * [cacheMutex] 锁内调用:标记脏数据并调度延迟落盘。已有等待中的任务则不另起
     * (任务到点写的是整个内存副本,天然覆盖窗口内的后续变更)。
     */
    private fun scheduleFlushLocked() {
        dirty = true
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            runCatching { flushCache() }
        }
    }

    /** [cacheMutex] 锁内调用:超出上限按 LRU(访问序)淘汰最久未用(只动内存,落盘由 flush 统一)。 */
    private suspend fun updateMemOnlyLocked(key: String, value: String) {
        val map = cacheLocked()
        map[key] = value
        // 淘汰仍需在内存做,否则 memCache 可能突破上限
        while (map.size > MAX_CACHE_ENTRIES) {
            val eldest = map.entries.firstOrNull()?.key ?: break
            map.remove(eldest)
        }
    }

    /** 把内存缓存落盘(批量翻译结束后/延迟窗口到点调用);无未落盘变更则空操作。 */
    private suspend fun flushCache() {
        cacheMutex.withLock {
            if (!dirty) return@withLock
            val map = memCache ?: return@withLock
            withContext(Dispatchers.IO) { TranslationCache.write(cacheDir, map) }
            dirty = false
        }
    }

    /**
     * 翻译一段文本。返回 [Result]:成功为译文,失败为异常([ShortContentException] /
     * HTTP 错误 / 解析失败 / 网络)。
     *
     * @param text 原文(评论为 HTML,标题为纯文本);内部会先 [HtmlUtil.stripHtml]
     * @param config 用户的 AI 服务配置(必须 [AiConfig.isReady],由调用方保证)
     * @param deferPersist true 时翻译成功只更新内存副本、不调度落盘(批量翻译场景,
     *                     由调用方在批次结束时调 [flush] 统一落盘);默认 false
     *                     (单条场景:更新内存 + 延迟合并落盘,窗口内进程被杀会丢这批
     *                     未落盘缓存,代价是重译一次,换取不做每条全量重写)
     */
    suspend fun translate(text: String, config: AiConfig, deferPersist: Boolean = false): Result<String> {
        // HTML 剥离 + sha256 挪到 Default:translate 由 UI 层主线程调用,
        // 长评论(几十 KB HTML)全量解析不应占主线程
        val plain: String
        val key: String
        withContext(Dispatchers.Default) {
            plain = HtmlUtil.stripHtml(text)
            key = sha256Short(plain)
        }
        // 短内容/无字母(纯链接、纯符号)不浪费 token
        if (plain.length < 5 || plain.none { it.isLetter() }) {
            return Result.failure(ShortContentException())
        }

        // 命中缓存直接返回(内存副本,不再每次读盘)
        cacheMutex.withLock { cacheLocked()[key] }?.let { return Result.success(it) }

        // 同一 key 串行,避免并发重复请求
        val mutex = lockFor(key)
        val got = mutex.withLock {
            // 二次查缓存(可能在等锁期间已被别的请求写入了)
            cacheMutex.withLock { cacheLocked()[key] }?.let { return@withLock Result.success(it) }

            chatClient.chat(config, SYSTEM_PROMPT, plain)
                .onSuccess { result ->
                    // 统计 token 用量(响应无 usage 时 record 内部跳过)
                    runCatching {
                        usageStore.record(config.model, result.promptTokens, result.completionTokens)
                    }
                    // 写缓存:文件级锁内「改内存副本 → 淘汰」;批量场景(deferPersist)
                    // 完全不触发落盘,单条场景延迟合并落盘(见 scheduleFlushLocked)
                    cacheMutex.withLock {
                        updateMemOnlyLocked(key, result.content)
                        if (!deferPersist) scheduleFlushLocked()
                    }
                }
                .map { it.content }
        }
        // 回收 per-key 锁,防长会话 locks 表无界增长:仅在锁空闲时移除 —— 若有等待者
        // 已拿到引用,等待者仍会正常获锁并命中锁内二次缓存检查,不受影响
        if (mutex.tryLock()) {
            mutex.unlock()
            locksGuard.withLock { if (locks[key] === mutex) locks.remove(key) }
        }
        return got
    }

    /**
     * 把内存缓存落盘 —— 供批量翻译([translate] 传 deferPersist=true)结束后调用,
     * 确保这批译文持久化(否则进程被杀会丢失);无变更时为空操作。
     */
    suspend fun flush() = flushCache()

    private fun sha256Short(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    companion object {
        /** 缓存条目上限:超出按 LRU(访问序)淘汰最久未用,防文件无界增长。 */
        private const val MAX_CACHE_ENTRIES = 1000

        /** 单条翻译的延迟合并落盘窗口:窗口内连续翻译合并为一次全量写。 */
        private const val PERSIST_DEBOUNCE_MS = 3_000L

        private const val SYSTEM_PROMPT =
            "You are a translator. Translate the user's text into Simplified Chinese. " +
                "Preserve technical terms, code, and proper nouns in their original form. " +
                "Output ONLY the translation, no explanations, no quotes."

        @Volatile
        private var instance: TranslationRepository? = null

        /** 进程级单例访问(见类注释:内存缓存与锁表必须全 App 共享)。 */
        fun get(context: Context): TranslationRepository =
            instance ?: synchronized(this) {
                instance ?: TranslationRepository(context.applicationContext).also { instance = it }
            }
    }
}
