package com.example.aihot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.example.aihot.data.AppDatabase
import com.example.aihot.data.BrowseHistoryRepository
import com.example.aihot.data.HackerNewsStory
import com.example.aihot.data.NewsItem
import com.example.aihot.data.TranslationConfigStore
import com.example.aihot.data.source.SourceMode
import com.example.aihot.ui.more.SettingsStore
import com.example.aihot.ui.NewsDetailScreen
import com.example.aihot.ui.BrowseHistoryViewModel
import com.example.aihot.ui.components.AppBottomBar
import com.example.aihot.ui.components.AppTab
import com.example.aihot.ui.daily.DailyArchiveScreen
import com.example.aihot.ui.daily.DailyDateScreen
import com.example.aihot.ui.daily.DailyScreen
import com.example.aihot.ui.items.HackerNewsCommentsScreen
import com.example.aihot.ui.items.HackerNewsScreen
import com.example.aihot.ui.items.GitHubTrendingScreen
import com.example.aihot.ui.items.BrowseHistoryScreen
import com.example.aihot.ui.items.HuggingFacePapersScreen
import com.example.aihot.ui.items.LinuxDoHotScreen
import com.example.aihot.ui.items.StormzhangAiNewsScreen
import com.example.aihot.ui.items.SearchScreen
import com.example.aihot.ui.more.AboutScreen
import com.example.aihot.ui.more.MoreScreen
import com.example.aihot.ui.more.SettingsScreen
import com.example.aihot.ui.more.FontChoice
import com.example.aihot.ui.more.ThemeMode
import com.example.aihot.ui.summary.SummaryScreen
import com.example.aihot.ui.tabs.AllTab
import com.example.aihot.ui.tabs.FeaturedTab
import com.example.aihot.ui.anim.PageNavStyle
import com.example.aihot.ui.anim.pageTransition
import com.example.aihot.ui.theme.AIHotTheme
import com.example.aihot.ui.webview.WebViewScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AIHotApp() }
    }
}

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
private sealed interface Page {
    /**
     * 该页面的转场风格,由 [pageTransition] 统一消费。
     *
     * 默认 PUSH(横向推入),绝大多数二级页无需单独声明。
     * 仅 Web 含 AndroidView(WebView),位移会撕裂 → override 为 FADE(纯淡入淡出)。
     * 其余页(含 Detail)是纯 Compose,正常 PUSH 即可。
     */
    val navStyle: PageNavStyle get() = PageNavStyle.PUSH

    data class Detail(val item: NewsItem) : Page
    data class Web(val url: String, val title: String = "加载中…") : Page {
        override val navStyle = PageNavStyle.FADE
    }
    data object DailyArchive : Page
    data class DailyDate(val date: String) : Page
    /** 全部动态 —— 原为独立 tab,现改为从精选页 push 进入的二级页。 */
    data object All : Page
    /** AI 日报 —— 原为独立 tab,现改为从「全部」页 push 进入的二级页。 */
    data object Daily : Page
    data object Search : Page
    data object Settings : Page
    data object About : Page
    data object HackerNews : Page
    data class HackerNewsComments(val story: HackerNewsStory) : Page
    data object GitHubTrending : Page
    data object LinuxDo : Page
    data object StormzhangAiNews : Page
    data object HuggingFacePapers : Page
    data object BrowseHistory : Page
}

/**
 * 把单个 [Page] 写入 / 读出 [Bundle]。tag(键 "t")区分子类型;
 * NewsItem / HackerNewsStory 已是 Parcelable,可直接 putParcelable。
 */
private fun Page.toBundle(): Bundle = Bundle().apply {
    when (this@toBundle) {
        is Page.Detail -> { putString("t", "Detail"); putParcelable("item", item) }
        is Page.Web -> { putString("t", "Web"); putString("url", url); putString("title", title) }
        is Page.DailyDate -> { putString("t", "DailyDate"); putString("date", date) }
        is Page.HackerNewsComments -> { putString("t", "HNComments"); putParcelable("story", story) }
        is Page.DailyArchive -> putString("t", "DailyArchive")
        is Page.All -> putString("t", "All")
        is Page.Daily -> putString("t", "Daily")
        is Page.Search -> putString("t", "Search")
        is Page.Settings -> putString("t", "Settings")
        is Page.About -> putString("t", "About")
        is Page.HackerNews -> putString("t", "HackerNews")
        is Page.GitHubTrending -> putString("t", "GitHubTrending")
        is Page.LinuxDo -> putString("t", "LinuxDo")
        is Page.StormzhangAiNews -> putString("t", "StormzhangAiNews")
        is Page.HuggingFacePapers -> putString("t", "HuggingFacePapers")
        is Page.BrowseHistory -> putString("t", "BrowseHistory")
    }
}

