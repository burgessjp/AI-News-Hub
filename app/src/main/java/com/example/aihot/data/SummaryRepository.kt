package com.example.aihot.data

import com.example.aihot.data.source.GitHubTrendingArchiveRepository
import com.example.aihot.data.source.HackerNewsArchiveRepository
import com.example.aihot.data.source.HuggingFacePapersArchiveRepository
import com.example.aihot.data.source.StormzhangAiNewsArchiveRepository
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
 * 单个归档源的 AI 摘要结果 —— 摘要正文 + 该源快照落盘时刻(用于 UI 标注数据新鲜度)。
 *
 * [fetchedAtMs] 直接取自归档快照的 `fetched_at_ms`(北京时间每日 08:00 附近),
 * 与「摘要生成时刻」区分:用户关心的是「这份数据是哪天的」,而非「AI 何时跑的」。
 */
data class SourceSummary(
    val text: String,
    val fetchedAtMs: Long
)

/**
 * 摘要缓存条目。key = `"<source>|<fetchedAtMs>|<model>"` 的 sha256 前 16 位。
 *
 * 设计语义:同一源的同一天快照(fetchedAtMs 不变)用同一模型生成的摘要内容稳定,
 * 可永久缓存命中即用(归档本身不变)。换模型 / 快照更新(第二天)→ key 变 → 重新生成。
 *
 * 结构与 [com.example.aihot.data.TranslationRepository] 的翻译缓存同范式,但独立成文件,
 * 避免与翻译缓存相互覆盖。
 */
private object SummaryCache {
    private const val FILE = "source_summaries.json"

    fun file(cacheDir: File?): File? = cacheDir?.let { File(it, FILE) }

    /** 读全量缓存为 mutableMap。文件缺失或损坏返回空 map(不阻塞生成)。 */
    fun read(cacheDir: File?): MutableMap<String, SourceSummary> {
        val f = file(cacheDir) ?: return mutableMapOf()
        if (!f.exists()) return mutableMapOf()
        val json = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return mutableMapOf()
        val map = mutableMapOf<String, SourceSummary>()
        json.keys().forEach { k ->
            val obj = json.optJSONObject(k) ?: return@forEach
            val text = obj.optString("text").takeIf { it.isNotBlank() } ?: return@forEach
            val fetchedAt = obj.optLong("fetched_at_ms", 0L)
            map[k] = SourceSummary(text, fetchedAt)
        }
        return map
    }

    /** 全量写回(append 后调用)。IO 异常用 runCatching 吞掉,缓存写失败不影响结果。 */
    fun write(cacheDir: File?, map: Map<String, SourceSummary>) {
        val dir = cacheDir ?: return
        if (!dir.exists()) dir.mkdirs()
        val root = JSONObject()
        map.forEach { (k, v) ->
            root.put(k, JSONObject().put("text", v.text).put("fetched_at_ms", v.fetchedAtMs))
        }
        runCatching { file(dir)?.writeText(root.toString()) }
    }
}

/** 某归档源拉取结果 —— 快照落盘时刻 + 压缩成 prompt 友好的用户输入文本。 */
private data class SourcePayload(val fetchedAtMs: Long, val userPrompt: String)

/**
 * 各归档源的元信息 —— 摘要卡片的标题、prompt 构造器。
 *
 * 每个源有独立的:
 *  - [title]:       卡片展示的中文名
 *  - [systemPrompt]: 针对该源内容形态定制的 AI 角色(技术编辑 / 开源观察者 等)
 *  - [load]:        拉取归档快照,返回 (fetchedAtMs + 压缩后的 user prompt)
 *                   取 Top N 控制 token,只拉一次网络(避免重复请求)
 *
 * Prompt 设计原则(针对反馈「总结质量差 / 英文输出」):
 *  - 开头三令五申「输出简体中文」,避免模型对英文输入(HN/Papers 标题)默认回英文;
 *  - 每条用「**标题**：简述」加粗小标题形式,信息密度高于一句流水;
 *  - 篇幅 6-10 条,每条 2-3 句,既不寡淡也不冗长;
 *  - 专有名词 / 项目名 / 公司名 / 技术术语保留原文,只在必要时括注中文;
 *  - 末尾禁止「以上是…」「希望对你有帮助」等套话。
 */
