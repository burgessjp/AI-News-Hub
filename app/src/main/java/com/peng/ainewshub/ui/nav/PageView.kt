package com.peng.ainewshub.ui.nav

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.peng.ainewshub.R
import com.peng.ainewshub.data.AiConfig
import com.peng.ainewshub.data.AiConfigStore
import com.peng.ainewshub.data.AiUsageStore
import com.peng.ainewshub.data.BrowseHistoryRepository
import com.peng.ainewshub.data.FavoritesRepository
import com.peng.ainewshub.data.NewsItem
import com.peng.ainewshub.data.SourceKeys
import com.peng.ainewshub.ui.NewsDetailScreen
import com.peng.ainewshub.ui.daily.DailyArchiveScreen
import com.peng.ainewshub.ui.daily.DailyDateScreen
import com.peng.ainewshub.ui.daily.DailyScreen
import com.peng.ainewshub.ui.i18n.AppLanguage
import com.peng.ainewshub.ui.items.BrowseHistoryScreen
import com.peng.ainewshub.ui.items.FavoritesScreen
import com.peng.ainewshub.ui.items.GitHubTrendingScreen
import com.peng.ainewshub.ui.items.HackerNewsCommentsScreen
import com.peng.ainewshub.ui.items.HackerNewsScreen
import com.peng.ainewshub.ui.items.HuggingFacePapersScreen
import com.peng.ainewshub.ui.items.LocalSearchScreen
import com.peng.ainewshub.ui.items.OpenAiAnthropicNewsScreen
import com.peng.ainewshub.ui.items.ProductHuntScreen
import com.peng.ainewshub.ui.items.RundownAiScreen
import com.peng.ainewshub.ui.items.SearchScreen
import com.peng.ainewshub.ui.items.StormzhangAiNewsScreen
import com.peng.ainewshub.ui.more.AboutOssScreen
import com.peng.ainewshub.ui.more.AboutScreen
import com.peng.ainewshub.ui.more.AboutSourcesScreen
import com.peng.ainewshub.ui.more.AiServiceScreen
import com.peng.ainewshub.ui.more.ChangelogScreen
import com.peng.ainewshub.ui.more.FontChoice
import com.peng.ainewshub.ui.more.FontScale
import com.peng.ainewshub.ui.more.SettingsScreen
import com.peng.ainewshub.ui.more.SettingsStore
import com.peng.ainewshub.ui.more.SourcesScreen
import com.peng.ainewshub.ui.more.ThemeMode
import com.peng.ainewshub.ui.overview.OverviewArchiveScreen
import com.peng.ainewshub.ui.overview.OverviewDateScreen
import com.peng.ainewshub.ui.summary.SummaryArchiveScreen
import com.peng.ainewshub.ui.summary.SummaryDateScreen
import com.peng.ainewshub.ui.trends.TrendsArchiveScreen
import com.peng.ainewshub.ui.trends.TrendsCloudScreen
import com.peng.ainewshub.ui.trends.TrendsDateScreen
import com.peng.ainewshub.ui.tabs.AllTab
import com.peng.ainewshub.ui.tabs.FeaturedTab
import com.peng.ainewshub.ui.webview.WebViewScreen
import kotlinx.coroutines.launch

/**
 * 二级页共用环境:stores/repos 与全局 AI 配置,由 AiNewsHubApp 构造一次下传,
 * 取代原先十余个散参数的层层穿线(无 DI 框架,仍为 Composable 内直接构造)。
 */
internal class PageEnv(
    val settingsStore: SettingsStore,
    val configStore: AiConfigStore,
    val usageStore: AiUsageStore,
    /** AI 服务全局配置:WebView 整页翻译读取(开关/就绪态判定)。 */
    val aiConfig: AiConfig,
    /** 每日更新通知自查链上次运行时刻(设置页「上次检查」)。 */
    val lastNotifyCheckAt: Long,
    val browseHistoryRepo: BrowseHistoryRepository,
    val favoritesRepo: FavoritesRepository
)

/**
 * 显示偏好值 + 设置页回调:值即 [SettingsStore.DisplayPrefs] 全集,
 * 由 AiNewsHubApp 构造一次下传,仅设置页消费。
 */