@Suppress("DEPRECATION")
private fun pageFromBundle(b: Bundle): Page? {
    return when (b.getString("t")) {
        "Detail" -> b.getParcelable<NewsItem>("item")?.let { Page.Detail(it) }
        "Web" -> Page.Web(b.getString("url") ?: "", b.getString("title") ?: "加载中…")
        "DailyDate" -> b.getString("date")?.let { Page.DailyDate(it) }
        "HNComments" -> b.getParcelable<HackerNewsStory>("story")?.let { Page.HackerNewsComments(it) }
        "DailyArchive" -> Page.DailyArchive
        "All" -> Page.All
        "Daily" -> Page.Daily
        "Search" -> Page.Search
        "Settings" -> Page.Settings
        "About" -> Page.About
        "HackerNews" -> Page.HackerNews
        "GitHubTrending" -> Page.GitHubTrending
        "LinuxDo" -> Page.LinuxDo
        "StormzhangAiNews" -> Page.StormzhangAiNews
        "HuggingFacePapers" -> Page.HuggingFacePapers
        "BrowseHistory" -> Page.BrowseHistory
        else -> null
    }
}

/**
 * 导航栈持久化 Saver:把 Map<AppTab, List<Page>> 存进一个 Bundle(Parcelable,
 * 可直接被 rememberSaveable 的 autoSaver 接管,避免 Serializable 容器混入
 * Parcelable 元素的兼容问题)。每个 tab 一个 key,值为 ArrayList<Bundle>。
 */
private val pageStacksSaver = androidx.compose.runtime.saveable.Saver<
    Map<AppTab, List<Page>>, Bundle
>(
    save = { stacks ->
        Bundle().apply {
            stacks.forEach { (tab, pages) ->
                val arr = arrayListOf<Bundle>()
                pages.forEach { arr += it.toBundle() }
                putParcelableArrayList(tab.name, arr)
            }
        }
    },
    restore = { b ->
        @Suppress("DEPRECATION")
        AppTab.entries.mapNotNull { tab ->
            val arr = b.getParcelableArrayList<Bundle>(tab.name) ?: return@mapNotNull null
            tab to arr.mapNotNull { pageFromBundle(it) }
        }.toMap()
    }
)

/**
 * App 顶层路由 —— 多栈底部导航。
 *
 * 模型:
 *  - currentTab: 当前选中的 4 个根 tab 之一
 *  - pageStacks: 每个 tab 独立的二级页栈(栈空 = 处于根)
 *
 * 行为:
 *  - 点底栏切 tab:仅换 currentTab(各 tab 二级栈保留)
 *  - 进入二级页:push 到当前 tab 的栈
 *  - 返回:pop 当前 tab 栈;栈空时交系统默认退出 App
 *  - 底栏始终常驻(见 AIHotApp 内说明)
 */
