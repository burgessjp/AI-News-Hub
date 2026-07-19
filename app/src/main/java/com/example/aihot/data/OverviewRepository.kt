package com.example.aihot.data

import android.content.Context
import com.example.aihot.data.source.ArchiveHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** AI 服务未配置(isReady=false)时抛出,UI 据此显示「去设置」引导态。 */
class AiConfigMissingException : RuntimeException("ai_config_missing")

/**
 * 今日总览单条——AI 只给 ref、一句话与 breaking 标记,标题/链接/指标
 * 由客户端按 ref 回源数据回填(AI 不抄 URL,省 token 且避免编造链接)。
 *
 * @param source 归档源 key(供 UI 取源名徽章)
 * @param title 原标题(来自快照,非 AI 输出)
 * @param url 点击落地页(内置 WebView)
 * @param metrics 互动指标行(如 "得分 512 · 评论 389");无指标的源为空串
 * @param comment AI 给出的一句话(为什么重要/值得关注什么)
 * @param breaking 是否「突发重磅」(多源交叉/数据爆发/重大发布;UI 特殊样式 + 标签)
 */
data class OverviewEntry(
    val source: String,
    val title: String,
    val url: String,
    val metrics: String,
    val comment: String,
    val breaking: Boolean
)

/**
 * 今日总览结果。
 *
 * @param items 今日热点 Top10(breaking 条目排在最前,全部条目共 ≤10 条)
 * @param generatedAt 本次生成时刻(毫秒)
 * @param dataFetchedAt 输入快照中最新的 fetched_at_ms(「数据截至」)
 * @param missingSources 本次未能加载的源 key(页脚标注)
 * @param fromCache 是否命中当日缓存(未新调 AI)
 */
data class OverviewDigest(
    val items: List<OverviewEntry>,
    val generatedAt: Long,
    val dataFetchedAt: Long,
    val missingSources: List<String>,
    val model: String,
    val totalTokens: Int,
    val fromCache: Boolean
)

/**
 * 今日总览 Repository —— 首个根 tab「总览」的数据源。
 *
 * 与 [SummaryRepository] 的关键差异:摘要是流水线预生成、App 只读;总览是**端侧实时
 * 调用**用户在「设置 → AI 服务」配置的服务([AiChatClient]),对 7 个归档源当日快照
 * 做跨源综合分析(今日热点 Top10,其中 AI 判定为突发重磅的条目带 breaking 标记)。
 *
 * 设计要点(复刻 [TranslationRepository] 范式):
 *  - 输入仅 7 个归档源快照(不接 aihot.virxact.com /hot-topics、不接 LinuxDo 实时):每源取
 *    `ai_summary` 作上下文 + items 前 [ITEMS_PER_SOURCE] 条的标题/简介/互动指标;
 *  - 缓存键 = 北京日期 + 7 源 latest 路径指纹(路径含日期+时间,数据更新即失效),
 *    命中缓存时**连快照都不拉**,一天正常只生成 1-2 次;缓存单槽覆盖,不留历史;
 *  - [Mutex] 全局串行防并发重复生成;成功后 token 用量写 [AiUsageStore];
 *  - 生成输出长(数千 token),read 超时放宽到 120s(见 [AiChatClient.chat]);
 *  - AI 只输出 `ref="源key:序号"` + 一句话 + breaking 标记,标题/URL/指标由客户端
 *    按 ref 回填;无效 ref 丢弃,breaking 截断到 [MAX_BREAKING]、列表截断到 [MAX_TOP]。
 */
class OverviewRepository(context: Context) {

    private val appContext = context.applicationContext
    private val cacheFile: File? = appContext.cacheDir?.let { File(it, CACHE_FILE) }
    private val chatClient = AiChatClient()
    private val configStore = AiConfigStore(appContext)
    private val usageStore = AiUsageStore(appContext)

    /** 全局串行:同一时刻只允许一次生成(手动刷新与自动加载互斥)。 */
    private val generateMutex = Mutex()

    /**
     * 加载今日总览。
     *
     * @param force true=忽略缓存强制重新生成(手动刷新);false=指纹命中即用缓存
     * @return 成功为 [OverviewDigest];失败为 [AiConfigMissingException](未配置)/
     * 归档数据不足 / AI 调用或输出解析失败
     */
    suspend fun loadDigest(force: Boolean = false): Result<OverviewDigest> = runCatching {
        val config = configStore.configFlow.first()
        if (!config.isReady) throw AiConfigMissingException()

        // 指纹只读 index.json 的 latest 指针(2 分钟缓存,不拉快照本体)
        val paths = ArchiveHttpClient.fetchLatestPaths()
        val key = cacheKey(paths)
        if (!force) {
            readCached(key)?.let { return@runCatching it }
        }
        generateMutex.withLock {
            // 二次查缓存(等锁期间可能已被并发生成写入)
            if (!force) {
                readCached(key)?.let { return@runCatching it }
            }
            generate(key, config)
        }
    }