internal class DisplayControls(
    val prefs: SettingsStore.DisplayPrefs,
    val onSelectTheme: (ThemeMode) -> Unit,
    val onToggleDynamicColor: (Boolean) -> Unit,
    val onSelectFont: (FontChoice) -> Unit,
    val onSelectFontScale: (FontScale) -> Unit,
    val onSelectLanguage: (AppLanguage) -> Unit,
    val onToggleDailyNotify: (Boolean) -> Unit
)

/**
 * 渲染某个二级页。页面入口回调统一在分支内经 [AppNavState.push] 构造,
 * 不再经参数表层层传递;仅 onOpenUrl(记浏览历史的唯一入口)与标题回写为真实参数。
 */
@Composable
internal fun PageView(
    page: Page,
    nav: AppNavState,
    onOpenUrl: (String, String, String?) -> Unit,
    onTitleResolved: (String, String) -> Unit,
    listStates: MutableMap<Page, LazyListState>,
    pagerStates: MutableMap<Page, PagerState>,
    env: PageEnv,
    display: DisplayControls,
    darkTheme: Boolean
) {
    val onBack = { nav.pop() }
    // 已读记录用:点进详情/评论页即视为「看过」(详见各记录点注释)
    val readScope = rememberCoroutineScope()
    val onOpenSettings = { nav.push(Page.Settings) }
    // 浏览历史来源标签:下方非 Composable 回调(onOpenUrl lambda)里捕获,提前取词
    val dailyLabel = stringResource(R.string.history_source_daily)
    val aboutLabel = stringResource(R.string.history_source_about)
    // 各源标签:统一走 source_title_* 取词,避免浏览历史里硬编码散落
    val aihotLabel = stringResource(R.string.source_title_aihot_featured)
    val hackerNewsLabel = stringResource(R.string.source_title_hackernews)
    val githubTrendingLabel = stringResource(R.string.source_title_github_trending)
    val stormzhangLabel = stringResource(R.string.source_title_stormzhang)
    val huggingFaceLabel = stringResource(R.string.source_title_huggingface)
    val productHuntLabel = stringResource(R.string.source_title_producthunt)
    val rundownLabel = stringResource(R.string.source_title_rundown)
    val openAiAnthropicLabel = stringResource(R.string.source_title_openai_anthropic)
    // 点进详情即视为已读:记录 permalink 优先的 URL(详情「阅读页」打开的即它;
    // 记录同时进浏览历史,语义 = 我点开过的文章,列表弱化即时生效)
    val onItemClick: (NewsItem) -> Unit = { item ->
        val readUrl = item.permalink.ifBlank { item.url }
        if (readUrl.isNotBlank()) {
            readScope.launch { env.browseHistoryRepo.record(readUrl, item.title, aihotLabel) }
        }
        nav.push(Page.Detail(item))
    }
    when (page) {
        is Page.Detail -> NewsDetailScreen(
            item = page.item,
            onBack = onBack,
            // 适配:Detail 页打开的链接来自 AI HOT 详情,标注 "AIHot 精选"
            onOpenUrl = { url, title -> onOpenUrl(url, title, aihotLabel) }
        )
        is Page.Web -> WebViewScreen(
            url = page.url,
            title = page.title,
            darkTheme = darkTheme,
            fontScale = display.prefs.fontScale,
            aiConfig = env.aiConfig,
            favoritesRepo = env.favoritesRepo,
            // 浏览历史仓库:阅读进度(「继续上次阅读」)按 URL 落库读取
            browseHistoryRepo = env.browseHistoryRepo,
            source = page.source,
            onBack = onBack,
            onOpenSettings = onOpenSettings,
            onTitleResolved = onTitleResolved
        )
        Page.All -> AllTab(
            onItemClick = onItemClick,
            onBack = onBack,
            onOpenDaily = { nav.push(Page.Daily) },
            onOpenSearch = { nav.push(Page.Search) },
            listState = listStates.forPage(page)
        )
        Page.Daily -> DailyScreen(
            onItemClick = onItemClick,
            onOpenArchive = { nav.push(Page.DailyArchive) },
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, dailyLabel) },
            listState = listStates.forPage(page)
        )
        Page.DailyArchive -> DailyArchiveScreen(
            onSelectDate = { nav.push(Page.DailyDate(it)) },
            onBack = onBack,
            listState = listStates.forPage(page)
        )
        is Page.DailyDate -> DailyDateScreen(
            date = page.date,
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, dailyLabel) },
            listState = listStates.forPage(page)
        )
        Page.Search -> SearchScreen(
            onBack = onBack,
            onItemClick = onItemClick,
            listState = listStates.forPage(page)
        )
        Page.LocalSearch -> LocalSearchScreen(
            onBack = onBack,
            // 结果直达 WebView;source 标签传条目自身来源(见 LocalSearchScreen)
            onOpenUrl = { url, title, source -> onOpenUrl(url, title, source) },
            listState = listStates.forPage(page)
        )
        Page.Settings -> SettingsScreen(
            themeMode = display.prefs.themeMode,
            onSelectTheme = display.onSelectTheme,
            dynamicColor = display.prefs.dynamicColor,
            onToggleDynamicColor = display.onToggleDynamicColor,
            fontChoice = display.prefs.fontChoice,
            onSelectFont = display.onSelectFont,
            fontScale = display.prefs.fontScale,
            onSelectFontScale = display.onSelectFontScale,
            language = display.prefs.language,
            onSelectLanguage = display.onSelectLanguage,
            dailyNotify = display.prefs.dailyNotify,
            lastNotifyCheckAt = env.lastNotifyCheckAt,
            onToggleDailyNotify = display.onToggleDailyNotify,
            settingsStore = env.settingsStore,
            browseHistoryRepo = env.browseHistoryRepo,
            onBack = onBack
        )
        Page.AiService -> AiServiceScreen(
            configStore = env.configStore,
            usageStore = env.usageStore,
            onBack = onBack
        )
        Page.About -> AboutScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, aboutLabel) },
            onOpenChangelog = { nav.push(Page.Changelog) },
            onOpenSources = { nav.push(Page.AboutSources) },
            onOpenOss = { nav.push(Page.AboutOss) }
        )
        // 关于 · 数据来源:8 源品牌色列表,点击行经内置 WebView 访问各源官网
        Page.AboutSources -> AboutSourcesScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, aboutLabel) },
            listState = listStates.forPage(page)
        )
        // 关于 · 开源依赖:清单 + license 徽章,点击行打开项目主页
        Page.AboutOss -> AboutOssScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, aboutLabel) },
            listState = listStates.forPage(page)
        )
        // 更新日志:读构建时打包的 CHANGELOG.md(纯静态页),列表状态照约定上提
        Page.Changelog -> ChangelogScreen(
            onBack = onBack,
            listState = listStates.forPage(page)
        )
        Page.HackerNews -> HackerNewsScreen(
            onBack = onBack,
            onOpenComments = { story ->
                // 进评论页即视为已读:HN 行点击不开 WebView,记录 discussion 页 URL
                // 驱动列表弱化(条目也会出现在浏览历史,点开即 HN 讨论页,语义自洽)
                readScope.launch { env.browseHistoryRepo.record(story.discussionUrl, story.title, hackerNewsLabel) }
                nav.push(Page.HackerNewsComments(story))
            },
            onOpenSettings = onOpenSettings,
            listState = listStates.forPage(page)
        )
        is Page.HackerNewsComments -> HackerNewsCommentsScreen(
            story = page.story,
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, hackerNewsLabel) },
            onOpenSettings = onOpenSettings,
            listState = listStates.forPage(page)
        )
        Page.GitHubTrending -> GitHubTrendingScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, githubTrendingLabel) },
            onOpenSettings = onOpenSettings,
            listState = listStates.forPage(page)
        )
        Page.StormzhangAiNews -> StormzhangAiNewsScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, stormzhangLabel) },
            listState = listStates.forPage(page)
        )
        Page.HuggingFacePapers -> HuggingFacePapersScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, huggingFaceLabel) },
            onOpenSettings = onOpenSettings,
            listState = listStates.forPage(page)
        )
        Page.ProductHunt -> ProductHuntScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, productHuntLabel) },
            onOpenSettings = onOpenSettings,
            listState = listStates.forPage(page)
        )
        Page.RundownAi -> RundownAiScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, rundownLabel) },
            onOpenSettings = onOpenSettings,
            listState = listStates.forPage(page)
        )
        Page.OpenAiAnthropicNews -> OpenAiAnthropicNewsScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, openAiAnthropicLabel) },
            onOpenSettings = onOpenSettings,
            listState = listStates.forPage(page)
        )
        // AIHot 精选(原根 tab,现二级页):复用 FeaturedTab,UI 含今日热点 +
        // 最新精选列表 + 「全部 ›」。顶栏带返回箭头(onBack),列表底部不预留底栏
        //(二级页底栏不悬浮)。reselectSignal 传 0(非根 tab,无重击语义)。
        Page.FeaturedHub -> FeaturedTab(
            onItemClick = onItemClick,
            onOpenAll = { nav.push(Page.All) },
            onOpenUrl = { url, title -> onOpenUrl(url, title, aihotLabel) },
            onBack = onBack,
            reselectSignal = 0,
            listState = listStates.forPage(page)
        )
        // 信息源(Hub 浏览区)二级页:聚合 8 源(可拖拽自定义顺序)。
        // 单回调 onOpen(key) 按源 key 分发到各 Page;key 来自 SourceKeys。
        Page.Sources -> SourcesScreen(
            onBack = onBack,
            onOpen = { key ->
                when (key) {
                    SourceKeys.HACKERNEWS -> nav.push(Page.HackerNews)
                    SourceKeys.GITHUB_TRENDING -> nav.push(Page.GitHubTrending)
                    SourceKeys.OPENAI_ANTHROPIC_NEWS -> nav.push(Page.OpenAiAnthropicNews)
                    SourceKeys.HUGGINGFACE_PAPERS -> nav.push(Page.HuggingFacePapers)
                    SourceKeys.PRODUCTHUNT -> nav.push(Page.ProductHunt)
                    SourceKeys.RUNDOWN_AI -> nav.push(Page.RundownAi)
                    SourceKeys.AIHOT_FEATURED -> nav.push(Page.FeaturedHub)
                    SourceKeys.STORMZHANG_AI -> nav.push(Page.StormzhangAiNews)
                    else -> Unit
                }
            }
        )
        Page.BrowseHistory -> BrowseHistoryScreen(
            repo = env.browseHistoryRepo,
            onBack = onBack,
            onOpenUrl = { url, title, source -> onOpenUrl(url, title, source) },
            listState = listStates.forPage(page)
        )
        Page.Favorites -> FavoritesScreen(
            repo = env.favoritesRepo,
            onBack = onBack,
            onOpenUrl = { url, title, source -> onOpenUrl(url, title, source) },
            listState = listStates.forPage(page)
        )
        // 历史摘要:日期列表(history 索引)→ 当日全源摘要卡页(复用摘要卡片)。
        // 纯归档语义,不参与 SourceMode 切换;卡片无「查看完整列表」出口。
        Page.SummaryArchive -> SummaryArchiveScreen(
            onSelectDate = { nav.push(Page.SummaryDate(it)) },
            onBack = onBack,
            listState = listStates.forPage(page)
        )
        is Page.SummaryDate -> SummaryDateScreen(
            date = page.date,
            onBack = onBack,
            onOpenUrl = { url, title, source -> onOpenUrl(url, title, source) },
            pagerState = pagerStates.forPagePager(page)
        )
        // 历史总览:日期列表(overview_history 索引)→ 当日总览页(复用总览内容)。
        // 纯归档语义;二级页无下拉刷新。
        Page.OverviewArchive -> OverviewArchiveScreen(
            onSelectDate = { nav.push(Page.OverviewDate(it)) },
            onBack = onBack,
            listState = listStates.forPage(page)
        )
        is Page.OverviewDate -> OverviewDateScreen(
            date = page.date,
            onBack = onBack,
            onOpenUrl = { url, title, source -> onOpenUrl(url, title, source) },
            listState = listStates.forPage(page)
        )
        // 历史热词:日期列表(trends_history 索引)→ 当日热词榜页(复用趋势内容)。
        // 纯归档语义;二级页无下拉刷新。
        Page.TrendsArchive -> TrendsArchiveScreen(
            onSelectDate = { nav.push(Page.TrendsDate(it)) },
            onBack = onBack,
            listState = listStates.forPage(page)
        )
        is Page.TrendsDate -> TrendsDateScreen(
            date = page.date,
            onBack = onBack,
            onOpenUrl = { url, title, source -> onOpenUrl(url, title, source) },
            listState = listStates.forPage(page)
        )
        // 趋势词云:读根级独立文件 trends_cloud.json(专用 VM),纯 Canvas 可视化页,
        // 无列表滚动状态(不上提 listState)。
        Page.TrendsCloud -> TrendsCloudScreen(onBack = onBack)
    }
}