@Composable
fun AIHotApp() {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    // 偏好存储(进程级单例):显示偏好(主题/字体) + 翻译配置,均基于 DataStore 持久化。
    val settingsStore = remember { SettingsStore(appContext) }
    val configStore = remember { TranslationConfigStore(appContext) }

    // 浏览历史仓库(进程级单例):基于 Room,记录所有通过 openUrl 打开的网页。
    val browseHistoryRepo = remember {
        BrowseHistoryRepository(AppDatabase.get(appContext).browseHistoryDao())
    }

    // 主题模式 + 字体族:订阅持久化 Flow。Flow 首帧前用默认值(System),
    // 读到持久化值后自动切换。设置页改值时写入 store,Flow 回推新值刷新。
    val displayPrefs by settingsStore.prefsFlow.collectAsStateWithLifecycle(
        initialValue = SettingsStore.DisplayPrefs()
    )
    val themeMode = displayPrefs.themeMode
    val fontChoice = displayPrefs.fontChoice
    val sourceMode = displayPrefs.sourceMode
    val onSelectTheme: (ThemeMode) -> Unit = { scope.launch { settingsStore.updateTheme(it) } }
    val onSelectFont: (FontChoice) -> Unit = { scope.launch { settingsStore.updateFont(it) } }
    val onSelectSource: (SourceMode) -> Unit = { scope.launch { settingsStore.updateSourceMode(it) } }

    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    // 当前 tab + 每个 tab 的二级页栈
    var currentTab by rememberSaveable { mutableStateOf(AppTab.Summary) }
    // 用 rememberSaveable 持久化导航栈,转屏/进程被杀后仍可恢复(需自定义 Saver,
    // 因 Page 含业务对象、AppTab 是 enum,默认 Bundle 无法直接存 Map)。
    var pageStacks by rememberSaveable(stateSaver = pageStacksSaver) {
        mutableStateOf(emptyMap<AppTab, List<Page>>())
    }
    // 转场方向:push 时为 false(前进),pop 时为 true(返回)。
    // transitionSpec 据此决定 PUSH 类页面的位移方向(返回时方向镜像)。
    var isNavigatingBack by remember { mutableStateOf(false) }

    val currentPages: List<Page> = pageStacks[currentTab].orEmpty()
    val isRoot: Boolean = currentPages.isEmpty()

    // 进入二级页:push 到当前 tab 栈
    val push: (Page) -> Unit = { page ->
        isNavigatingBack = false
        pageStacks = pageStacks.toMutableMap().apply {
            this[currentTab] = (this[currentTab].orEmpty()) + page
        }
    }
    // 弹当前 tab 栈顶
    val pop: () -> Unit = {
        if (pageStacks[currentTab].orEmpty().isNotEmpty()) {
            isNavigatingBack = true
            pageStacks = pageStacks.toMutableMap().apply {
                this[currentTab] = (this[currentTab].orEmpty()).dropLast(1)
            }
        }
    }
    // 切 tab
    val selectTab: (AppTab) -> Unit = { tab ->
        if (tab != currentTab) {
            isNavigatingBack = false
            currentTab = tab
        }
    }

    // 统一"打开内置 WebView"。
    //
    // 记录浏览历史:在此唯一入口拦截,全 App 覆盖。source 为来源标签
    // ("GitHub Trending"/"日报"/"AI HOT"…),由各调用点显式传入,可空。
    val openUrl: (String, String, String?) -> Unit = { url, title, source ->
        scope.launch { browseHistoryRepo.record(url, title, source) }
        push(Page.Web(url, title))
    }

    // 网页标题回写:WebView 加载完成后拿到真实标题,更新历史记录(而非占位"加载中…")。
    // 不更新 visitedAt,避免回写把条目顶到最前。
    val onTitleResolved: (String, String) -> Unit = { url, resolvedTitle ->
        scope.launch { browseHistoryRepo.updateTitle(url, resolvedTitle) }
    }

    // 系统返回键:当前 tab 栈非空时 pop
    BackHandler(enabled = !isRoot) { pop() }

    // 当前屏幕:根(tab) 或 二级页。用作 AnimatedContent 的 key。
    val screen: Screen = if (isRoot) Screen.Root(currentTab) else Screen.Secondary(currentPages.last())

    AIHotTheme(
        darkTheme = darkTheme,
        fontFamily = if (fontChoice == FontChoice.System) null else fontChoice.fontFamily
    ) {
        // 浮动药丸底栏架构:不再用 Scaffold bottomBar 槽,改用 Box 叠层。
        //  - 内容区 edge-to-edge 全屏,内层各 Tab 的 Scaffold 负责自己的状态栏 inset;
        //    这里只补 statusBarsPadding 防止 AnimatedContent 与系统栏重叠错位。
        //  - 底栏作为 overlay 浮在内容上(BottomCenter + navigationBarsPadding + 16dp 距底),
        //    由调用方在列表 contentPadding 留出空间避免末项被遮挡。
        Surface {
            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        // 转场策略由页面自身声明的 navStyle + 导航方向驱动,集中配置于 pageTransition()。
                        // 加新页面时只需在该 Page 上标注 PageNavStyle,无需改这里。
                        pageTransition(
                            enter = targetState.navStyle,
                            exit = initialState.navStyle,
                            back = isNavigatingBack
                        )
                    },
                    contentAlignment = Alignment.TopCenter,
                    label = "nav",
                    modifier = Modifier.fillMaxSize()
                ) { s ->
                    when (s) {
                        is Screen.Root -> TabRoot(
                            tab = s.tab,
                            onItemClick = { push(Page.Detail(it)) },
                            onOpenAll = { push(Page.All) },
                            onOpenHackerNews = { push(Page.HackerNews) },
                            onOpenGitHubTrending = { push(Page.GitHubTrending) },
                            onOpenLinuxDo = { push(Page.LinuxDo) },
                            onOpenStormzhangAiNews = { push(Page.StormzhangAiNews) },
                            onOpenHuggingFacePapers = { push(Page.HuggingFacePapers) },
                            onOpenBrowseHistory = { push(Page.BrowseHistory) },
                            onOpenUrl = openUrl,
                            onOpenSettings = { push(Page.Settings) },
                            onOpenAbout = { push(Page.About) }
                        )
                        is Screen.Secondary -> PageView(
                            page = s.page,
                            themeMode = themeMode,
                            onSelectTheme = onSelectTheme,
                            fontChoice = fontChoice,
                            onSelectFont = onSelectFont,
                            sourceMode = sourceMode,
                            onSelectSource = onSelectSource,
                            onBack = pop,
                            onItemClick = { push(Page.Detail(it)) },
                            onOpenDaily = { push(Page.Daily) },
                            onOpenSearch = { push(Page.Search) },
                            onSelectDate = { push(Page.DailyDate(it)) },
                            onOpenArchive = { push(Page.DailyArchive) },
                            onOpenComments = { push(Page.HackerNewsComments(it)) },
                            onOpenUrl = openUrl,
                            onTitleResolved = onTitleResolved,
                            onOpenSettings = { push(Page.Settings) },
                            configStore = configStore,
                            browseHistoryRepo = browseHistoryRepo,
                            darkTheme = darkTheme
                        )
                    }
                }

                // 浮动药丸底栏:根页显示,进入二级页时向下滑出。
                // 二级页不显示底栏(沉浸感)。
                AnimatedVisibility(
                    visible = isRoot,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it }),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp)
                ) {
                    AppBottomBar(current = currentTab, onSelect = selectTab)
                }
            }
        }
    }
}