private enum class SummarySource(
    val key: String,
    val title: String,
    val systemPrompt: String,
    val load: suspend () -> SourcePayload
) {
    HACKERNEWS(
        key = "hackernews",
        title = "HackerNews",
        systemPrompt = HACKERNEWS_PROMPT,
        load = {
            val result = HackerNewsArchiveRepository().forceRefresh()
            val body = result.stories.take(15).joinToString("\n") { s ->
                "• ${s.title.trim()}（得分 ${s.score}，评论 ${s.descendants}）"
            }
            SourcePayload(result.fetchedAt, "以下是今日 HackerNews 热门（按得分排序）：\n$body")
        }
    ),

    GITHUB_TRENDING(
        key = "github-trending",
        title = "GitHub Trending",
        systemPrompt = GITHUB_PROMPT,
        load = {
            val result = GitHubTrendingArchiveRepository().forceRefresh()
            val body = result.repos.take(10).joinToString("\n") { r ->
                val desc = r.description.trim().ifBlank { "（无描述）" }
                "• ${r.owner}/${r.name}（今日 +${r.starsToday}★，共 ${r.totalStars}★，${r.language.ifBlank { "未知语言" }}）：$desc"
            }
            SourcePayload(result.fetchedAt, "以下是今日 GitHub Trending（按今日新增 star 排序）：\n$body")
        }
    ),

    HUGGINGFACE_PAPERS(
        key = "huggingface-papers",
        title = "HuggingFace Papers",
        systemPrompt = PAPERS_PROMPT,
        load = {
            val result = HuggingFacePapersArchiveRepository().forceRefresh()
            val body = result.papers.take(10).joinToString("\n") { p ->
                val sum = p.summary.trim().ifBlank { "（无摘要）" }
                "• ${p.title.trim()}（↑${p.upvotes}）：$sum"
            }
            SourcePayload(result.fetchedAt, "以下是今日 HuggingFace 热门论文（按 upvote 排序）：\n$body")
        }
    ),

    STORMZHANG_AI(
        key = "stormzhang-ai",
        title = "stormzhang AI 资讯",
        systemPrompt = STORMZHANG_PROMPT,
        load = {
            val result = StormzhangAiNewsArchiveRepository().forceRefresh()
            val body = result.news.take(15).joinToString("\n") { n ->
                val src = n.source.trim().ifBlank { "未知来源" }
                "• [${src}] ${n.summary.trim()}"
            }
            SourcePayload(result.fetchedAt, "以下是今日聚合的 AI 资讯（含多个信源）：\n$body")
        }
    );

    companion object {
        /** 按归档源的 key 反查枚举;未知 key 返回 null。 */
        fun fromKey(key: String): SummarySource? = entries.firstOrNull { it.key == key }
    }
}

// ===== 各源 system prompt(独立成常量,便于阅读与迭代) =====
//
// 通用骨架:每条用「**加粗标题**：2-3 句简述」;专有名词保留原文;输出简体中文;
// 6-10 条;禁套话。具体到各源再定制侧重点。

/** HackerNews —— 技术动态与开发者社区讨论。侧重「发生了什么 + 为什么值得关注」。 */
private val HACKERNEWS_PROMPT = """
你是一位资深技术编辑与 HackerNews 社区观察者。请把用户提供的 HackerNews 当日热门条目，整理成一份高质量的中文技术简报。

【语言要求】必须输出简体中文。即使输入标题是英文，正文也用中文表达；项目名、公司名、技术术语、人名等专有名词保留原文，不要音译。

【输出格式】6 到 10 条要点，每条格式如下：
• **一句话概括标题**：用 2-3 句中文说明这件事是什么、为什么值得关注或开发者反应如何。

【内容要求】
- 按得分热度排序，重要的放前面；
- 抓住技术本质（新发布 / 漏洞 / 工具 / 行业观点），不要照抄标题；
- 合并同一事件的多条讨论；
- 高分且评论多的条目适当多写。

【禁止】不要输出英文；不要「以上是…」「希望对你有帮助」等套话；不要额外解释你做了什么；不要输出引号或前后缀。直接给出要点列表。
""".trimIndent()

