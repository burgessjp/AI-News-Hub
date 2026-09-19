package com.peng.ainewshub.ui.nav

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import com.peng.ainewshub.data.source.SourceKeys
import com.peng.ainewshub.ui.components.AppTab
import com.peng.ainewshub.ui.follows.FollowsScreen
import com.peng.ainewshub.ui.more.MoreScreen
import com.peng.ainewshub.ui.overview.TodayScreen

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
    followsListState: LazyListState,
    onOpenUrl: (String, String, String?) -> Unit
) {
    when (tab) {
        AppTab.Today -> TodayScreen(
            onOpenUrl = onOpenUrl,
            // 顶栏搜索图标 → 本地搜索独立页(查设备内索引,覆盖本 App 浏览过的 8 源数据)
            onOpenSearch = { nav.push(Page.LocalSearch()) },
            // 报头「热词」→ 热词榜二级页(近 N 天热词 + 词云入口)
            onOpenTrends = { nav.push(Page.Hotwords) },
            // 分源摘要区块头「查看全部」→ 源完整列表二级页
            onOpenSource = { source -> nav.push(sourcePageOf(source)) },
            listState = todayListState,
            reselectSignal = reselectTick
        )
        // 关注 tab 根屏:关键词命中流(热词榜/词云已拆入 Page.Hotwords 二级页)
        AppTab.Follows -> FollowsScreen(
            onOpenUrl = onOpenUrl,
            // 空态引导「去热词页看看趋势」→ 热词榜二级页
            onOpenTrends = { nav.push(Page.Hotwords) },
            listState = followsListState,
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