/**
 * 顶层屏幕标识:根(tab) 或 二级页。供 [AnimatedContent] 区分转场策略。
 *
 * [navStyle] 让 [transitionSpec] 直接查表,无需类型判断:
 * 根页统一为 [PageNavStyle.NONE],二级页沿用其 [Page.navStyle]。
 */
private sealed interface Screen {
    val navStyle: PageNavStyle

    data class Root(val tab: AppTab) : Screen {
        override val navStyle = PageNavStyle.NONE
    }
    data class Secondary(val page: Page) : Screen {
        override val navStyle get() = page.navStyle
    }
}

/** 渲染某个 tab 的根屏幕。 */
@Composable
private fun TabRoot(
    tab: AppTab,
    onItemClick: (NewsItem) -> Unit,
    onOpenAll: () -> Unit,
    onOpenHackerNews: () -> Unit,
    onOpenGitHubTrending: () -> Unit,
    onOpenLinuxDo: () -> Unit,
    onOpenStormzhangAiNews: () -> Unit,
    onOpenHuggingFacePapers: () -> Unit,
    onOpenBrowseHistory: () -> Unit,
    onOpenUrl: (String, String, String?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit
) {
    when (tab) {
        AppTab.Featured -> FeaturedTab(
            onItemClick = onItemClick,
            // 「全部 ›」入口:跳转到全部动态二级页
            onOpenAll = onOpenAll,
            // 今日热点卡片链接到 AI HOT 阅读页,标注来源 "AI HOT"
            onOpenUrl = { url, title -> onOpenUrl(url, title, "AI HOT") }
        )
        AppTab.Summary -> SummaryScreen(
            onOpenHackerNews = onOpenHackerNews,
            onOpenGitHubTrending = onOpenGitHubTrending,
            onOpenHuggingFacePapers = onOpenHuggingFacePapers,
            onOpenStormzhangAiNews = onOpenStormzhangAiNews,
            onOpenSettings = onOpenSettings
        )
        AppTab.More -> MoreScreen(
            onOpenHackerNews = onOpenHackerNews,
            onOpenGitHubTrending = onOpenGitHubTrending,
            onOpenLinuxDo = onOpenLinuxDo,
            onOpenStormzhangAiNews = onOpenStormzhangAiNews,
            onOpenHuggingFacePapers = onOpenHuggingFacePapers,
            onOpenBrowseHistory = onOpenBrowseHistory,
            onOpenSettings = onOpenSettings,
            onOpenAbout = onOpenAbout
        )
    }
}

/** 渲染某个二级页。 */
@Composable
private fun PageView(
    page: Page,
    themeMode: ThemeMode,
    onSelectTheme: (ThemeMode) -> Unit,
    fontChoice: FontChoice,
    onSelectFont: (FontChoice) -> Unit,
    sourceMode: SourceMode,
    onSelectSource: (SourceMode) -> Unit,
    onBack: () -> Unit,
    onItemClick: (NewsItem) -> Unit,
    onOpenDaily: () -> Unit,
    onOpenSearch: () -> Unit,
    onSelectDate: (String) -> Unit,
    onOpenArchive: () -> Unit,
    onOpenComments: (HackerNewsStory) -> Unit,
    onOpenUrl: (String, String, String?) -> Unit,
    onTitleResolved: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    configStore: TranslationConfigStore,
    browseHistoryRepo: BrowseHistoryRepository,
    darkTheme: Boolean = false
) {
    when (page) {
        is Page.Detail -> NewsDetailScreen(
            item = page.item,
            onBack = onBack,
            // 适配:Detail 页打开的链接来自 AI HOT 详情,标注 "AI HOT"
            onOpenUrl = { url, title -> onOpenUrl(url, title, "AI HOT") }
        )
        is Page.Web -> WebViewScreen(
            url = page.url,
            title = page.title,
            darkTheme = darkTheme,
            onBack = onBack,
            onTitleResolved = onTitleResolved
        )
        Page.All -> AllTab(
            onItemClick = onItemClick,
            onBack = onBack,
            onOpenDaily = onOpenDaily,
            onOpenSearch = onOpenSearch
        )
        Page.Daily -> DailyScreen(
            onItemClick = onItemClick,
            onOpenArchive = onOpenArchive,
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, "日报") }
        )
        Page.DailyArchive -> DailyArchiveScreen(
            onSelectDate = onSelectDate,
            onBack = onBack
        )
        is Page.DailyDate -> DailyDateScreen(
            date = page.date,
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, "日报") }
        )
        Page.Search -> SearchScreen(
            onBack = onBack,
            onItemClick = onItemClick
        )
        Page.Settings -> SettingsScreen(
            themeMode = themeMode,
            onSelectTheme = onSelectTheme,
            fontChoice = fontChoice,
            onSelectFont = onSelectFont,
            sourceMode = sourceMode,
            onSelectSource = onSelectSource,
            configStore = configStore,
            onBack = onBack
        )
        Page.About -> AboutScreen(onBack = onBack)
        Page.HackerNews -> HackerNewsScreen(
            onBack = onBack,
            onOpenComments = onOpenComments,
            onOpenSettings = onOpenSettings
        )
        is Page.HackerNewsComments -> HackerNewsCommentsScreen(
            story = page.story,
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, "HackerNews") },
            onOpenSettings = onOpenSettings
        )
        Page.GitHubTrending -> GitHubTrendingScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, "GitHub Trending") },
            onOpenSettings = onOpenSettings
        )
        Page.LinuxDo -> LinuxDoHotScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, "LinuxDo") }
        )
        Page.StormzhangAiNews -> StormzhangAiNewsScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, "stormzhang AI") }
        )
        Page.HuggingFacePapers -> HuggingFacePapersScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, "HuggingFace") },
            onOpenSettings = onOpenSettings
        )
        Page.BrowseHistory -> BrowseHistoryScreen(
            repo = browseHistoryRepo,
            onBack = onBack,
            onOpenUrl = { url, title, source -> onOpenUrl(url, title, source) }
        )
    }
}
