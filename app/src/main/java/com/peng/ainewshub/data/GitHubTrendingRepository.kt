package com.peng.ainewshub.data

import com.peng.ainewshub.data.source.GitHubTrendingSource
import org.jsoup.Jsoup
import java.io.File

/**
 * GitHub Trending 抓取客户端。
 * 来源:https://github.com/trending(无官方 API,抓 HTML 用 jsoup 解析)。
 *
 * 继承 [BaseHtmlCacheRepository]:缓存 + Mutex + stale 兜底四步逻辑由基类统一,
 * 本类只提供 URL / headers / jsoup 解析 + 结果包装。TTL 4 小时(基类统一)。
 *
 * UA:GitHub 对默认 OkHttp UA 偶尔差异对待(可能裁剪条目),统一带浏览器 UA。
 *
 * @param cacheDir 缓存根目录,通常传 application.cacheDir;传 null 关闭缓存
 */
class GitHubTrendingRepository(
    cacheDir: File? = null
) : BaseHtmlCacheRepository<TrendingResult>(cacheDir), GitHubTrendingSource {

    private val trendingUrl = "https://github.com/trending"

    override val cacheFileName: String = "github_trending.html"

    override suspend fun fetchHtml(): String =
        HttpClients.get(
            trendingUrl,
            mapOf(
                "User-Agent" to HttpClients.DEFAULT_BROWSER_UA,
                "Accept" to "text/html,application/xhtml+xml",
                "Accept-Language" to "en-US,en;q=0.9"
            )
        )

    override fun packResult(fetchedAt: Long, rawHtml: String): TrendingResult =
        TrendingResult(fetchedAt, parse(rawHtml))

    /** 用 jsoup 解析 trending HTML;条目数动态,返回多少解析多少。 */
    private fun parse(html: String): List<TrendingRepo> {
        val doc = Jsoup.parse(html)
        return doc.select("article.Box-row").mapIndexed { index, el ->
            // index 未命中时 fromArticle 返回 null,mapNotNull 跳过,rank 可能不连续;
            // 这里用 index+1 作为初始 rank,失败项不影响后续排名(排名本质是页面顺序)。
            TrendingRepo.fromArticle(el, index + 1)
        }.filterNotNull()
    }
}

/**
 * Trending 拉取结果(带数据新鲜度),与 [HackerNewsTopStories] 同构。
 *
 * [fetchedAt] 是这批数据实际落盘的时刻:命中缓存秒回时是缓存写入时刻(即「上次刷新时间」),
 * 走网络时是当前时刻。UI 据此在顶栏显示「上次刷新 N 分钟前」。
 */
data class TrendingResult(
    override val fetchedAt: Long,
    val repos: List<TrendingRepo>
) : SourceListResult<TrendingRepo> {
    override val items: List<TrendingRepo> get() = repos
}
