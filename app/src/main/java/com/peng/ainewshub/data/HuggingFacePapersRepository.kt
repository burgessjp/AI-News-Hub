package com.peng.ainewshub.data

import com.peng.ainewshub.data.source.HuggingFacePapersSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File

/**
 * HuggingFace Trending Papers 抓取客户端。
 * 来源:https://huggingface.co/papers/trending(AK 每日精选 arXiv 论文,SSR HTML,无公开 JSON API)
 *
 * 继承 [BaseHtmlCacheRepository]:缓存 + Mutex + stale 兜底四步逻辑由基类统一,
 * 本类只提供 URL / headers / CF 挑战检测 / jsoup 解析 + 结果包装。TTL 4 小时(基类统一)。
 *
 * 卡片选择器 `article.relative.overflow-hidden.rounded-xl.border`,字段抽取见
 * [HuggingFacePaper.fromArticle]。HuggingFace 的 trending 列表已按热度排序,列表位置即排名。
 *
 * UA:站点对默认 OkHttp UA 偶尔差异对待,统一带浏览器 UA。
 *
 * @param cacheDir 缓存根目录,通常传 application.cacheDir;传 null 关闭缓存
 */
class HuggingFacePapersRepository(
    cacheDir: File? = null
) : BaseHtmlCacheRepository<HuggingFacePapersResult>(cacheDir), HuggingFacePapersSource {

    private val papersUrl = "https://huggingface.co/papers/trending"

    override val cacheFileName: String = "huggingface_papers.html"

    override suspend fun fetchHtml(): String = withContext(Dispatchers.IO) {
        val html = HttpClients.get(
            papersUrl,
            mapOf(
                "User-Agent" to HttpClients.DEFAULT_BROWSER_UA,
                "Accept" to "text/html,application/xhtml+xml",
                "Accept-Language" to "en-US,en;q=0.9,zh-CN,zh;q=0.8"
            )
        )
        // CF 挑战页检测:huggingface.co 套 Cloudflare,异常时返回 HTML 挑战页而非论文列表。
        // 常见特征:body 含 "Just a moment" 或标题为 CF 挑战页。
        if (html.contains("Just a moment", ignoreCase = true)) {
            throw AppException.RateLimited()
        }
        html
    }

    override fun packResult(fetchedAt: Long, rawHtml: String): HuggingFacePapersResult =
        HuggingFacePapersResult(fetchedAt, parse(rawHtml))

    /**
     * 用 jsoup 解析页面。
     *  - 论文卡片:`article.relative.overflow-hidden.rounded-xl.border`,按页面顺序编号
     *  - 卡片内字段抽取见 [HuggingFacePaper.fromArticle]
     *
     * HuggingFace 的 trending 列表已按热度排序,列表位置即排名,不做二次重排。
     */
    private fun parse(html: String): List<HuggingFacePaper> {
        val doc = Jsoup.parse(html)
        return doc.select("article.relative.overflow-hidden.rounded-xl.border")
            .mapIndexed { index, el ->
                HuggingFacePaper.fromArticle(el, index + 1)
            }
            .filterNotNull()
    }
}

/**
 * Trending Papers 拉取结果(带数据新鲜度),与 [TrendingResult] / [StormzhangAiNewsResult] 同构。
 *
 * [fetchedAt] 是这批数据实际落盘的时刻:命中缓存秒回时是缓存写入时刻(即「上次刷新时间」),
 * 走网络时是当前时刻。UI 据此在顶栏显示「上次刷新 N 分钟前」。
 */
data class HuggingFacePapersResult(
    val fetchedAt: Long,
    val papers: List<HuggingFacePaper>
)
