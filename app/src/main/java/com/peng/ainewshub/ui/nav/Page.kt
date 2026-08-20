package com.peng.ainewshub.ui.nav

import android.os.Bundle
import com.peng.ainewshub.data.HackerNewsStory
import com.peng.ainewshub.data.NewsItem
import com.peng.ainewshub.ui.anim.PageNavStyle
import com.peng.ainewshub.ui.components.AppTab

/**
 * 二级页 —— 各 tab 共用的 push 页面类型。
 *
 * - Detail:    新闻详情
 * - Web:       内置 WebView(原文 / AI HOT 阅读页)
 * - DailyArchive: 历史日报归档列表
 * - DailyDate: 指定日期日报
 * - Search:    搜索页
 * - Settings:  设置(主题)
 * - About:     关于
 * - HackerNews: HackerNews 列表
 * - HackerNewsComments: HackerNews 单条 story 的评论树
 */
internal sealed interface Page {
    /**
     * 该页面的转场风格,由 [pageTransition] 统一消费。
     *
     * 默认 PUSH(横向推入),绝大多数二级页无需单独声明。
     * 仅 Web 含 AndroidView(WebView),位移会撕裂 → override 为 FADE(纯淡入淡出)。
     * 其余页(含 Detail)是纯 Compose,正常 PUSH 即可。
     */
    val navStyle: PageNavStyle get() = PageNavStyle.PUSH

    data class Detail(val item: NewsItem) : Page
    // title 无默认值:占位文案随语言取词,两处构造点(openUrl / pageFromBundle)均显式传入
    // source:来源标签(如 "GitHub Trending"),随 Page 传递供收藏落库;可空
    data class Web(val url: String, val title: String, val source: String? = null) : Page {
        override val navStyle = PageNavStyle.FADE
    }
    data object DailyArchive : Page
    data class DailyDate(val date: String) : Page
    /** 全部动态 —— 原为独立 tab,现改为从精选页 push 进入的二级页。 */
    data object All : Page
    /** AI 日报 —— 原为独立 tab,现改为从「全部」页 push 进入的二级页。 */
    data object Daily : Page
    data object Search : Page
    /** 本地搜索 —— 独立页:查设备内 Room 索引(浏览过的 8 源批次),总览顶栏进入。 */
    data object LocalSearch : Page
    /** 我的关注 —— 关键词订阅的当日命中流(总览 Top10 + 8 源摘要过滤),总览顶栏进入。 */
    data object Follows : Page
    data object Settings : Page
    /** AI 服务 —— 服务商/模型/翻译开关 + 用量统计,从「更多」页进入。 */
    data object AiService : Page
    data object About : Page
    /** 关于 · 数据来源 —— 8 源品牌色列表,点击行经内置 WebView 访问各源官网(关于页进入)。 */
    data object AboutSources : Page
    /** 关于 · 开源依赖 —— 依赖清单 + license 徽章,点击行打开项目主页(关于页进入)。 */
    data object AboutOss : Page
    /** 更新日志 —— 各版本新增/修复/改进(读构建时打包的 CHANGELOG.md),从「更多」/「关于」页进入。 */
    data object Changelog : Page
    data object HackerNews : Page
    data class HackerNewsComments(val story: HackerNewsStory) : Page
    data object GitHubTrending : Page
    data object StormzhangAiNews : Page
    data object HuggingFacePapers : Page
    data object ProductHunt : Page
    data object RundownAi : Page
    data object OpenAiAnthropicNews : Page
    /** AIHot 精选 —— 原为独立根 tab,现改为从「更多」页进入的二级页(复用 FeaturedTab)。 */
    data object FeaturedHub : Page
    /** 信息源(Sources) —— Hub 浏览区独立页,聚合 8 个源全集入口。从「更多」页进入。 */
    data object Sources : Page
    data object BrowseHistory : Page
    /** 收藏(稍后读) —— WebView 顶栏星标的文章列表,从「更多」页进入。 */
    data object Favorites : Page
    /** 历史摘要 —— 可选日期列表(归档 history 索引),从「更多」页进入。 */
    data object SummaryArchive : Page
    /** 历史摘要 —— 指定日期的全源摘要卡页(复用摘要卡片实现)。 */
    data class SummaryDate(val date: String) : Page
    /** 历史总览 —— 可选日期列表(归档 overview_history 索引),从「更多」页进入。 */
    data object OverviewArchive : Page
    /** 历史总览 —— 指定日期的总览页(复用总览 Tab 内容实现)。 */
    data class OverviewDate(val date: String) : Page
    /** 历史热词 —— 可选日期列表(归档 trends_history 索引),从「更多」页进入。 */
    data object TrendsArchive : Page
    /** 历史热词 —— 指定日期的热词榜页(复用趋势 Tab 内容实现)。 */
    data class TrendsDate(val date: String) : Page
    /** 趋势词云 —— 近窗口期热词的词云全景页(趋势 Tab caption 行进入,纯 Canvas 无列表)。 */
    data object TrendsCloud : Page
}

