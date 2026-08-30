package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.model.RundownAiArticle
import com.peng.ainewshub.data.model.RundownAiResult
import com.peng.ainewshub.data.repo.SearchIndexRepository
import com.peng.ainewshub.data.source.SourceKeys

/**
 * The Rundown AI 的 gitcode 归档数据源。
 *
 * 数据来自数据流水线([scripts/fetch_data.py] 经首页 HTML 抓取归档)的快照。
 * 字段映射对齐 docs/news-hub-data-usage.md 的 rundown-ai items 表
 * 与 [com.peng.ainewshub.data.model.RundownAiArticle.fromJson]。
 * 缓存语义:fetch() 走 index 2 分钟缓存,forceRefresh() 绕过 TTL 强制重读 index
 * (源列表二级页下拉刷新);快照本体按路径不可变,无需 force。
 * 失败抛 RuntimeException 交由 VM 显示 Error。
 */
class RundownAiArchiveRepository {

    suspend fun fetch(): RundownAiResult = load()

    suspend fun forceRefresh(): RundownAiResult = load(true)

    private suspend fun load(force: Boolean = false): RundownAiResult {
        val (fetchedAt, articles) = ArchiveHttpClient.fetchItemsList(SourceKeys.RUNDOWN_AI, force = force) { obj, i ->
            RundownAiArticle.fromJson(obj, fallbackRank = i + 1)
        }
        // 本地搜索索引回填:摘要用副标题(正文是 newsletter 卡片,无独立摘要)
        SearchIndexRepository.index(
            articles.map {
                SearchIndexRepository.SearchDoc(it.url, it.title, it.subtitle, SourceKeys.RUNDOWN_AI)
            }
        )
        return RundownAiResult(fetchedAt, articles)
    }
}
