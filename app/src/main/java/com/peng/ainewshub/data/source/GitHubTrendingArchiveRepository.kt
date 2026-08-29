package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.repo.SearchIndexRepository
import com.peng.ainewshub.data.source.SourceKeys
import com.peng.ainewshub.data.model.TrendingRepo
import com.peng.ainewshub.data.model.TrendingResult

/**
 * GitHub Trending 的 [gitcode 归档]数据源。
 *
 * 数据来自数据流水线归档的快照。字段映射对齐 docs/news-hub-data-usage.md 的
 * github-trending items 表。无缓存概念:fetch == forceRefresh。
 * 失败抛 RuntimeException 交由 VM 显示 Error。
 */
class GitHubTrendingArchiveRepository {

    suspend fun fetch(): TrendingResult = load()

    suspend fun forceRefresh(): TrendingResult = load()

    private suspend fun load(): TrendingResult {
        val (fetchedAt, repos) = ArchiveHttpClient.fetchItemsList(SourceKeys.GITHUB_TRENDING) { obj, i ->
            TrendingRepo(
                rank = obj.optInt("rank", i + 1),
                owner = obj.optString("owner"),
                name = obj.optString("name"),
                url = obj.optString("url"),
                description = obj.optString("description"),
                language = obj.optString("language"),
                languageColor = obj.optString("languageColor"),
                totalStars = obj.optInt("totalStars", 0),
                forks = obj.optInt("forks", 0),
                starsToday = obj.optInt("starsToday", 0)
            )
        }
        // 本地搜索索引回填:标题用 owner/name(与列表页展示一致),摘要用仓库描述
        SearchIndexRepository.index(
            repos.map {
                SearchIndexRepository.SearchDoc(
                    url = it.url,
                    title = "${it.owner}/${it.name}",
                    summary = it.description,
                    source = SourceKeys.GITHUB_TRENDING
                )
            }
        )
        return TrendingResult(fetchedAt, repos)
    }
}