/**
 * 把单个 [Page] 写入 / 读出 [Bundle]。tag(键 "t")区分子类型;
 * NewsItem / HackerNewsStory 已是 Parcelable,可直接 putParcelable。
 */
internal fun Page.toBundle(): Bundle = Bundle().apply {
    when (this@toBundle) {
        is Page.Detail -> { putString("t", "Detail"); putParcelable("item", item) }
        is Page.Web -> { putString("t", "Web"); putString("url", url); putString("title", title); putString("source", source) }
        is Page.DailyDate -> { putString("t", "DailyDate"); putString("date", date) }
        is Page.HackerNewsComments -> { putString("t", "HNComments"); putParcelable("story", story) }
        is Page.DailyArchive -> putString("t", "DailyArchive")
        is Page.All -> putString("t", "All")
        is Page.Daily -> putString("t", "Daily")
        is Page.Search -> putString("t", "Search")
        is Page.LocalSearch -> putString("t", "LocalSearch")
        is Page.Follows -> putString("t", "Follows")
        is Page.Settings -> putString("t", "Settings")
        is Page.AiService -> putString("t", "AiService")
        is Page.About -> putString("t", "About")
        is Page.AboutSources -> putString("t", "AboutSources")
        is Page.AboutOss -> putString("t", "AboutOss")
        is Page.Changelog -> putString("t", "Changelog")
        is Page.HackerNews -> putString("t", "HackerNews")
        is Page.GitHubTrending -> putString("t", "GitHubTrending")
        is Page.StormzhangAiNews -> putString("t", "StormzhangAiNews")
        is Page.HuggingFacePapers -> putString("t", "HuggingFacePapers")
        is Page.ProductHunt -> putString("t", "ProductHunt")
        is Page.RundownAi -> putString("t", "RundownAi")
        is Page.OpenAiAnthropicNews -> putString("t", "OpenAiAnthropicNews")
        is Page.FeaturedHub -> putString("t", "FeaturedHub")
        is Page.Sources -> putString("t", "Sources")
        is Page.BrowseHistory -> putString("t", "BrowseHistory")
        is Page.Favorites -> putString("t", "Favorites")
        is Page.SummaryArchive -> putString("t", "SummaryArchive")
        is Page.SummaryDate -> { putString("t", "SummaryDate"); putString("date", date) }
        is Page.OverviewArchive -> putString("t", "OverviewArchive")
        is Page.OverviewDate -> { putString("t", "OverviewDate"); putString("date", date) }
        is Page.TrendsArchive -> putString("t", "TrendsArchive")
        is Page.TrendsDate -> { putString("t", "TrendsDate"); putString("date", date) }
        is Page.TrendsCloud -> putString("t", "TrendsCloud")
    }
}

