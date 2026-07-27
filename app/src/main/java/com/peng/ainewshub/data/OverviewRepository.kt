package com.peng.ainewshub.data

import android.content.Context
import com.peng.ainewshub.data.source.ArchiveHttpClient
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
 * @param breakingReason Breaking 专属「推荐理由」(为什么是突发/影响面有多大),
 *   仅 breaking=true 时有;普通条目为空串。与 [comment] 语义区分:comment=重要性,
 *   breakingReason=突发性
 */
data class OverviewEntry(
    val source: String,
    val title: String,
    val url: String,
    val metrics: String,
    val comment: String,
    val breaking: Boolean,
    val breakingReason: String = ""
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
 * 调用**用户在「设置 → AI 服务」配置的服务([AiChatClient]),对全部归档源当日快照
 * 做跨源综合分析(今日热点 Top10,其中 AI 判定为突发重磅的条目带 breaking 标记)。
 *
 * 设计要点(复刻 [TranslationRepository] 范式):
 *  - 输入为 [SummaryRepository.SOURCE_KEYS] 全部归档源快照(不接 aihot.virxact.com /hot-topics、
 *    不接 LinuxDo 实时):每源取 `ai_summary` 作上下文 + items 前 [ITEMS_PER_SOURCE] 条的
 *    标题/简介/归一化热度(跨源可比);
 *  - 缓存键 = 北京日期 + 全源 latest 路径指纹(路径含日期+时间,数据更新即失效),
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

    /** 完整生成:拉全源快照 → 组 prompt → 调 AI → 解析回填 → 写缓存。 */
    private suspend fun generate(key: String, config: AiConfig): OverviewDigest {
        // 1) 并发拉全源最新快照,单源失败不阻断
        val snapshots: Map<String, JSONObject> = coroutineScope {
            SummaryRepository.SOURCE_KEYS.map { source ->
                async { source to runCatching { ArchiveHttpClient.fetchLatestSnapshot(source) }.getOrNull() }
            }.awaitAll().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
        }
        if (snapshots.size < MIN_SOURCES) {
            throw AppException.NoData()
        }

        // 「今天」以**数据侧日期**为准:取全源快照里最新的 fetched_at_ms 对应的北京日期。
        // 这样在数据流水线尚未跑当天的清晨,不会因本机日期超前于数据日期而产不出任何 breaking。
        val dataDateMs = snapshots.values.maxOf { it.optLong("fetched_at_ms", 0L) }
        val dataToday = beijingDateKeyOfMs(dataDateMs)

        // 2) 组 prompt 并调用(长输出,read 超时放宽;温度 0.3:排序是确定性任务,低温减少抖动)
        val user = buildString {
            append("数据日期(北京):").append(dataToday).append("\n\n")
            append(snapshots.entries.joinToString("\n\n") { (k, snap) -> buildSection(k, snap) })
        }
        val result = chatClient.chat(config, SYSTEM_PROMPT, user, temperature = 0.3, readTimeoutSeconds = 120)
            .getOrThrow()
        runCatching { usageStore.record(config.model, result.promptTokens, result.completionTokens) }

        // 3) 解析 + ref 回填
        val items = parseResult(result.content, snapshots, dataToday)
        val digest = OverviewDigest(
            items = items,
            generatedAt = System.currentTimeMillis(),
            dataFetchedAt = dataDateMs,
            missingSources = SummaryRepository.SOURCE_KEYS - snapshots.keys,
            model = config.model,
            totalTokens = result.promptTokens + result.completionTokens,
            fromCache = false
        )
        writeCache(key, digest)
        return digest
    }

    // ===== prompt 组装 =====

    /** 单源输入段:标题行 + AI 要点(上下文)+ 编号条目(标题/简介/原始指标/跨源热度档位/发布日期,不带 URL)。 */
    private fun buildSection(source: String, snapshot: JSONObject): String {
        val sb = StringBuilder()
        sb.append("## ").append(source).append("(").append(SummaryRepository.titleOf(source)).append(")")
        val aiSummary = readAiSummary(snapshot)
        if (aiSummary.isNotEmpty()) sb.append("\nAI 要点:").append(aiSummary)
        sb.append("\n条目:")
        extractItems(source, snapshot).forEach { item ->
            sb.append("\n[").append(item.index).append("] ").append(item.title)
            if (item.blurb.isNotEmpty()) sb.append(" — ").append(item.blurb.take(60))
            // 跨源可比的归一化热度档位(各源按自身 top 归一化):AI 据此横向比较强度,
            // 不被各源原始指标量级差异(HN 几百 / GitHub 几万 / PH 几百)误导
            sb.append(" | 热度 ").append(item.heatPct).append("%")
            if (item.metrics.isNotEmpty()) sb.append(" | ").append(item.metrics)
            // 日期(北京 MM-dd)给 AI 判断「今天」用,时效兜底在 parseEntries 客户端二次校验
            if (item.dateKey.length >= 10) sb.append(" | ").append(item.dateKey.substring(5))
        }
        return sb.toString()
    }

    /**
     * 读取快照顶层的 AI 要点并拍平成纯文本供 prompt 上下文用:
     * - 优先 `ai_summary_v2`(JSON 数组),把每项 title + desc 拼成「title：desc」分号串;
     * - 回退 `ai_summary`(旧纯文本,仅历史快照)。
     * 都缺失返回空串。
     */
    private fun readAiSummary(snapshot: JSONObject): String {
        val v2 = snapshot.optJSONArray("ai_summary_v2")
        if (v2 != null && v2.length() > 0) {
            val parts = (0 until v2.length()).mapNotNull { i ->
                val obj = v2.optJSONObject(i) ?: return@mapNotNull null
                val title = obj.optString("title").trim()
                val desc = obj.optString("desc").trim()
                if (title.isNotEmpty() && desc.isNotEmpty()) "$title：$desc" else null
            }
            if (parts.isNotEmpty()) return parts.joinToString("；")
        }
        return snapshot.optString("ai_summary").trim()
    }

    /** 输入条目的统一视图:index 即 prompt 里的序号(供 AI ref 引用)。
     *  [dateKey] 为条目的北京日期(yyyy-MM-dd),用于「只推今天的 breaking」时效兜底;
     *  无 item 级日期的源用快照顶层 fetched_at_ms 近似,均失败则为空串(按非今天处理)。
     *  [heatPct] 跨源可比的归一化热度(0-100):各源按自身 top 条目的最大原始热度
     *  归一化(stormzhang / OpenAI×Anthropic / Rundown 等无指标源按列表内排名线性递减),
     *  让 AI 在跨源排序时有一致的强度信号,而不是对比量级悬殊的原始数字。 */
    private data class ItemView(
        val index: Int,
        val title: String,
        val url: String,
        val metrics: String,
        val blurb: String,
        val dateKey: String,
        val heatPct: Int
    )

    /** 从快照 items 提取前 [limit] 条,字段映射对齐 docs/news-hub-data-usage.md 各源结构。
     *  热度归一化:每源原始热度按该源 top-[limit] 内的最大值算百分比;无指标源
     *  按列表序号线性递减(首位 100,末位 ≈10)。这样 AI 跨源比较的是相对档位而非绝对量级。 */
    private fun extractItems(source: String, snapshot: JSONObject, limit: Int = ITEMS_PER_SOURCE): List<ItemView> {
        val items = snapshot.optJSONArray("items") ?: return emptyList()
        val n = minOf(limit, items.length())
        // 无 item 级日期的源用快照顶层落盘时刻近似(北京时间日期)
        val fallbackDateKey = beijingDateKeyOfMs(snapshot.optLong("fetched_at_ms", 0L))
        // 第一遍:抽取原始字段 + 原始热度(各源定义不同)。null 表示该条应丢弃。
        data class Raw(val view: ItemView, val rawHeat: Double)
        val raws: List<Raw> = (0 until n).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            // 每分支产出 Pair<ItemView, Double> 或 null(未知源 / 字段全空)
            val pair: Pair<ItemView, Double>? = when (source) {
                "hackernews" -> ItemView(
                    i, o.optString("title"), o.optString("target_url"),
                    "得分 ${o.optInt("score")} · 评论 ${o.optInt("descendants")}", "",
                    beijingDateKeyOfMs(o.optLong("time", 0L) * 1000L), 0
                ) to rawHeatHackerNews(o)
                "github-trending" -> ItemView(
                    i, "${o.optString("owner")}/${o.optString("name")}", o.optString("url"),
                    "今日 star +${o.optInt("starsToday")} · 累计 ${fmtCount(o.optInt("totalStars"))}",
                    o.optString("description"), fallbackDateKey, 0
                ) to rawHeatGithub(o)
                "huggingface-papers" -> ItemView(
                    i, o.optString("title"), o.optString("url"),
                    "upvotes ${o.optInt("upvotes")}", o.optString("summary"),
                    beijingDateKeyOfEnDate(o.optString("published")), 0
                ) to o.optInt("upvotes", 0).toDouble()
                "producthunt" -> ItemView(
                    i, o.optString("name"), o.optString("url"),
                    buildString {
                        append("票 ${o.optInt("votesCount")} · 评论 ${o.optInt("commentsCount")}")
                        val r = o.optInt("dailyRank")
                        if (r > 0) append(" · 日榜#$r")
                    },
                    o.optString("tagline"),
                    beijingDateKeyOfIso(o.optString("createdAt")), 0
                ) to rawHeatProductHunt(o)
                "rundown-ai" -> ItemView(
                    i, o.optString("title"), o.optString("url"),
                    "", o.optString("subtitle"), fallbackDateKey, 0
                ) to 0.0  // 无指标源,按序号归一化
                "stormzhang-ai" -> ItemView(
                    i, o.optString("summary"), o.optString("url"),
                    "信源 ${o.optString("source")}", o.optString("english"),
                    // "2026-07-15 20:00" 北京时间无时区,直接取前 10 字符(yyyy-MM-dd)
                    o.optString("time").trim().takeIf { it.length >= 10 }?.substring(0, 10) ?: "",
                    0
                ) to 0.0
                "aihot-featured" -> ItemView(
                    i, o.optString("title"),
                    o.optString("permalink").ifBlank { o.optString("url") },
                    "权重 ${o.optInt("score")} · ${o.optString("source")}",
                    o.optString("summary"),
                    beijingDateKeyOfIso(o.optString("publishedAt")), 0
                ) to o.optInt("score", 0).toDouble()
                "openai-anthropic-news" -> ItemView(
                    i, o.optString("title"), o.optString("url"),
                    "厂商 ${o.optString("vendor")} · ${o.optString("category")}",
                    o.optString("summary"),
                    beijingDateKeyOfIso(o.optString("publishedAt")), 0
                ) to 0.0
                else -> null
            }
            val (view, rawHeat) = pair ?: return@mapNotNull null
            // 标题空的条目丢弃(与原实现一致)
            if (view.title.isBlank()) return@mapNotNull null
            Raw(view, rawHeat)
        }
        if (raws.isEmpty()) return emptyList()
        // 第二遍:计算归一化热度
        val maxRaw = raws.maxOf { it.rawHeat }
        return raws.mapIndexed { pos, raw ->
            val pct = when {
                // 有指标的源:按该源最大原始热度归一化(最少 10%,避免末位被当成零热度)
                maxRaw > 0 -> ((raw.rawHeat / maxRaw) * 100).toInt().coerceIn(10, 100)
                // 无指标源:按列表序号线性递减(top1=100, topN≈10)
                else -> if (raws.size == 1) 100 else (100 - pos * 90.0 / (raws.size - 1)).toInt().coerceIn(10, 100)
            }
            raw.view.copy(heatPct = pct)
        }
    }

    /** HN 综合热度:得分 + 评论数 * 0.3(评论密度反映讨论热度,适当加权但不盖过得分)。 */
    private fun rawHeatHackerNews(o: JSONObject): Double =
        o.optInt("score", 0) + o.optInt("descendants", 0) * 0.3

    /** GitHub 综合热度:今日新增 star * 3 + 累计 star 对数权重(今日增量是趋势主信号)。 */
    private fun rawHeatGithub(o: JSONObject): Double {
        val today = o.optInt("starsToday", 0)
        val total = o.optInt("totalStars", 0)
        return today * 3.0 + if (total > 0) Math.log10(total.toDouble()) * 10 else 0.0
    }

    /** Product Hunt 综合热度:票数 + 评论 * 0.5 + 日榜加成(日榜前 5 显著加权)。 */
    private fun rawHeatProductHunt(o: JSONObject): Double {
        val votes = o.optInt("votesCount", 0)
        val comments = o.optInt("commentsCount", 0)
        val rank = o.optInt("dailyRank", 0)
        val rankBoost = if (rank in 1..5) (6 - rank) * 30.0 else 0.0
        return votes + comments * 0.5 + rankBoost
    }

    /** Unix 毫秒 → 北京日期(yyyy-MM-dd);0 或负数返回空串。 */
    private fun beijingDateKeyOfMs(epochMs: Long): String {
        if (epochMs <= 0L) return ""
        return runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT+08:00")
            fmt.format(Date(epochMs))
        }.getOrDefault("")
    }

    /** ISO UTC 字符串(如 2026-07-18T07:01:00Z)→ 北京日期;解析失败返回空串。 */
    private fun beijingDateKeyOfIso(iso: String): String {
        val s = iso.trim()
        if (s.isEmpty()) return ""
        return runCatching {
            java.time.Instant.parse(s).atZone(java.time.ZoneOffset.UTC)
                .withZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
                .toLocalDate().toString()
        }.getOrDefault("")
    }

    /** 英文月份格式日期(如 "Jul 8, 2026")→ 北京日期;解析失败返回空串。 */
    private fun beijingDateKeyOfEnDate(text: String): String {
        val s = text.trim()
        if (s.isEmpty()) return ""
        return runCatching {
            val fmt = SimpleDateFormat("MMM d, yyyy", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT+08:00")
            val ms = fmt.parse(s)?.time ?: return@runCatching ""
            val out = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            out.timeZone = TimeZone.getTimeZone("GMT+08:00")
            out.format(Date(ms))
        }.getOrDefault("")
    }

    // ===== AI 输出解析 =====

    /**
     * 解析 AI 输出为统一的热点列表;items 为空视为解析失败抛异常。
     * 兜底策略(prompt 已要求但模型未必严格遵守):
     *  - **时效硬约束**:breaking 条目的北京日期必须等于数据日期 [today],非当日的
     *    强制降级为普通条目(防流水线失败保留机制把过期快照推成突发);
     *  - breaking 标记截断到 [MAX_BREAKING] 条(超出的按提交顺序降级为普通条目);
     *  - **双层去重**:先按落地 URL(同链接跨源同现),再按标题 token 相似度
     *    (Jaccard ≥ [TITLE_DUP_THRESHOLD],防同一事件跨源标题不同但描述一致);
     *  - breaking 条目稳定排序到最前,整体截断到 [MAX_TOP]。
     *
     * @param today 数据日期(全源快照里最新 fetched_at_ms 的北京日期,非本机当天)
     */
    private fun parseResult(
        content: String,
        snapshots: Map<String, JSONObject>,
        today: String
    ): List<OverviewEntry> {
        val json = JSONObject(extractJson(content))
        val entries = parseEntries(json.optJSONArray("items"), snapshots, today)
        if (entries.isEmpty()) throw AppException.AiService()
        var breakingLeft = MAX_BREAKING
        val urls = HashSet<String>()
        val seenTitleTokens = mutableListOf<Set<String>>()  // 标题去重历史(用于跨条目相似度比对)
        return entries.map { e ->
                if (e.breaking && breakingLeft > 0) { breakingLeft--; e } else e.copy(breaking = false, breakingReason = "")
            }
            .filter { urls.add(it.url) }  // 第一层:URL 去重
            .filter { titleNotDuplicate(it.title, seenTitleTokens) }  // 第二层:标题相似度去重
            .sortedByDescending { it.breaking }  // 稳定排序,不打乱同级重要性次序
            .take(MAX_TOP)
    }

    /**
     * 标题去重:把标题切成 token 集合(中英文混排,按非字母数字分割),与历史标题
     * 集合逐一算 Jaccard 相似度,≥ [TITLE_DUP_THRESHOLD] 视为重复丢弃;否则记入历史。
     */
    private fun titleNotDuplicate(title: String, history: MutableList<Set<String>>): Boolean {
        val tokens = tokenizeTitle(title)
        if (tokens.size < 3) {
            // 过短标题无比较意义(如 "GPT-5" 这种),直接放行但仍记录
            history.add(tokens)
            return true
        }
        val dup = history.any { prev -> jaccard(tokens, prev) >= TITLE_DUP_THRESHOLD }
        if (dup) return false
        history.add(tokens)
        return true
    }

    /** 标题切 token:按非字母数字分段,统一小写,过滤 1 字符噪声(如单独的 "-" / "·")。 */
    private fun tokenizeTitle(title: String): Set<String> =
        title.lowercase().split(Regex("[^a-z0-9\\u4e00-\\u9fa5]+"))
            .filter { it.length >= 2 }
            .toSet()

    /** Jaccard 相似度:交集 / 并集。空集返回 0。 */
    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    /**
     * 解析条目数组:按 ref 回源数据回填标题/URL/指标,丢弃无效 ref(源不存在、
     * 序号越界、重复)。breaking 标记先经**时效兜底**(非 [today] 强制 false),再由
     * [parseResult] 统一截断到 [MAX_BREAKING]。
     *
     * @param today 数据日期(yyyy-MM-dd),用于 breaking 时效校验
     */
    private fun parseEntries(
        arr: JSONArray?,
        snapshots: Map<String, JSONObject>,
        today: String
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
            // 时效硬约束:AI 标 breaking 但日期不是今天的,强制降级(防过期数据上突发位)。
            // dateKey 为空的源(stormzhang time 异常 / Rundown 无 item 级日期)回退到快照
            // 顶层 fetched_at_ms 对应的北京日期作为兜底,避免今日热点因日期解析失败永不上突发位。
            val aiBreaking = o.optBoolean("breaking")
            val effectiveDate = if (item.dateKey.isNotEmpty()) item.dateKey
                else beijingDateKeyOfMs(snapshot.optLong("fetched_at_ms", 0L))
            val isBreaking = aiBreaking && effectiveDate == today && effectiveDate.isNotEmpty()
            OverviewEntry(
                source = source,
                title = item.title,
                url = item.url,
                metrics = item.metrics,
                comment = o.optString("analysis").trim(),
                breaking = isBreaking,
                breakingReason = if (isBreaking) o.optString("breakingReason").trim() else ""
            )
        }
    }

    /** 剥 markdown 围栏并截取首个 { 到末个 } —— 对模型的额外输出容错。 */
    private fun extractJson(content: String): String {
        val s = content.trim()
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) throw AppException.AiService()
        return s.substring(start, end + 1)
    }

    // ===== 缓存(cacheDir 单槽 JSON,指纹命中即用;写失败不影响结果) =====

    /** 缓存键 = 北京日期 + 全源 latest 路径指纹(任源出新快照即变化)。 */
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
                breaking = o.optBoolean("breaking"),
                breakingReason = o.optString("breakingReason")
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
                    .put("breakingReason", e.breakingReason)
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
        const val MAX_BREAKING = 2

        /** 热点列表总条数上限(breaking 与普通条目共计)。 */
        const val MAX_TOP = 10

        /** 标题 Jaccard 相似度去重阈值:≥ 此值视为同一事件跨源重复,丢弃。 */
        const val TITLE_DUP_THRESHOLD = 0.5

        val SYSTEM_PROMPT = """
你是「AI News Hub」今日总览栏目的主编。输入是多个资讯源的今日榜单:每源附 AI 要点摘要,以及排名前若干条目(序号、标题、简介、**跨源归一化热度档位**、原始指标、北京日期 MM-DD)。请基于全部数据做当天整体研判。

严格输出一个 JSON 对象,不要输出任何解释文字,不要使用 markdown 代码围栏:
{"items":[{"ref":"源key:序号","analysis":"一句话,不超过40字","breaking":true,"breakingReason":"为什么是突发,40字内"}]}

规则:
1. items 是今日最值得关注的条目,**严格按热度档位(热度 NN%)从高到低排序**,最多 10 条(数据不足时按实际给,至少 5 条)。热度档位已跨源归一化,**直接按档位数字排序即可,不要主观调整顺序**——档位 92% 的条目必须排在档位 85% 的前面,即使后者主题看起来更重要。
2. **归一化热度档位**是核心排序信号:每条带「热度 NN%」,反映该条在本源的相对强度(同源内档位越高越热)。跨源比较时:
   - 有指标的源(HN/GitHub/HF/PH/AIHot)档位可信,直接按数字比;
   - **无指标源(rundown-ai / stormzhang-ai / openai-anthropic-news)的档位仅按列表序号给**,可信度低于有指标源——同等档位下,有指标源的条目优先于无指标源;
   - 原始指标量级差异极大(HN 几百 / GitHub 几万),**禁止直接比较原始数字**。
3. 其中「突发重磅」标 "breaking":true:多源交叉报道、热度档位 ≥85% 且远超同源其它条目、或重大发布与行业事件。0 到 2 条,宁缺毋滥,绝不硬凑;其余条目一律 "breaking":false。breaking 条目排在 items 最前,同样计入 10 条总数。**时效硬约束**:输入顶部已给出「数据日期(北京)」,仅条目末尾的北京日期等于该值的条目才可标 breaking;过期条目即便热度爆发也一律 false(客户端会二次校验日期,不符会强制降级,且不补 breakingReason)。
4. **跨源同事件合并**:同一事件(如某新模型发布)在多个源出现时,只保留热度档位最高的一条;通过 ref 引用其中之一即可,analysis 里可点出「多家报道」。不要让同一事件占据多个名额。
5. ref 必须原样照抄输入中的「源key:序号」(如 hackernews:2),不得编造;标题与链接由客户端按 ref 回填,你不要输出标题和 URL。
6. analysis 用简体中文,一句话说清「为什么重要/值得关注什么」,不要复述标题。
7. breaking=true 的条目必须给出 breakingReason,简体中文,一句话说明「为什么是突发/影响面有多大」,≤40 字,不复述 analysis;breaking=false 时 breakingReason 留空字符串。
""".trimIndent()
    }
}
