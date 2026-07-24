package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.AppException
import com.peng.ainewshub.data.TrendingRepo
import com.peng.ainewshub.data.TrendingResult
import org.json.JSONObject

/**
 * GitHub Trending 的 [gitcode 归档]数据源实现。
 *
 * 与 [com.peng.ainewshub.data.GitHubTrendingRepository](实时)并列,实现同一
 * [GitHubTrendingSource] 接口。数据来自 GitHub Action 每天 08:00 归档的快照。
 *
 * 字段映射对齐 docs/news-hub-data-usage.md 的 github-trending items 表。
 * 无缓存概念:fetch == forceRefresh。失败抛 RuntimeException 交由 VM 显示 Error。
 */
class GitHubTrendingArchiveRepository : GitHubTrendingSource {

    override suspend fun fetch(): TrendingResult = load()

    override suspend fun forceRefresh(): TrendingResult = load()

    private suspend fun load(): TrendingResult {
        val snapshot = ArchiveHttpClient.fetchLatestSnapshot(SOURCE_KEY)
        val fetchedAt = snapshot.optLong("fetched_at_ms", System.currentTimeMillis())
        val items = snapshot.optJSONArray("items")
            ?: throw AppException.NoData()
        val repos = (0 until items.length()).mapNotNull { i ->
            val obj = items.optJSONObject(i) ?: return@mapNotNull null
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
        if (repos.isEmpty()) throw AppException.NoData()
        return TrendingResult(fetchedAt, repos)
    }

    private companion object {
        const val SOURCE_KEY = "github-trending"
    }
}