@Suppress("DEPRECATION")
internal fun pageFromBundle(b: Bundle, webFallbackTitle: String): Page? {
    return when (b.getString("t")) {
        "Detail" -> b.getParcelable<NewsItem>("item")?.let { Page.Detail(it) }
        "Web" -> Page.Web(b.getString("url") ?: "", b.getString("title") ?: webFallbackTitle, b.getString("source"))
        "DailyDate" -> b.getString("date")?.let { Page.DailyDate(it) }
        "HNComments" -> b.getParcelable<HackerNewsStory>("story")?.let { Page.HackerNewsComments(it) }
        "DailyArchive" -> Page.DailyArchive
        "All" -> Page.All
        "Daily" -> Page.Daily
        "Search" -> Page.Search
        "LocalSearch" -> Page.LocalSearch
        "Follows" -> Page.Follows
        "Settings" -> Page.Settings
        "AiService" -> Page.AiService
        "About" -> Page.About
        "AboutSources" -> Page.AboutSources
        "AboutOss" -> Page.AboutOss
        "Changelog" -> Page.Changelog
        "HackerNews" -> Page.HackerNews
        "GitHubTrending" -> Page.GitHubTrending
        "StormzhangAiNews" -> Page.StormzhangAiNews
        "HuggingFacePapers" -> Page.HuggingFacePapers
        "ProductHunt" -> Page.ProductHunt
        "RundownAi" -> Page.RundownAi
        "OpenAiAnthropicNews" -> Page.OpenAiAnthropicNews
        "FeaturedHub" -> Page.FeaturedHub
        "Sources" -> Page.Sources
        "BrowseHistory" -> Page.BrowseHistory
        "Favorites" -> Page.Favorites
        "SummaryArchive" -> Page.SummaryArchive
        "SummaryDate" -> b.getString("date")?.let { Page.SummaryDate(it) }
        "OverviewArchive" -> Page.OverviewArchive
        "OverviewDate" -> b.getString("date")?.let { Page.OverviewDate(it) }
        "TrendsArchive" -> Page.TrendsArchive
        "TrendsDate" -> b.getString("date")?.let { Page.TrendsDate(it) }
        "TrendsCloud" -> Page.TrendsCloud
        else -> null
    }
}

/**
 * 把各 tab 页栈序列化为一个 Bundle(Parcelable,可直接被 rememberSaveable 的
 * Saver 接管,避免 Serializable 容器混入 Parcelable 元素的兼容问题)。
 * 每个 tab 一个键(tab.name),值为 ArrayList<Bundle>。
 */
internal fun stacksToBundle(stacks: Map<AppTab, List<Page>>): Bundle = Bundle().apply {
    stacks.forEach { (tab, pages) ->
        val arr = arrayListOf<Bundle>()
        pages.forEach { arr += it.toBundle() }
        putParcelableArrayList(tab.name, arr)
    }
}

/**
 * 从 Bundle 还原各 tab 页栈(与 [stacksToBundle] 互逆)。
 *
 * webFallbackTitle:恢复 Web 页且 Bundle 缺 title 时的占位文案(随语言取词,common_loading)。
 */
@Suppress("DEPRECATION")
internal fun stacksFromBundle(b: Bundle, webFallbackTitle: String): Map<AppTab, List<Page>> =
    AppTab.entries.mapNotNull { tab ->
        val arr = b.getParcelableArrayList<Bundle>(tab.name) ?: return@mapNotNull null
        tab to arr.mapNotNull { pageFromBundle(it, webFallbackTitle) }
    }.toMap()

/**
 * 顶层屏幕标识:根(tab) 或 二级页。供 [androidx.compose.animation.AnimatedContent] 区分转场策略。
 *
 * [navStyle] 让 transitionSpec 直接查表,无需类型判断:
 * 根页统一为 [PageNavStyle.NONE],二级页沿用其 [Page.navStyle]。
 */
internal sealed interface Screen {
    val navStyle: PageNavStyle

    data class Root(val tab: AppTab) : Screen {
        override val navStyle = PageNavStyle.NONE
    }
    data class Secondary(val page: Page) : Screen {
        override val navStyle get() = page.navStyle
    }
}
