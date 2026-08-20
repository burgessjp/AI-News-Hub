package com.peng.ainewshub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 「我的关注」语料单条 —— 总览 Top10 条目与各源结构化摘要条目的统一形态:
 * - 总览条目:[desc] 为 AI 一句话点评(comment),额外带 [metrics] 与 [breaking];
 * - 摘要条目:[desc] 为该条摘要正文,无 metrics。
 *
 * [url] 为空串的条目(历史快照 / ref 无效)只读不可点,与摘要 Tab 行为一致。
 */
data class FollowCorpusEntry(
    val source: String,
    val title: String,
    val desc: String,
    val url: String,
    val metrics: String = "",
    val fromOverview: Boolean,
    val breaking: Boolean = false
)

/**
 * 「我的关注」语料 —— 当日全量候选条目(未过滤)。
 *
 * [entries] 顺序即展示顺序:总览条目在前(流水线已把 breaking 排最前),
 * 摘要条目按用户自定义源顺序跟后;同 URL 去重保留总览版本(信息更全)。
 * [missingSources] 为加载失败的源 key(总览失败记 [FollowsRepository.OVERVIEW_KEY]),
 * UI 页脚标注;[dataFetchedAt] 取各输入快照 fetched_at 的最大值(「数据截至」)。
 */
data class FollowCorpus(
    val entries: List<FollowCorpusEntry>,
    val missingSources: List<String>,
    val dataFetchedAt: Long
)

/** 过滤结果条目 = 语料条目 + 命中的关注词(原样大小写,展示标签用)。 */
data class FollowFeedItem(
    val entry: FollowCorpusEntry,
    val matchedKeywords: List<String>
)

/**
 * 「我的关注」Repository —— 聚合当日总览 Top10 与 8 个归档源的结构化摘要,
 * 作为关键词过滤的语料。纯端上:数据全部来自既有归档(ArchiveHttpClient 的
 * index.json 2 分钟缓存 + 快照缓存自动复用,摘要 Tab / 总览 Tab 打开过即预热),
 * 不发额外请求、不依赖 AI 服务配置。
 *
 * 并发范式同摘要 Tab(SummaryViewModel):总览 + 8 源并行拉取,单源失败记入
 * [FollowCorpus.missingSources] 跳过、不拖累其余;仅当总览与 8 源**全部**失败时
 * 才返回 failure(UI 走错误态)。[SummaryRepository.summarize] 自带的本地搜索
 * 索引回填副作用在此同样生效 —— 关注页也是索引覆盖的日常入口之一。
 */
class FollowsRepository {

    /**
     * 加载当日语料(总览 Top10 + 8 源结构化摘要,同 URL 去重)。
     *
     * @param force true 绕过 index.json 2 分钟缓存(关注页下拉刷新)
     * @param sourceOrder 摘要条目的排序源顺序(用户自定义,见 SettingsStore.sourceOrderFlow;
     *   未知 key 容错忽略)
     */
    suspend fun loadCorpus(
        force: Boolean = false,
        sourceOrder: List<String>
    ): Result<FollowCorpus> = runCatching {
        coroutineScope {
            val overviewDeferred = async { overviewRepo.loadDigest(force) }
            val summaryDeferred = SummaryRepository.SOURCE_KEYS.associateWith { key ->
                async { summaryRepo.summarize(key, force) }
            }
            val overview = overviewDeferred.await()
            val summaries = summaryDeferred.mapValues { it.value.await() }
            buildCorpus(overview, summaries, sourceOrder)
        }
    }

