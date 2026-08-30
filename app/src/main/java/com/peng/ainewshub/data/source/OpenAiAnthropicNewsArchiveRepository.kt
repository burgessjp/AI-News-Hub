package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.model.OpenAiAnthropicNews
import com.peng.ainewshub.data.model.OpenAiAnthropicNewsResult
import com.peng.ainewshub.data.repo.SearchIndexRepository
import com.peng.ainewshub.data.source.SourceKeys

/**
 * OpenAI x Anthropic 厂商动态的 [gitcode 归档]数据源。
 *
 * 数据来自数据流水线([scripts/fetch_data.py] 抓 OpenAI RSS + Anthropic HTML
 * 合并归档,两家均无稳定公开 API,App 端不直连)的快照。
 * 字段映射对齐 [com.peng.ainewshub.data.model.OpenAiAnthropicNews.fromJson]。
 * 缓存语义:fetch() 走 index 2 分钟缓存,forceRefresh() 绕过 TTL 强制重读 index
 * (源列表二级页下拉刷新);快照本体按路径不可变,无需 force。
 * 失败抛 RuntimeException 交由 VM 显示 Error。
 */
class OpenAiAnthropicNewsArchiveRepository {

    suspend fun fetch(): OpenAiAnthropicNewsResult = load()

    suspend fun forceRefresh(): OpenAiAnthropicNewsResult = load(true)

    private suspend fun load(force: Boolean = false): OpenAiAnthropicNewsResult {
        val (fetchedAt, articles) = ArchiveHttpClient.fetchItemsList(SourceKeys.OPENAI_ANTHROPIC_NEWS, force = force) { obj, i ->
            OpenAiAnthropicNews.fromJson(obj, fallbackRank = i + 1)
        }
        // 本地搜索索引回填
        SearchIndexRepository.index(
            articles.map {
                SearchIndexRepository.SearchDoc(it.url, it.title, it.summary, SourceKeys.OPENAI_ANTHROPIC_NEWS)
            }
        )
        return OpenAiAnthropicNewsResult(fetchedAt, articles)
    }
}
