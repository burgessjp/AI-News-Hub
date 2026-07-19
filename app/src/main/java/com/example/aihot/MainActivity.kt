package com.example.aihot

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import com.example.aihot.data.AppDatabase
import com.example.aihot.data.BrowseHistoryRepository
import com.example.aihot.data.HackerNewsStory
import com.example.aihot.data.NewsItem
import com.example.aihot.data.AiConfig
import com.example.aihot.data.AiConfigStore
import com.example.aihot.data.CacheManager
import com.example.aihot.data.AiUsageStore
import com.example.aihot.data.SummaryRepository
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
import com.example.aihot.ui.items.ProductHuntScreen
import com.example.aihot.ui.items.RundownAiScreen
import com.example.aihot.ui.items.StormzhangAiNewsScreen
import com.example.aihot.ui.items.SearchScreen
import com.example.aihot.ui.more.AboutScreen
import com.example.aihot.ui.more.AiServiceScreen
import com.example.aihot.ui.more.FontScale
import com.example.aihot.ui.more.MoreScreen
import com.example.aihot.ui.more.SettingsScreen
import com.example.aihot.ui.more.SourcesScreen
import com.example.aihot.ui.more.FontChoice
import com.example.aihot.ui.more.ThemeMode
import com.example.aihot.ui.overview.OverviewScreen
import com.example.aihot.ui.summary.SummaryArchiveScreen
import com.example.aihot.ui.summary.SummaryDateScreen
import com.example.aihot.ui.summary.SummaryScreen
import com.example.aihot.ui.tabs.AllTab
import com.example.aihot.ui.tabs.FeaturedTab
import com.example.aihot.ui.anim.PageNavStyle
import com.example.aihot.ui.anim.pageTransition
import com.example.aihot.ui.anim.predictivePopTransition
import com.example.aihot.ui.theme.AIHotTheme
import com.example.aihot.ui.webview.WebViewScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        /** Intent extra:为 true 时启动后自动进入「设置」页(系统选中翻译的「去设置」入口)。 */
        const val EXTRA_OPEN_SETTINGS = "com.example.aihot.extra.OPEN_SETTINGS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val openSettings = savedInstanceState == null &&
            intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)
        setContent { AIHotApp(openSettingsOnLaunch = openSettings) }
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
    /** AI 服务 —— 服务商/模型/翻译开关 + 用量统计,从「更多」页进入。 */
    data object AiService : Page
    data object About : Page
    data object HackerNews : Page
    data class HackerNewsComments(val story: HackerNewsStory) : Page
    data object GitHubTrending : Page
    data object LinuxDo : Page
    data object StormzhangAiNews : Page
    data object HuggingFacePapers : Page
    data object ProductHunt : Page
    data object RundownAi : Page
    /** AIHot 精选 —— 原为独立根 tab,现改为从「更多」页进入的二级页(复用 FeaturedTab)。 */
    data object FeaturedHub : Page
    /** 信息源(Sources) —— Hub 浏览区独立页,聚合 7 个第三方源 + AIHot 精选入口。从「更多」页进入。 */
    data object Sources : Page
    data object BrowseHistory : Page
    /** 历史摘要 —— 可选日期列表(归档 history 索引),从「更多」页进入。 */
    data object SummaryArchive : Page
    /** 历史摘要 —— 指定日期的 7 源摘要卡页(复用摘要卡片实现)。 */
    data class SummaryDate(val date: String) : Page
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
        is Page.AiService -> putString("t", "AiService")
        is Page.About -> putString("t", "About")
        is Page.HackerNews -> putString("t", "HackerNews")
        is Page.GitHubTrending -> putString("t", "GitHubTrending")
        is Page.LinuxDo -> putString("t", "LinuxDo")
        is Page.StormzhangAiNews -> putString("t", "StormzhangAiNews")
        is Page.HuggingFacePapers -> putString("t", "HuggingFacePapers")
        is Page.ProductHunt -> putString("t", "ProductHunt")
        is Page.RundownAi -> putString("t", "RundownAi")
        is Page.FeaturedHub -> putString("t", "FeaturedHub")
        is Page.Sources -> putString("t", "Sources")
        is Page.BrowseHistory -> putString("t", "BrowseHistory")
        is Page.SummaryArchive -> putString("t", "SummaryArchive")
        is Page.SummaryDate -> { putString("t", "SummaryDate"); putString("date", date) }
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
        "AiService" -> Page.AiService
        "About" -> Page.About
        "HackerNews" -> Page.HackerNews
        "GitHubTrending" -> Page.GitHubTrending
        "LinuxDo" -> Page.LinuxDo
        "StormzhangAiNews" -> Page.StormzhangAiNews
        "HuggingFacePapers" -> Page.HuggingFacePapers
        "ProductHunt" -> Page.ProductHunt
        "RundownAi" -> Page.RundownAi
        "FeaturedHub" -> Page.FeaturedHub
        "Sources" -> Page.Sources
        "BrowseHistory" -> Page.BrowseHistory
        "SummaryArchive" -> Page.SummaryArchive
        "SummaryDate" -> b.getString("date")?.let { Page.SummaryDate(it) }
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
 *  - currentTab: 当前选中的 3 个根 tab 之一(总览 / 摘要 / 更多)
 *  - pageStacks: 每个 tab 独立的二级页栈(栈空 = 处于根)
 *
 * 行为:
 *  - 点底栏切 tab:仅换 currentTab(各 tab 二级栈保留)
 *  - 进入二级页:push 到当前 tab 的栈
 *  - 返回:pop 当前 tab 栈;栈空时交系统默认退出 App
 *  - 底栏始终常驻(见 AIHotApp 内说明)
 */
