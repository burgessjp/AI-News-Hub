package com.peng.ainewshub.data.model

import androidx.compose.runtime.Immutable

/**
 * GitHub Trending 单条仓库(来源:https://github.com/trending,由数据流水线抓取归档)。
 *
 * 与 [NewsItem] / [HackerNewsStory] 平行:Trending 是第三个独立数据源,
 * 字段语义(排名/今日新增/语言色点)与新闻/HN 完全不同,故单独建模。
 *
 * 不加 @Parcelize:点击仓库走 [url](内置 WebView),URL 是普通字符串,
 * 无需把整个对象跨页面传递(对比 NewsItem/HackerNewsStory 需带详情进二级页)。
 *
 * @param rank          1 起的排名(由列表位置决定)
 * @param owner         仓库所有者,如 "OpenCut-app"
 * @param name          仓库名,如 "OpenCut"
 * @param url           仓库完整 HTTPS 地址
 * @param description   一句话描述;可能为空
 * @param language      主语言,如 "TypeScript";部分仓库无语言,为空
 * @param languageColor 语言色点十六进制(如 "#3178c6");无语言时为空
 * @param totalStars    累计 star 数
 * @param forks         fork 数
 * @param starsToday    今日新增 star 数(页面默认 daily 窗口)
 */
@Immutable

data class TrendingRepo(
    val rank: Int,
    val owner: String,
    val name: String,
    val url: String,
    val description: String = "",
    val language: String = "",
    val languageColor: String = "",
    val totalStars: Int = 0,
    val forks: Int = 0,
    val starsToday: Int = 0
)

/**
 * Trending 拉取结果(带数据新鲜度),与 [HackerNewsTopStories] 同构。
 *
 * [fetchedAt] 是归档快照顶层的 fetched_at_ms(数据落盘时刻),
 * UI 据此在列表头显示「数据更新时间」。
 */
data class TrendingResult(
    override val fetchedAt: Long,
    val repos: List<TrendingRepo>
) : SourceListResult<TrendingRepo> {
    override val items: List<TrendingRepo> get() = repos
}
