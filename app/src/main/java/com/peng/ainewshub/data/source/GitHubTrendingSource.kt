package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.TrendingResult

/**
 * GitHub Trending 数据源抽象 —— [com.peng.ainewshub.data.GitHubTrendingRepository]
 * (实时)与 [GitHubTrendingArchiveRepository](gitcode 归档)的共同接口。
 */
interface GitHubTrendingSource {
    suspend fun fetch(): TrendingResult
    suspend fun forceRefresh(): TrendingResult
}