@Composable
fun AIHotApp(openSettingsOnLaunch: Boolean = false) {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    // 偏好存储(进程级单例):显示偏好(主题/字体) + AI 服务配置,均基于 DataStore 持久化。
    val settingsStore = remember { SettingsStore(appContext) }
    val configStore = remember { AiConfigStore(appContext) }
    // AI 用量统计(与 AiConfigStore 同一 DataStore):设置页「用量与费用」区块数据源。
    val usageStore = remember { AiUsageStore(appContext) }

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
    val dynamicColor = displayPrefs.dynamicColor
    val fontChoice = displayPrefs.fontChoice
    val fontScale = displayPrefs.fontScale
    val sourceMode = displayPrefs.sourceMode
    // AI 服务全局配置:除设置页外,WebView 整页翻译也读取(开关/就绪态判定)
    val aiConfig by configStore.configFlow.collectAsStateWithLifecycle(
        initialValue = AiConfig()
    )
    val onSelectTheme: (ThemeMode) -> Unit = { scope.launch { settingsStore.updateTheme(it) } }
    val onToggleDynamicColor: (Boolean) -> Unit = { scope.launch { settingsStore.updateDynamicColor(it) } }
    val onSelectFont: (FontChoice) -> Unit = { scope.launch { settingsStore.updateFont(it) } }
    val onSelectFontScale: (FontScale) -> Unit = { scope.launch { settingsStore.updateFontScale(it) } }
    val onSelectSource: (SourceMode) -> Unit = { scope.launch { settingsStore.updateSourceMode(it) } }

    // 缓存占用(进入设置页时计算一次,清理后刷新)。0 表示未计算,UI 显示「< 1 KB」。
    var cacheSizeBytes by remember { mutableStateOf(0L) }
    // 首次组合计算一次;清理后由 onClearCache 内手动再算一次。
    LaunchedEffect(Unit) { cacheSizeBytes = CacheManager.sizeBytes(appContext) }
    val onClearCache: () -> Unit = {
        scope.launch {
            CacheManager.clear(appContext, browseHistoryRepo, settingsStore)
            cacheSizeBytes = CacheManager.sizeBytes(appContext)
        }
    }

    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    // 状态栏/导航栏图标外观跟随 App 实际暗色态(用户 ThemeMode 或系统暗色),
    // 而非 enableEdgeToEdge() 默认依据的系统 uiMode —— 否则用户强制浅色 / 深色时,
    // 图标颜色会与界面背景同色(如浅色背景配白字)。每次 darkTheme 重组即重下发。
    val statusBarView = LocalView.current
    if (!statusBarView.isInEditMode) {
        SideEffect {
            val window = (statusBarView.context as Activity).window
            val controller = WindowInsetsControllerCompat(window, statusBarView)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    // 当前 tab + 每个 tab 的二级页栈(默认「总览」——端侧 AI 当日分析,首屏即默认首页)
    var currentTab by rememberSaveable { mutableStateOf(AppTab.Overview) }
    // 用 rememberSaveable 持久化导航栈,转屏/进程被杀后仍可恢复(需自定义 Saver,
    // 因 Page 含业务对象、AppTab 是 enum,默认 Bundle 无法直接存 Map)。
    var pageStacks by rememberSaveable(stateSaver = pageStacksSaver) {
        mutableStateOf(emptyMap<AppTab, List<Page>>())
    }
    // 转场方向:push 时为 false(前进),pop 时为 true(返回)。
    // transitionSpec 据此决定 PUSH 类页面的位移方向(返回时方向镜像)。
    var isNavigatingBack by remember { mutableStateOf(false) }
    // 预测返回手势状态:收到首个手势事件后置 true,transitionSpec 切换为
    // predictivePopTransition(LinearEasing,动画进度与手指 1:1);
    // backSwipeEdge 记录手势起始边缘,决定退出页滑出方向。
    var seekMode by remember { mutableStateOf(false) }
    var backSwipeEdge by remember { mutableStateOf(BackEventCompat.EDGE_LEFT) }

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
    // 重击当前 tab 信号:已在根页时递增,根屏据此滚回顶部并刷新。
    var reselectTick by remember { mutableStateOf(0) }

    // 切 tab。重击当前 tab:栈非空 → 清空该栈回根页;已在根 → 发 reselect 信号
    //(滚顶 + 刷新,由各根屏自行消费)。
    val selectTab: (AppTab) -> Unit = { tab ->
        if (tab != currentTab) {
            isNavigatingBack = false
            currentTab = tab
        } else if (pageStacks[currentTab].orEmpty().isNotEmpty()) {
            isNavigatingBack = true
            pageStacks = pageStacks.toMutableMap().apply { this[currentTab] = emptyList() }
        } else {
            reselectTick++
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

    // 外部入口要求直达设置页(如系统选中翻译的「去设置」)
    LaunchedEffect(openSettingsOnLaunch) {
        if (openSettingsOnLaunch) push(Page.Settings)
    }

    // 当前屏幕:根(tab) 或 二级页。用作转场的 currentState/targetState。
    val screen: Screen = if (isRoot) Screen.Root(currentTab) else Screen.Secondary(currentPages.last())

    // 可寻址转场:普通导航 animateTo 补间;预测返回手势期间按进度 seekTo。
    val navTransitionState = remember { SeekableTransitionState(screen) }
    val navTransition = rememberTransition(navTransitionState, label = "nav")

    // 非手势导航(push / pop / 切 tab / 顶栏返回):screen 变化即补间到目标页。
    // 预测返回手势完成后的 pop 不会触发二次动画(此时 targetState 已是目标页)。
    LaunchedEffect(screen) {
        if (navTransitionState.currentState != screen &&
            navTransitionState.targetState != screen
        ) {
            navTransitionState.animateTo(screen)
        }
    }

    // 系统返回 + 预测返回手势(替换原根 BackHandler;本身是 OnBackPressedCallback。
    // WebView 内层 BackHandler 后组合、优先级更高,网页历史优先行为不变):
    // 手势进度实时 seek 到返回目标页;松手完成则播完剩余动画后 pop;中途取消则回弹。
    // 返回键触发时 progress 流零事件直接完成 → seekMode 保持 false,
    // 走 pageTransition 的 emphasized 返回动画(API < 34 同此路径)。
    PredictiveBackHandler(enabled = !isRoot) { progress ->
        val from = screen
        val to: Screen = if (currentPages.size > 1) {
            Screen.Secondary(currentPages[currentPages.lastIndex - 1])
        } else {
            Screen.Root(currentTab)
        }
        isNavigatingBack = true
        try {
            progress.collect { event ->
                // 首个手势事件到达才切 predictive 规格(LinearEasing 跟手);
                // 先于 seekTo 写入,保证 transition 创建时捕获新规格。
                seekMode = true
                backSwipeEdge = event.swipeEdge
                navTransitionState.seekTo(event.progress, to)
            }
            navTransitionState.animateTo(to)
            pop()
            seekMode = false
        } catch (e: CancellationException) {
            // 手势取消:先复位 seekMode(避免回弹期间新导航误用 predictive 规格),
            // 再回弹到当前页(NonCancellable 保证回弹动画不被取消打断)
            seekMode = false
            withContext(NonCancellable) { navTransitionState.animateTo(from) }
            throw e
        }
    }

    // 各列表/页码的滚动状态上提到此(转场层之上):AnimatedContent 换页会销毁页内
    // remember/rememberSaveable(实测 rememberPagerState 也随换页丢失),下层页重返
    // 组合时是全新状态 → 滚动位置/页码被重置。由本层持有即可跨 push/pop 存活;
    // 进程死亡后不保留(数据本身也会重拉,可接受)。
    // 注:「AIHot 精选」原为根 tab 时有独立的 featuredListState;改为二级页后
    // 走 pageListStates.forPage(Page.FeaturedHub),不再上提。
    val summaryPagerState = rememberPagerState(pageCount = { SummaryRepository.SOURCE_KEYS.size })
    // 总览 tab 的列表滚动状态(与 summaryPagerState 同层上提)
    val overviewListState = rememberLazyListState()
    // 二级页滚动状态:以 Page 值(data class,可作 key)索引,页面弹出后清理。
    val pageListStates = remember { mutableMapOf<Page, LazyListState>() }
    // 二级页 Pager 状态(历史摘要按日期页):与列表状态同上提、同清理。
    val pagePagerStates = remember { mutableMapOf<Page, PagerState>() }
    LaunchedEffect(pageStacks) {
        val alive = pageStacks.values.flatten().toSet()
        pageListStates.keys.removeAll { it !in alive }
        pagePagerStates.keys.removeAll { it !in alive }
    }

    AIHotTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        fontFamily = if (fontChoice == FontChoice.System) null else fontChoice.fontFamily,
        fontScale = fontScale.scale
    ) {
        // 浮动药丸底栏架构:不再用 Scaffold bottomBar 槽,改用 Box 叠层。
        //  - 内容区 edge-to-edge 全屏,内层各 Tab 的 Scaffold 负责自己的状态栏 inset;
        //    这里只补 statusBarsPadding 防止 AnimatedContent 与系统栏重叠错位。
        //  - 底栏作为 overlay 浮在内容上(BottomCenter + navigationBarsPadding + 16dp 距底),
        //    由调用方在列表 contentPadding 留出空间避免末项被遮挡。
        Surface {
            Box(modifier = Modifier.fillMaxSize()) {
                navTransition.AnimatedContent(
                    transitionSpec = {
                        // 转场策略由页面自身声明的 navStyle + 导航方向驱动,集中配置于
                        // pageTransition()/predictivePopTransition()。
                        // 加新页面时只需在该 Page 上标注 PageNavStyle,无需改这里。
                        // 预测返回手势期间(seekMode)切换为跟手的 predictive 规格。
                        if (seekMode) {
                            predictivePopTransition(
                                enter = targetState.navStyle,
                                exit = initialState.navStyle,
                                swipeEdge = backSwipeEdge
                            )
                        } else {
                            pageTransition(
                                enter = targetState.navStyle,
                                exit = initialState.navStyle,
                                back = isNavigatingBack
                            )
                        }
                    },
                    contentAlignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                ) { s ->
                    when (s) {
                        is Screen.Root -> TabRoot(
                            tab = s.tab,
                            reselectTick = reselectTick,
                            summaryPagerState = summaryPagerState,
                            overviewListState = overviewListState,
                            onItemClick = { push(Page.Detail(it)) },
                            onOpenAll = { push(Page.All) },
                            onOpenHackerNews = { push(Page.HackerNews) },
                            onOpenGitHubTrending = { push(Page.GitHubTrending) },
                            onOpenLinuxDo = { push(Page.LinuxDo) },
                            onOpenStormzhangAiNews = { push(Page.StormzhangAiNews) },
                            onOpenHuggingFacePapers = { push(Page.HuggingFacePapers) },
                            onOpenProductHunt = { push(Page.ProductHunt) },
                            onOpenRundownAi = { push(Page.RundownAi) },
                            onOpenFeaturedHub = { push(Page.FeaturedHub) },
                            onOpenSources = { push(Page.Sources) },
                            onOpenBrowseHistory = { push(Page.BrowseHistory) },
                            onOpenSummaryArchive = { push(Page.SummaryArchive) },
                            onOpenUrl = openUrl,
                            onOpenSettings = { push(Page.Settings) },
                            onOpenAiService = { push(Page.AiService) },
                            onOpenAbout = { push(Page.About) }
                        )
                        is Screen.Secondary -> PageView(
                            page = s.page,
                            pageListStates = pageListStates,
                            pagePagerStates = pagePagerStates,
                            themeMode = themeMode,
                            onSelectTheme = onSelectTheme,
                            dynamicColor = dynamicColor,
                            onToggleDynamicColor = onToggleDynamicColor,
                            fontChoice = fontChoice,
                            onSelectFont = onSelectFont,
                            fontScale = fontScale,
                            onSelectFontScale = onSelectFontScale,
                            sourceMode = sourceMode,
                            onSelectSource = onSelectSource,
                            onBack = pop,
                            onItemClick = { push(Page.Detail(it)) },
                            // 精选二级页头部的「全部 ›」入口 → 全部动态二级页
                            onOpenAll = { push(Page.All) },
                            onOpenDaily = { push(Page.Daily) },
                            onOpenSearch = { push(Page.Search) },
                            onSelectDate = { push(Page.DailyDate(it)) },
                            onSelectSummaryDate = { push(Page.SummaryDate(it)) },
                            onOpenArchive = { push(Page.DailyArchive) },
                            onOpenComments = { push(Page.HackerNewsComments(it)) },
                            onOpenUrl = openUrl,
                            onTitleResolved = onTitleResolved,
                            onOpenSettings = { push(Page.Settings) },
                            onOpenHackerNews = { push(Page.HackerNews) },
                            onOpenGitHubTrending = { push(Page.GitHubTrending) },
                            onOpenLinuxDo = { push(Page.LinuxDo) },
                            onOpenStormzhangAiNews = { push(Page.StormzhangAiNews) },
                            onOpenHuggingFacePapers = { push(Page.HuggingFacePapers) },
                            onOpenProductHunt = { push(Page.ProductHunt) },
                            onOpenRundownAi = { push(Page.RundownAi) },
                            onOpenFeaturedHub = { push(Page.FeaturedHub) },
                            configStore = configStore,
                            aiConfig = aiConfig,
                            usageStore = usageStore,
                            browseHistoryRepo = browseHistoryRepo,
                            cacheSizeBytes = cacheSizeBytes,
                            onClearCache = onClearCache,
                            darkTheme = darkTheme
                        )
                    }
                }

                // 浮动药丸底栏:根页显示,进入二级页时向下滑出。
                // 二级页不显示底栏(沉浸感)。
                // 可见性跟随转场目标而非仅 isRoot:预测返回手势一开始 seek 向根页,
                // 底栏即随转场滑入;手势取消则随目标复原滑出——不再在 pop 完成后突兀出现。
                AnimatedVisibility(
                    visible = isRoot || navTransitionState.targetState is Screen.Root,
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
    reselectTick: Int,
    summaryPagerState: androidx.compose.foundation.pager.PagerState,
    overviewListState: LazyListState,
    onItemClick: (NewsItem) -> Unit,
    onOpenAll: () -> Unit,
    onOpenHackerNews: () -> Unit,
    onOpenGitHubTrending: () -> Unit,
    onOpenLinuxDo: () -> Unit,
    onOpenStormzhangAiNews: () -> Unit,
    onOpenHuggingFacePapers: () -> Unit,
    onOpenProductHunt: () -> Unit,
    onOpenRundownAi: () -> Unit,
    onOpenFeaturedHub: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenBrowseHistory: () -> Unit,
    onOpenSummaryArchive: () -> Unit,
    onOpenUrl: (String, String, String?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAiService: () -> Unit,
    onOpenAbout: () -> Unit
) {
    when (tab) {
        AppTab.Overview -> OverviewScreen(
            onOpenUrl = onOpenUrl,
            onOpenAiService = onOpenAiService,
            listState = overviewListState,
            reselectSignal = reselectTick
        )
        AppTab.Summary -> SummaryScreen(
            reselectSignal = reselectTick,
            pagerState = summaryPagerState,
            onOpenHackerNews = onOpenHackerNews,
            onOpenGitHubTrending = onOpenGitHubTrending,
            onOpenHuggingFacePapers = onOpenHuggingFacePapers,
            onOpenStormzhangAiNews = onOpenStormzhangAiNews,
            onOpenProductHunt = onOpenProductHunt,
            onOpenRundownAi = onOpenRundownAi,
            onOpenFeaturedHub = onOpenFeaturedHub
        )
        AppTab.More -> MoreScreen(
            onOpenSources = onOpenSources,
            onOpenBrowseHistory = onOpenBrowseHistory,
            onOpenSummaryArchive = onOpenSummaryArchive,
            onOpenSettings = onOpenSettings,
            onOpenAiService = onOpenAiService,
            onOpenAbout = onOpenAbout
        )
    }
}

/** 渲染某个二级页。 */
@Composable
private fun PageView(
    page: Page,
    pageListStates: MutableMap<Page, LazyListState>,
    pagePagerStates: MutableMap<Page, PagerState>,
    themeMode: ThemeMode,
    onSelectTheme: (ThemeMode) -> Unit,
    dynamicColor: Boolean,
    onToggleDynamicColor: (Boolean) -> Unit,
    fontChoice: FontChoice,
    onSelectFont: (FontChoice) -> Unit,
    fontScale: FontScale,
    onSelectFontScale: (FontScale) -> Unit,
    sourceMode: SourceMode,
    onSelectSource: (SourceMode) -> Unit,
    onBack: () -> Unit,
    onItemClick: (NewsItem) -> Unit,
    onOpenAll: () -> Unit,
    onOpenDaily: () -> Unit,
    onOpenSearch: () -> Unit,
    onSelectDate: (String) -> Unit,
    onSelectSummaryDate: (String) -> Unit,
    onOpenArchive: () -> Unit,
    onOpenComments: (HackerNewsStory) -> Unit,
    onOpenUrl: (String, String, String?) -> Unit,
    onTitleResolved: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    // 信息源(Sources)二级页内的 8 个源入口回调
    onOpenHackerNews: () -> Unit,
    onOpenGitHubTrending: () -> Unit,
    onOpenLinuxDo: () -> Unit,
    onOpenStormzhangAiNews: () -> Unit,
    onOpenHuggingFacePapers: () -> Unit,
    onOpenProductHunt: () -> Unit,
    onOpenRundownAi: () -> Unit,
    onOpenFeaturedHub: () -> Unit,
    configStore: AiConfigStore,
    aiConfig: AiConfig,
    usageStore: AiUsageStore,
    browseHistoryRepo: BrowseHistoryRepository,
    cacheSizeBytes: Long,
    onClearCache: () -> Unit,
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
            fontScale = fontScale,
            aiConfig = aiConfig,
            onBack = onBack,
            onOpenSettings = onOpenSettings,
            onTitleResolved = onTitleResolved
        )
        Page.All -> AllTab(
            onItemClick = onItemClick,
            onBack = onBack,
            onOpenDaily = onOpenDaily,
            onOpenSearch = onOpenSearch,
            listState = pageListStates.forPage(page)
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
            onItemClick = onItemClick,
            listState = pageListStates.forPage(page)
        )
        Page.Settings -> SettingsScreen(
            themeMode = themeMode,
            onSelectTheme = onSelectTheme,
            dynamicColor = dynamicColor,
            onToggleDynamicColor = onToggleDynamicColor,
            fontChoice = fontChoice,
            onSelectFont = onSelectFont,
            fontScale = fontScale,
            onSelectFontScale = onSelectFontScale,
            sourceMode = sourceMode,
            onSelectSource = onSelectSource,
            cacheSizeBytes = cacheSizeBytes,
            onClearCache = onClearCache,
            onBack = onBack
        )
        Page.AiService -> AiServiceScreen(
            configStore = configStore,
            usageStore = usageStore,
            onBack = onBack
        )
        Page.About -> AboutScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, "关于") }
        )
        Page.HackerNews -> HackerNewsScreen(
            onBack = onBack,
            onOpenComments = onOpenComments,
            onOpenSettings = onOpenSettings,
            listState = pageListStates.forPage(page)
        )
        is Page.HackerNewsComments -> HackerNewsCommentsScreen(
            story = page.story,
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, "HackerNews") },
            onOpenSettings = onOpenSettings,
            listState = pageListStates.forPage(page)
        )
        Page.GitHubTrending -> GitHubTrendingScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, "GitHub Trending") },
            onOpenSettings = onOpenSettings,
            listState = pageListStates.forPage(page)
        )
        Page.LinuxDo -> LinuxDoHotScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, "LinuxDo") },
            listState = pageListStates.forPage(page)
        )
        Page.StormzhangAiNews -> StormzhangAiNewsScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, "stormzhang AI") },
            listState = pageListStates.forPage(page)
        )
        Page.HuggingFacePapers -> HuggingFacePapersScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, "HuggingFace") },
            onOpenSettings = onOpenSettings,
            listState = pageListStates.forPage(page)
        )
        Page.ProductHunt -> ProductHuntScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, "Product Hunt") },
            onOpenSettings = onOpenSettings,
            listState = pageListStates.forPage(page)
        )
        Page.RundownAi -> RundownAiScreen(
            onBack = onBack,
            onOpenUrl = { url, title -> onOpenUrl(url, title, "The Rundown AI") },
            onOpenSettings = onOpenSettings,
            listState = pageListStates.forPage(page)
        )
        // AIHot 精选(原根 tab,现二级页):复用 FeaturedTab,UI 含今日热点 +
        // 最新精选列表 + 「全部 ›」。顶栏带返回箭头(onBack),列表底部不预留底栏
        // (二级页底栏不悬浮)。reselectSignal 传 0(非根 tab,无重击语义)。
        Page.FeaturedHub -> FeaturedTab(
            onItemClick = onItemClick,
            onOpenAll = onOpenAll,
            onOpenUrl = { url, title -> onOpenUrl(url, title, "AI HOT") },
            onBack = onBack,
            reselectSignal = 0,
            listState = pageListStates.forPage(page)
        )
        // 信息源(Hub 浏览区)二级页:聚合 7 个第三方源 + AIHot 精选入口。
        // 原 MoreScreen 的「浏览」组,现独立成页。源入口透传到各自 Screen。
        Page.Sources -> SourcesScreen(
            onBack = onBack,
            onOpenHackerNews = onOpenHackerNews,
            onOpenGitHubTrending = onOpenGitHubTrending,
            onOpenLinuxDo = onOpenLinuxDo,
            onOpenStormzhangAiNews = onOpenStormzhangAiNews,
            onOpenHuggingFacePapers = onOpenHuggingFacePapers,
            onOpenProductHunt = onOpenProductHunt,
            onOpenRundownAi = onOpenRundownAi,
            onOpenFeaturedHub = onOpenFeaturedHub
        )
        Page.BrowseHistory -> BrowseHistoryScreen(
            repo = browseHistoryRepo,
            onBack = onBack,
            onOpenUrl = { url, title, source -> onOpenUrl(url, title, source) },
            listState = pageListStates.forPage(page)
        )
        // 历史摘要:日期列表(history 索引)→ 当日 7 源摘要卡页(复用摘要卡片)。
        // 纯归档语义,不参与 SourceMode 切换;卡片无「查看完整列表」出口。
        Page.SummaryArchive -> SummaryArchiveScreen(
            onSelectDate = onSelectSummaryDate,
            onBack = onBack,
            listState = pageListStates.forPage(page)
        )
        is Page.SummaryDate -> SummaryDateScreen(
            date = page.date,
            onBack = onBack,
            pagerState = pagePagerStates.forPagePager(page)
        )
    }
}

/** 取某个二级页持有的列表滚动状态(上提原因见 AIHotApp 内说明)。 */
private fun MutableMap<Page, LazyListState>.forPage(page: Page): LazyListState =
    getOrPut(page) { LazyListState() }

/** 取某个二级页持有的 Pager 状态(历史摘要按日期页;上提原因同列表状态)。 */
private fun MutableMap<Page, PagerState>.forPagePager(page: Page): PagerState =
    getOrPut(page) { PagerState { SummaryRepository.SOURCE_KEYS.size } }
