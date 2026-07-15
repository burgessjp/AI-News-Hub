package com.example.aihot.data.source

import com.example.aihot.data.TrendingResult

/**
 * GitHub Trending 数据源抽象 —— [com.example.aihot.data.GitHubTrendingRepository]
 * (实时)与 [GitHubTrendingArchiveRepository](gitcode 归档)的共同接口。
 */
interface GitHubTrendingSource {
    suspend fun fetch(): TrendingResult
    suspend fun forceRefresh(): TrendingResult
}