/** GitHub Trending —— 开源新热点。侧重「这是什么 + 解决什么问题 + 热度信号」。 */
private val GITHUB_PROMPT = """
你是一位开源生态观察者。请把用户提供的 GitHub Trending 当日热门仓库，整理成一份中文开源动态简报。

【语言要求】必须输出简体中文。仓库 owner/name、技术名词保留原文，不要翻译。

【输出格式】6 到 10 条，每条格式如下：
• **owner/name（一句话价值定位）**：用 2-3 句中文说明这个项目解决什么问题、适用场景，以及今日新增 star 反映的热度趋势。

【内容要求】
- 结合描述和语言推断项目价值，不要只复述描述；
- 今日新增 star 多的排前面；
- 同类项目可合并成一条并对比。

【禁止】不要输出英文正文；不要套话；直接给出要点列表。
""".trimIndent()

/** HuggingFace Papers —— AI 研究前沿。侧重「研究什么 + 方法亮点 + 意义」。 */
private val PAPERS_PROMPT = """
你是一位 AI 研究前沿解读员。请把用户提供的 HuggingFace Trending Papers，整理成一份中文论文速读简报。

【语言要求】必须输出简体中文。论文标题先给中文意译，括号内附英文原标题；模型名、方法名、数据集名等专有名词保留原文。

【输出格式】6 到 10 条，每条格式如下：
• **中文标题（English Title，↑upvote）**：用 2-3 句中文说明这篇论文研究什么问题、方法亮点、可能的影响。

【内容要求】
- upvote 高的排前面；
- 避免堆砌术语，用普通开发者能懂的话解释；
- 同一方向的论文可合并对比。

【禁止】不要输出全英文；不要逐字翻译摘要；不要套话；直接给出要点列表。
""".trimIndent()

/** stormzhang-ai —— 多源聚合 AI 行业资讯。侧重去重归纳成清晰的事件清单。 */
private val STORMZHANG_PROMPT = """
你是一位 AI 行业资讯编辑。用户提供的已是中文 AI 资讯摘要（来自 Hacker News / Reddit / Product Hunt / The Rundown AI / TLDR AI 等多个信源），请重新归纳成一份结构清晰的中文要点清单。

【语言要求】输出简体中文。

【输出格式】6 到 10 条，每条格式如下：
• **事件标题**：用 2-3 句说明核心事实，并在末尾标注信源（如「（来源：Reddit）」）。

【内容要求】
- 按主题去重合并：同一事件的多条合成一条，保留最完整的信息；
- 突出产品发布、融资、模型更新、政策等硬事实；
- 按重要性排序。

【禁止】不要照抄原文；不要套话；直接给出要点列表。
""".trimIndent()

/**
 * AI 摘要 Repository —— 调用用户配置的 OpenAI 兼容服务,把各归档源当日数据汇总成中文要点。
 *
 * 设计要点(复刻项目既有范式):
 *  - 数据始终取自 gitcode 归档([ArchiveHttpClient] 每日 08:00 快照),与全局 [com.example.aihot.data.source.SourceMode] 无关
 *    —— 归档数据稳定、代表「今日」,适合做每日摘要;实时源波动大、用户可直接看列表。
 *  - 复用用户的翻译服务配置([TranslationConfigStore]):baseUrl/apiKey/model,不另开设置项。
 *  - 缓存 key = `<source>|<fetchedAtMs>|<model>` 的 sha256 前 16 位:同一天快照 + 同模型 → 命中秒回;
 *    快照更新(第二天)/ 换模型 → key 变 → 重新生成。永久缓存,无 TTL(归档不变)。
 *  - [Mutex] 按 key 串行化,防同一摘要被并发点两次(同 [TranslationRepository] 的 locks 套路)。
 *
 * 与 [TranslationRepository] 独立:system prompt 不同(摘要 vs 翻译)、温度更高(0.5 vs 0.3)、
 * 缓存 key 维度不同(带日期)。独立成类不碰已稳定的翻译链路。
 */