    /** 完整生成:拉 7 源快照 → 组 prompt → 调 AI → 解析回填 → 写缓存。 */
    private suspend fun generate(key: String, config: AiConfig): OverviewDigest {
        // 1) 并发拉 7 源最新快照,单源失败不阻断
        val snapshots: Map<String, JSONObject> = coroutineScope {
            SummaryRepository.SOURCE_KEYS.map { source ->
                async { source to runCatching { ArchiveHttpClient.fetchLatestSnapshot(source) }.getOrNull() }
            }.awaitAll().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
        }
        if (snapshots.size < MIN_SOURCES) {
            throw RuntimeException("归档数据不足(仅 ${snapshots.size}/${SummaryRepository.SOURCE_KEYS.size} 源可用)")
        }

        // 2) 组 prompt 并调用(长输出,read 超时放宽;温度 0.5 对齐流水线摘要)
        val user = snapshots.entries.joinToString("\n\n") { (k, snap) -> buildSection(k, snap) }
        val result = chatClient.chat(config, SYSTEM_PROMPT, user, temperature = 0.5, readTimeoutSeconds = 120)
            .getOrThrow()
        runCatching { usageStore.record(config.model, result.promptTokens, result.completionTokens) }

        // 3) 解析 + ref 回填
        val items = parseResult(result.content, snapshots)
        val digest = OverviewDigest(
            items = items,
            generatedAt = System.currentTimeMillis(),
            dataFetchedAt = snapshots.values.maxOf { it.optLong("fetched_at_ms", 0L) },
            missingSources = SummaryRepository.SOURCE_KEYS - snapshots.keys,
            model = config.model,
            totalTokens = result.promptTokens + result.completionTokens,
            fromCache = false
        )
        writeCache(key, digest)
        return digest
    }

    // ===== prompt 组装 =====

    /** 单源输入段:标题行 + AI 要点(上下文)+ 编号条目(标题/简介/指标,不带 URL)。 */
    private fun buildSection(source: String, snapshot: JSONObject): String {
        val sb = StringBuilder()
        sb.append("## ").append(source).append("(").append(SummaryRepository.titleOf(source)).append(")")
        val aiSummary = snapshot.optString("ai_summary").trim()
        if (aiSummary.isNotEmpty()) sb.append("\nAI 要点:").append(aiSummary)
        sb.append("\n条目:")
        extractItems(source, snapshot).forEach { item ->
            sb.append("\n[").append(item.index).append("] ").append(item.title)
            if (item.blurb.isNotEmpty()) sb.append(" — ").append(item.blurb.take(60))
            if (item.metrics.isNotEmpty()) sb.append(" | ").append(item.metrics)
        }
        return sb.toString()
    }

    /** 输入条目的统一视图:index 即 prompt 里的序号(供 AI ref 引用)。 */
    private data class ItemView(
        val index: Int,
        val title: String,
        val url: String,
        val metrics: String,
        val blurb: String
    )

