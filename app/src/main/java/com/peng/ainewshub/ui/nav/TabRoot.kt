package com.peng.ainewshub.ui.nav

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import com.peng.ainewshub.data.source.SourceKeys
import com.peng.ainewshub.ui.components.AppTab
import com.peng.ainewshub.ui.more.MoreScreen
import com.peng.ainewshub.ui.overview.TodayScreen
import com.peng.ainewshub.ui.trends.HotwordsScreen

/**
 * 渲染某个 tab 的根屏幕。各 onOpenXxx 入口统一在分支内经 [AppNavState.push]
 * 构造二级页,不再经参数表层层传递。
 */
@Composable
internal fun TabRoot(
    tab: AppTab,
    nav: AppNavState,
    reselectTick: Int,
    todayListState: LazyListState,
    hotwordsListState: LazyListState,
    onOpenUrl: (String, String, String?) -> Unit
) {
    when (tab) {
        AppTab.Today -> TodayScreen(
            onOpenUrl = onOpenUrl,
            // 顶栏搜索图标 → 本地搜索独立页(查设备内索引,覆盖本 App 浏览过的 8 源数据)
            onOpenSearch = { nav.push(Page.LocalSearch()) },
            // 分源摘要区块头「查看全部」→ 源完整列表二级页
            onOpenSource = { source -> nav.push(sourcePageOf(source)) },
            listState = todayListState,
            reselectSignal = reselectTick
        )
        // 热词 tab 根屏:「我的关注」命中流(上)+ 热词榜/词云(下)的单页两段
        AppTab.Hotwords -> HotwordsScreen(
            onOpenUrl = onOpenUrl,
            onOpenCloud = { nav.push(Page.TrendsCloud) },
            // 展开区「查看全部命中」带词进本地搜索(查设备内索引的全部命中)
            onOpenLocalSearch = { nav.push(Page.LocalSearch(it)) },
            listState = hotwordsListState,
            reselectSignal = reselectTick
        )
        AppTab.More -> MoreScreen(
            onOpenSources = { nav.push(Page.Sources) },
            onOpenBrowseHistory = { nav.push(Page.BrowseHistory) },
            onOpenFavorites = { nav.push(Page.Favorites) },
            // 历史回顾 hub(总览/摘要/热词三段合一;替代原三个独立历史入口)
            onOpenHistoryHub = { nav.push(Page.HistoryHub) },
            onOpenSettings = { nav.push(Page.Settings) },
            onOpenAiService = { nav.push(Page.AiService) },
            onOpenAbout = { nav.push(Page.About) }
        )
    }
}

/**
 * 源 key → 源完整列表二级页(「今天」页分源区块头「查看全部」的目标)。
 * 8 源全集经 [SourceKeys] 常量分发;未知 key 兜底回信息源 hub(不崩、有出口)。
 */
private fun sourcePageOf(source: String): Page = when (source) {
    SourceKeys.HACKERNEWS -> Page.HackerNews
    SourceKeys.GITHUB_TRENDING -> Page.GitHubTrending
    SourceKeys.HUGGINGFACE_PAPERS -> Page.HuggingFacePapers
    SourceKeys.STORMZHANG_AI -> Page.StormzhangAiNews
    SourceKeys.PRODUCTHUNT -> Page.ProductHunt
    SourceKeys.RUNDOWN_AI -> Page.RundownAi
    SourceKeys.OPENAI_ANTHROPIC_NEWS -> Page.OpenAiAnthropicNews
    SourceKeys.AIHOT_FEATURED -> Page.FeaturedHub
    else -> Page.Sources
}
