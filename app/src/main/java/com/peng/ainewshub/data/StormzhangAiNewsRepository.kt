package com.peng.ainewshub.data

import com.peng.ainewshub.data.source.StormzhangAiNewsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File

/**
 * stormzhang AI Daily 抓取客户端。
 * 来源:https://news.stormzhang.ai(每日 AI 资讯聚合,HTML 静态页,无 API)。
 *
 * 继承 [BaseHtmlCacheRepository]:缓存 + Mutex + stale 兜底四步逻辑由基类统一,
 * 本类只提供 URL / headers / jsoup 解析(含页面日期抽取)+ 结果包装。TTL 4 小时(基类统一)。
 *
 * UA:站点对默认 OkHttp UA 偶尔差异对待,统一带浏览器 UA。
 *
 * @param cacheDir 缓存根目录,通常传 application.cacheDir;传 null 关闭缓存
 */
class StormzhangAiNewsRepository(
    cacheDir: File? = null
) : BaseHtmlCacheRepository<StormzhangAiNewsResult>(cacheDir), StormzhangAiNewsSource {

    private val newsUrl = "https://news.stormzhang.ai"

    override val cacheFileName: String = "stormzhang_ai_news.html"

    override suspend fun fetchHtml(): String = withContext(Dispatchers.IO) {
        HttpClients.get(
            newsUrl,
            mapOf(
                "User-Agent" to HttpClients.DEFAULT_BROWSER_UA,
                "Accept" to "text/html,application/xhtml+xml",
                "Accept-Language" to "zh-CN,zh;q=0.9"
            )
        )
    }

    override fun packResult(fetchedAt: Long, rawHtml: String): StormzhangAiNewsResult {
        val (news, date) = parse(rawHtml)
        return StormzhangAiNewsResult(fetchedAt, news, date)
    }

    /**
     * 用 jsoup 解析页面。
     *  - 资讯条目:`a.item`,按页面顺序编号
     *  - 页面日期:取自 `<title>`(形如 "AI Daily — 2026.07.13")的日期段
     *
     * @return (资讯列表, 页面日期字符串;解析不到时为空)
     */
    private fun parse(html: String): Pair<List<StormzhangAiNews>, String> {
        val doc = Jsoup.parse(html)
        val news = doc.select("a.item").mapIndexed { index, el ->
            StormzhangAiNews.fromItem(el, index + 1)
        }.filterNotNull()

        // 页面日期:优先 <title> 中 "— YYYY.MM.DD" 段;取不到再回退 <meta property="article:...">。
        val title = doc.title()
        val date = TITLE_DATE_RE.find(title)?.value
            ?: ""
        return news to date
    }

    private companion object {
        /** 从 title 中提取日期段,如 "AI Daily — 2026.07.13" → "2026.07.13"。 */
        val TITLE_DATE_RE = Regex("\\d{4}\\.\\d{2}\\.\\d{2}")
    }
}

/**
 * AI 资讯拉取结果(带数据新鲜度),与 [TrendingResult] 同构。
 *
 * [fetchedAt] 是这批数据实际落盘的时刻:命中缓存秒回时是缓存写入时刻(即「上次刷新时间」),
 * 走网络时是当前时刻。UI 据此在顶栏显示「上次刷新 N 分钟前」。
 *
 * [pageDate] 是页面声明的资讯日期(如 "2026.07.13"),取自 title,解析不到时为空。
 */
data class StormzhangAiNewsResult(
    override val fetchedAt: Long,
    val news: List<StormzhangAiNews>,
    val pageDate: String = ""
) : SourceListResult<StormzhangAiNews> {
    override val items: List<StormzhangAiNews> get() = news
}