    /** 从快照 items 提取前 [limit] 条,字段映射对齐 docs/news-hub-data-usage.md 各源结构。 */
    private fun extractItems(source: String, snapshot: JSONObject, limit: Int = ITEMS_PER_SOURCE): List<ItemView> {
        val items = snapshot.optJSONArray("items") ?: return emptyList()
        val n = minOf(limit, items.length())
        return (0 until n).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val view = when (source) {
                "hackernews" -> ItemView(
                    i, o.optString("title"), o.optString("target_url"),
                    "得分 ${o.optInt("score")} · 评论 ${o.optInt("descendants")}", ""
                )
                "github-trending" -> ItemView(
                    i, "${o.optString("owner")}/${o.optString("name")}", o.optString("url"),
                    "今日 star +${o.optInt("starsToday")} · 累计 ${fmtCount(o.optInt("totalStars"))}",
                    o.optString("description")
                )
                "huggingface-papers" -> ItemView(
                    i, o.optString("title"), o.optString("url"),
                    "upvotes ${o.optInt("upvotes")}", o.optString("summary")
                )
                "producthunt" -> ItemView(
                    i, o.optString("name"), o.optString("url"),
                    buildString {
                        append("票 ${o.optInt("votesCount")} · 评论 ${o.optInt("commentsCount")}")
                        val r = o.optInt("dailyRank")
                        if (r > 0) append(" · 日榜#$r")
                    },
                    o.optString("tagline")
                )
                "rundown-ai" -> ItemView(
                    i, o.optString("title"), o.optString("url"),
                    "", o.optString("subtitle")
                )
                "stormzhang-ai" -> ItemView(
                    i, o.optString("summary"), o.optString("url"),
                    "信源 ${o.optString("source")}", o.optString("english")
                )
                "aihot-featured" -> ItemView(
                    i, o.optString("title"),
                    o.optString("permalink").ifBlank { o.optString("url") },
                    "权重 ${o.optInt("score")} · ${o.optString("source")}",
                    o.optString("summary")
                )
                else -> null
            }
            view?.takeIf { it.title.isNotBlank() }
        }
    }

    // ===== AI 输出解析 =====

    /**
     * 解析 AI 输出为统一的热点列表;items 为空视为解析失败抛异常。
     * 兜底策略(prompt 已要求但模型未必严格遵守):
     *  - breaking 标记截断到 [MAX_BREAKING] 条(超出的按提交顺序降级为普通条目);
     *  - 按落地 URL 去重(同一事件同链接跨源同现);
     *  - breaking 条目稳定排序到最前,整体截断到 [MAX_TOP]。
     */
    private fun parseResult(
        content: String,
        snapshots: Map<String, JSONObject>
    ): List<OverviewEntry> {
        val json = JSONObject(extractJson(content))
        val entries = parseEntries(json.optJSONArray("items"), snapshots)
        if (entries.isEmpty()) throw RuntimeException("AI 输出解析失败(items 为空)")
        var breakingLeft = MAX_BREAKING
        val urls = HashSet<String>()
        return entries.map { e ->
                if (e.breaking && breakingLeft > 0) { breakingLeft--; e } else e.copy(breaking = false)
            }
            .filter { urls.add(it.url) }
            .sortedByDescending { it.breaking }  // 稳定排序,不打乱同级重要性次序
            .take(MAX_TOP)
    }

    /**
     * 解析条目数组:按 ref 回源数据回填标题/URL/指标,丢弃无效 ref(源不存在、
     * 序号越界、重复)。breaking 标记直接采纳 AI 输出,截断在 [parseResult] 统一做。
     */
    private fun parseEntries(
        arr: JSONArray?,
        snapshots: Map<String, JSONObject>
    ): List<OverviewEntry> {
        if (arr == null) return emptyList()
        val seen = mutableSetOf<String>()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ref = o.optString("ref").trim()
            if (ref.isEmpty() || !seen.add(ref)) return@mapNotNull null
            val cut = ref.lastIndexOf(':')
            if (cut <= 0) return@mapNotNull null
            val source = ref.substring(0, cut).trim().lowercase()
            val index = ref.substring(cut + 1).trim().toIntOrNull() ?: return@mapNotNull null
            val snapshot = snapshots[source] ?: return@mapNotNull null
            // 序号必须落在 prompt 实际给出的前 ITEMS_PER_SOURCE 条内,防 AI 引用未提供的条目
            val item = extractItems(source, snapshot).getOrNull(index) ?: return@mapNotNull null
            if (item.url.isBlank()) return@mapNotNull null
            OverviewEntry(
                source = source,
                title = item.title,
                url = item.url,
                metrics = item.metrics,
                comment = o.optString("analysis").trim(),
                breaking = o.optBoolean("breaking")
            )
        }
    }

    /** 剥 markdown 围栏并截取首个 { 到末个 } —— 对模型的额外输出容错。 */
    private fun extractJson(content: String): String {
        val s = content.trim()
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) throw RuntimeException("AI 输出解析失败(非 JSON)")
        return s.substring(start, end + 1)
    }

    // ===== 缓存(cacheDir 单槽 JSON,指纹命中即用;写失败不影响结果) =====

    /** 缓存键 = 北京日期 + 7 源 latest 路径指纹(任源出新快照即变化)。 */
    private fun cacheKey(paths: Map<String, String>): String {
        val raw = SummaryRepository.SOURCE_KEYS.joinToString("|") { "$it=${paths[it].orEmpty()}" }
        val md = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return "${beijingToday()}-${md.joinToString("") { "%02x".format(it) }.take(16)}"
    }

    /** 北京日期(归档数据按北京时间落盘,「今日」对齐数据侧而非本机时区)。 */
    private fun beijingToday(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT+08:00")
        return fmt.format(Date())
    }

    /** 读缓存;仅当 cacheKey 匹配时返回(fromCache=true),否则 null。文件损坏或
     * 旧版结构(breaking/top10 双数组,无 items 字段)等同未命中,重新生成一次即可。 */
    private suspend fun readCached(key: String): OverviewDigest? = withContext(Dispatchers.IO) {
        val f = cacheFile ?: return@withContext null
        if (!f.exists()) return@withContext null
        val root = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return@withContext null
        if (root.optString("cacheKey") != key) return@withContext null
        runCatching {
            OverviewDigest(
                items = readEntries(root.optJSONArray("items")),
                generatedAt = root.optLong("generatedAt", 0L),
                dataFetchedAt = root.optLong("dataFetchedAt", 0L),
                missingSources = readStringList(root.optJSONArray("missingSources")),
                model = root.optString("model"),
                totalTokens = root.optInt("totalTokens", 0),
                fromCache = true
            ).takeIf { it.items.isNotEmpty() }
        }.getOrNull()
    }

    private suspend fun writeCache(key: String, digest: OverviewDigest) {
        val f = cacheFile ?: return
        val root = JSONObject()
            .put("cacheKey", key)
            .put("generatedAt", digest.generatedAt)
            .put("dataFetchedAt", digest.dataFetchedAt)
            .put("missingSources", JSONArray().apply { digest.missingSources.forEach { put(it) } })
            .put("model", digest.model)
            .put("totalTokens", digest.totalTokens)
            .put("items", writeEntries(digest.items))
        // 文件 IO 走 IO 线程;写失败吞掉,缓存写不进不影响生成结果
        withContext(Dispatchers.IO) { runCatching { f.writeText(root.toString()) } }
    }

    private fun readEntries(arr: JSONArray?): List<OverviewEntry> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            OverviewEntry(
                source = o.optString("source"),
                title = o.optString("title"),
                url = o.optString("url"),
                metrics = o.optString("metrics"),
                comment = o.optString("comment"),
                breaking = o.optBoolean("breaking")
            ).takeIf { it.title.isNotBlank() && it.url.isNotBlank() }
        }
    }

    private fun writeEntries(list: List<OverviewEntry>): JSONArray = JSONArray().apply {
        list.forEach { e ->
            put(
                JSONObject()
                    .put("source", e.source)
                    .put("title", e.title)
                    .put("url", e.url)
                    .put("metrics", e.metrics)
                    .put("comment", e.comment)
                    .put("breaking", e.breaking)
            )
        }
    }

    private fun readStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    private fun fmtCount(n: Int): String = "%,d".format(n)

    private companion object {
        const val CACHE_FILE = "overview_digest.json"

        /** 每源喂给 AI 的条目数(快照本身即按热度排序)。 */
        const val ITEMS_PER_SOURCE = 8

        /** 低于此源数不生成(数据太少,分析无意义)。 */
        const val MIN_SOURCES = 4

        /** 「突发重磅」标记上限(占 Top10 名额,不额外增加条数)。 */
        const val MAX_BREAKING = 3

        /** 热点列表总条数上限(breaking 与普通条目共计)。 */
        const val MAX_TOP = 10

        val SYSTEM_PROMPT = """
你是「AI News Hub」今日总览栏目的主编。输入是 7 个资讯源的今日榜单:每源附 AI 要点摘要,以及排名前若干条目(序号、标题、简介、互动指标)。请基于全部数据做当天整体研判。

严格输出一个 JSON 对象,不要输出任何解释文字,不要使用 markdown 代码围栏:
{"items":[{"ref":"源key:序号","analysis":"一句话,不超过40字","breaking":true}]}

规则:
1. items 是今日最值得关注的条目,按重要性排序,最多 10 条;尽量覆盖不同源与主题(模型发布/产品/研究论文/开源项目/行业动态),同一事件只留最重要的一条;数据不足 10 条时有多少给多少,至少 5 条。
2. 其中「突发重磅」标 "breaking":true:多源交叉报道、互动数据(得分/评论/票数/star)显著爆发、或重大发布与行业事件。0 到 3 条,宁缺毋滥,绝不硬凑;其余条目一律 "breaking":false。breaking 条目排在 items 最前,同样计入 10 条总数。
3. ref 必须原样照抄输入中的「源key:序号」(如 hackernews:2),不得编造;标题与链接由客户端按 ref 回填,你不要输出标题和 URL。
4. analysis 用简体中文,一句话说清「为什么重要/值得关注什么」,不要复述标题。
""".trimIndent()
    }
}