class SummaryRepository(
    private val cacheDir: File?
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS) // 摘要比翻译长,放宽读取超时
        .followRedirects(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** key 粒度的并发锁:同一摘要正在生成时,后来的请求等待复用结果。 */
    private val locks = mutableMapOf<String, Mutex>()
    private val locksGuard = Mutex()

    private suspend fun lockFor(key: String): Mutex = locksGuard.withLock {
        locks.getOrPut(key) { Mutex() }
    }

    /**
     * 生成某归档源的当日 AI 摘要。返回 [Result]:成功为 [SourceSummary](含正文与快照时刻),
     * 失败为异常(配置缺失 / 归档拉取失败 / HTTP 错误 / 网络)。
     *
     * 流程:
     *  1. 拉归档快照拿 (fetchedAtMs + user prompt) —— 单次网络,取 Top N 控制 token
     *  2. 算缓存 key = `<source>|<fetchedAtMs>|<model>`,命中秒回
     *  3. 未命中则按 key 加锁 → 二次查缓存 → 打 API → 写缓存
     *
     * @param source 归档源 key(hackernews / github-trending / huggingface-papers / stormzhang-ai)
     * @param config 用户配置(必须 [TranslationConfig.isReady],由调用方保证)
     */
    suspend fun summarize(source: String, config: TranslationConfig): Result<SourceSummary> {
        val src = SummarySource.fromKey(source)
            ?: return Result.failure(IllegalArgumentException("未知源:$source"))
        if (!config.isReady) {
            return Result.failure(IllegalStateException(CONFIG_MISSING))
        }

        // 1) 拉归档数据(fetchedAtMs + user prompt 单次拿到)
        val payload = runCatching { src.load() }.getOrElse {
            return Result.failure(RuntimeException("归档数据拉取失败:${it.message}"))
        }

        val cacheKey = sha256Short("${src.key}|${payload.fetchedAtMs}|${config.model}|v$PROMPT_VERSION")

        // 2) 命中缓存直接返回
        val cached = withContext(Dispatchers.IO) { SummaryCache.read(cacheDir)[cacheKey] }
        if (cached != null) return Result.success(cached)

        // 3) 同一 key 串行,避免并发重复请求
        val mutex = lockFor(cacheKey)
        val got = mutex.withLock {
            // 二次查缓存(可能在等锁期间已被别的请求写入了)
            val cached2 = withContext(Dispatchers.IO) { SummaryCache.read(cacheDir)[cacheKey] }
            if (cached2 != null) return@withLock Result.success(cached2)

            runCatching {
                val text = requestSummary(src.systemPrompt, payload.userPrompt, config)
                SourceSummary(text = text, fetchedAtMs = payload.fetchedAtMs)
            }.onSuccess { summary ->
                // 写缓存:读最新全量 → put → 写回,避免覆盖并发写入的其他 key
                val map = withContext(Dispatchers.IO) { SummaryCache.read(cacheDir) }
                map[cacheKey] = summary
                withContext(Dispatchers.IO) { SummaryCache.write(cacheDir, map) }
            }
        }
        return got
    }

    /** 发起一次 OpenAI 兼容 `/v1/chat/completions` 请求,返回摘要正文。 */
    private suspend fun requestSummary(
        systemPrompt: String,
        userPrompt: String,
        config: TranslationConfig
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", config.model)
            put("temperature", 0.5) // 摘要比翻译略发散,允许更自然的归纳
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
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

    companion object {
        /**
         * Prompt 版本号 —— 纳入缓存 key。改 prompt 后递增此值,旧缓存自动失效、强制重新生成。
         * 避免用户改了服务配置也看不到新 prompt 效果(归档 fetchedAtMs + model 不变时旧 key 会命中旧摘要)。
         */
        private const val PROMPT_VERSION = 2

        /** 配置未就绪的特殊错误标识,UI 据此引导去设置(对齐 [TranslationState.CONFIG_MISSING])。 */
        const val CONFIG_MISSING = "config_missing"

        /** 4 个支持的归档源 key,供 ViewModel / UI 遍历。 */
        val SOURCE_KEYS = listOf(
            SummarySource.HACKERNEWS.key,
            SummarySource.GITHUB_TRENDING.key,
            SummarySource.HUGGINGFACE_PAPERS.key,
            SummarySource.STORMZHANG_AI.key
        )

        /** 源 key → 展示标题。 */
        fun titleOf(source: String): String =
            SummarySource.fromKey(source)?.title ?: source
    }
}
