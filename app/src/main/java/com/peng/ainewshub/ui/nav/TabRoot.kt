package com.peng.ainewshub.ui.nav

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import com.peng.ainewshub.ui.components.AppTab
import com.peng.ainewshub.ui.more.MoreScreen
import com.peng.ainewshub.ui.overview.OverviewScreen
import com.peng.ainewshub.ui.summary.SummaryScreen
import com.peng.ainewshub.ui.trends.TrendsScreen

/**
 * 渲染某个 tab 的根屏幕。各 onOpenXxx 入口统一在分支内经 [AppNavState.push]
 * 构造二级页,不再经参数表层层传递。
 */
@Composable
internal fun TabRoot(
    tab: AppTab,
    nav: AppNavState,
    reselectTick: Int,
    summaryPagerState: PagerState,
    overviewListState: LazyListState,
    trendsListState: LazyListState,
    onOpenUrl: (String, String, String?) -> Unit
) {
    when (tab) {
        AppTab.Overview -> OverviewScreen(
            onOpenUrl = onOpenUrl,
            // 顶栏搜索图标 → 本地搜索独立页(查设备内索引,覆盖本 App 浏览过的 8 源数据)
            onOpenSearch = { nav.push(Page.LocalSearch) },
            listState = overviewListState,
            reselectSignal = reselectTick
        )
        AppTab.Summary -> SummaryScreen(
            reselectSignal = reselectTick,
            pagerState = summaryPagerState,
            onOpenHackerNews = { nav.push(Page.HackerNews) },
            onOpenGitHubTrending = { nav.push(Page.GitHubTrending) },
            onOpenHuggingFacePapers = { nav.push(Page.HuggingFacePapers) },
            onOpenStormzhangAiNews = { nav.push(Page.StormzhangAiNews) },
            onOpenProductHunt = { nav.push(Page.ProductHunt) },
            onOpenRundownAi = { nav.push(Page.RundownAi) },
            onOpenOpenAiAnthropicNews = { nav.push(Page.OpenAiAnthropicNews) },
            onOpenFeaturedHub = { nav.push(Page.FeaturedHub) },
            // 摘要条目点击直达原文(走 openUrl 单点:内置 WebView + 记浏览历史)
            onOpenUrl = { url, title, source -> onOpenUrl(url, title, source) }
        )
        AppTab.Trends -> TrendsScreen(
            onOpenUrl = onOpenUrl,
            onOpenCloud = { nav.push(Page.TrendsCloud) },
            listState = trendsListState,
            reselectSignal = reselectTick
        )
        AppTab.More -> MoreScreen(
            onOpenSources = { nav.push(Page.Sources) },
            onOpenBrowseHistory = { nav.push(Page.BrowseHistory) },
            onOpenFavorites = { nav.push(Page.Favorites) },
            onOpenSummaryArchive = { nav.push(Page.SummaryArchive) },
            onOpenOverviewArchive = { nav.push(Page.OverviewArchive) },
            onOpenTrendsArchive = { nav.push(Page.TrendsArchive) },
            onOpenSettings = { nav.push(Page.Settings) },
            onOpenAiService = { nav.push(Page.AiService) },
            onOpenChangelog = { nav.push(Page.Changelog) },
            onOpenAbout = { nav.push(Page.About) }
        )
    }
}