    /** 拼装语料:IO 切换 + 去重 + 排序都在这里,便于保持 loadCorpus 的并发结构清爽。 */
    private suspend fun buildCorpus(
        overview: Result<OverviewDigest>,
        summaries: Map<String, Result<SourceSummary>>,
        sourceOrder: List<String>
    ): FollowCorpus = withContext(Dispatchers.IO) {
        val missing = mutableListOf<String>()
        val entries = mutableListOf<FollowCorpusEntry>()
        var fetchedAt = 0L

        // 总览 Top10 在前(流水线已按 breaking 优先排序);失败记缺失
        overview.onSuccess { digest ->
            fetchedAt = maxOf(fetchedAt, digest.dataFetchedAt)
            digest.items.forEach { item ->
                entries += FollowCorpusEntry(
                    source = item.source,
                    title = item.title,
                    desc = item.comment,
                    url = item.url,
                    metrics = item.metrics,
                    fromOverview = true,
                    breaking = item.breaking
                )
            }
        }.onFailure { missing += OVERVIEW_KEY }

        // 已被总览收录的 URL 不再重复出现(总览版本信息更全:点评/指标/Breaking)
        val seenUrls = entries.map { it.url }.filterTo(mutableSetOf()) { it.isNotEmpty() }

        // 摘要条目按用户源顺序跟后;仅收录 v2 结构化条目(旧纯文本格式无条目可过滤)
        val order = sourceOrder.filter { it in SummaryRepository.SOURCE_KEYS } +
            SummaryRepository.SOURCE_KEYS.filter { it !in sourceOrder }
        order.forEach { key ->
            val result = summaries[key] ?: return@forEach
            result.onSuccess { summary ->
                fetchedAt = maxOf(fetchedAt, summary.fetchedAtMs)
                val structured = summary.content as? SummaryContent.Structured ?: return@onSuccess
                structured.items.forEach { item ->
                    if (item.url.isNotEmpty() && item.url in seenUrls) return@forEach
                    if (item.url.isNotEmpty()) seenUrls += item.url
                    entries += FollowCorpusEntry(
                        source = key,
                        title = item.title,
                        desc = item.desc,
                        url = item.url,
                        fromOverview = false
                    )
                }
            }.onFailure { missing += key }
        }

        // 全军覆没才视为失败:总览 + 8 源皆失败时语料没有任何输入,交错误态
        if (overview.isFailure && summaries.values.all { it.isFailure }) {
            throw (summaries.values.firstOrNull { it.isFailure }?.exceptionOrNull()
                ?: overview.exceptionOrNull() ?: AppException.Network())
        }
        FollowCorpus(entries = entries, missingSources = missing, dataFetchedAt = fetchedAt)
    }

    private val overviewRepo = OverviewRepository()
    private val summaryRepo = SummaryRepository()

    companion object {
        /** 语料缺失标注里代表「总览」的伪源 key(UI 映射为总览 Tab 名展示)。 */
        const val OVERVIEW_KEY = "overview"
    }
}

/**
 * 关键词匹配器 —— 纯函数、无状态:「我的关注」的过滤核心。
 *
 * 规则(v1 刻意保持简单):拉丁字母小写化后做 contains,中文直接子串包含;
 * 匹配文本 = 标题 + 摘要正文。命中任一关注词即收录;[selected] 非空时仅保留
 * 命中该词的条目(顶栏 chips 的单选过滤)。不做分词/同义词/语义匹配 ——
 * 价值验证后再考虑由流水线侧扩充同义词表。
 */
object FollowMatcher {

    /**
     * 对语料条目按关注词过滤。
     *
     * @param keywords 用户关注词(原样大小写;空列表直接返回空)
     * @param selected 单选过滤词(原样;null = 全部命中)
     * @return 命中条目(保持语料顺序),每项附命中的关注词(原样大小写)
     */
    fun filter(
        entries: List<FollowCorpusEntry>,
        keywords: List<String>,
        selected: String?
    ): List<FollowFeedItem> {
        val normalized = keywords.mapNotNull { k -> k.trim().takeIf { it.isNotEmpty() } }
        if (normalized.isEmpty()) return emptyList()
        val selectedNorm = selected?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return entries.mapNotNull { entry ->
            val text = (entry.title + " " + entry.desc).lowercase()
            val matched = normalized.filter { text.contains(it.lowercase()) }
            when {
                matched.isEmpty() -> null
                selectedNorm != null && matched.none { it.lowercase() == selectedNorm } -> null
                else -> FollowFeedItem(entry = entry, matchedKeywords = matched)
            }
        }
    }
}
