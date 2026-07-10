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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import com.example.aihot.data.HackerNewsStory
import com.example.aihot.data.NewsItem
import com.example.aihot.ui.NewsDetailScreen
import com.example.aihot.ui.components.AppBottomBar
import com.example.aihot.ui.components.AppTab
import com.example.aihot.ui.daily.DailyArchiveScreen
import com.example.aihot.ui.daily.DailyDateScreen
import com.example.aihot.ui.items.HackerNewsCommentsScreen
import com.example.aihot.ui.items.HackerNewsScreen
import com.example.aihot.ui.items.SearchScreen
import com.example.aihot.ui.more.AboutScreen
import com.example.aihot.ui.more.MoreScreen
import com.example.aihot.ui.more.SettingsScreen
import com.example.aihot.ui.more.FontChoice
import com.example.aihot.ui.more.ThemeMode
import com.example.aihot.ui.tabs.AllTab
import com.example.aihot.ui.tabs.DailyTab
import com.example.aihot.ui.tabs.FeaturedTab
import com.example.aihot.ui.anim.PageNavStyle
import com.example.aihot.ui.anim.pageTransition
import com.example.aihot.ui.theme.AIHotTheme
import com.example.aihot.ui.webview.WebViewScreen

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
    data object Search : Page
    data object Settings : Page
    data object About : Page
    data object HackerNews : Page
    data class HackerNewsComments(val story: HackerNewsStory) : Page
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
        is Page.Search -> putString("t", "Search")
        is Page.Settings -> putString("t", "Settings")
        is Page.About -> putString("t", "About")
        is Page.HackerNews -> putString("t", "HackerNews")
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
        "Search" -> Page.Search
        "Settings" -> Page.Settings
        "About" -> Page.About
        "HackerNews" -> Page.HackerNews
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
    // 主题模式:提升到顶层,设置页可修改。默认跟随系统。
    var themeMode by rememberSaveable { mutableStateOf(ThemeMode.System) }
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    // 字体族:同样提升到顶层,设置页可修改。默认系统无衬线。
    // System 时不向 AIHotTheme 传 fontFamily(沿用 Type.kt 默认 SansSerif),
    // 仅 Serif/Mono 时传实际 FontFamily,触发全 App 字形切换。
    var fontChoice by rememberSaveable { mutableStateOf(FontChoice.System) }

    // 当前 tab + 每个 tab 的二级页栈
    var currentTab by rememberSaveable { mutableStateOf(AppTab.Featured) }
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

    // 统一"打开内置 WebView"
    val openUrl: (String, String) -> Unit = { url, title -> push(Page.Web(url, title)) }

    // 系统返回键:当前 tab 栈非空时 pop
    BackHandler(enabled = !isRoot) { pop() }

    // 当前屏幕:根(tab) 或 二级页。用作 AnimatedContent 的 key。
    val screen: Screen = if (isRoot) Screen.Root(currentTab) else Screen.Secondary(currentPages.last())

    AIHotTheme(
        darkTheme = darkTheme,
        fontFamily = if (fontChoice == FontChoice.System) null else fontChoice.fontFamily
    ) {
        Surface {
            Scaffold(
                bottomBar = {
                    // 根页显示底栏;进入二级页时向下滑出,返回时滑入。
                    // 二级页不显示底栏(沉浸感)。底栏隐藏/显示期间 Scaffold 的
                    // contentWindowInsets padding 会随之变化,转场期间内容高度会随之拉伸/收缩,
                    // 与页面位移叠加产生轻微抖动——这是已知取舍,优先保证沉浸感与无留白。
                    AnimatedVisibility(
                        visible = isRoot,
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it })
                    ) {
                        AppBottomBar(current = currentTab, onSelect = selectTab)
                    }
                }
            ) { padding ->
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
                    contentAlignment = androidx.compose.ui.Alignment.TopCenter,
                    label = "nav",
                    // 应用 Scaffold 的 padding(含底栏高度)。consumeWindowInsets 防止内层各 Tab 的
                    // Scaffold 重复读取系统 inset(状态栏/手势条),导致空白叠加。
                    modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                ) { s ->
                    when (s) {
                        is Screen.Root -> TabRoot(
                            tab = s.tab,
                            onItemClick = { push(Page.Detail(it)) },
                            onOpenSearch = { push(Page.Search) },
                            onOpenArchive = { push(Page.DailyArchive) },
                            onOpenHackerNews = { push(Page.HackerNews) },
                            onOpenUrl = openUrl,
                            onOpenSettings = { push(Page.Settings) },
                            onOpenAbout = { push(Page.About) }
                        )
                        is Screen.Secondary -> PageView(
                            page = s.page,
                            themeMode = themeMode,
                            onSelectTheme = { themeMode = it },
                            fontChoice = fontChoice,
                            onSelectFont = { fontChoice = it },
                            onBack = pop,
                            onItemClick = { push(Page.Detail(it)) },
                            onSelectDate = { push(Page.DailyDate(it)) },
                            onOpenComments = { push(Page.HackerNewsComments(it)) },
                            onOpenUrl = openUrl,
                            darkTheme = darkTheme
                        )
                    }
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
    onOpenSearch: () -> Unit,
    onOpenArchive: () -> Unit,
    onOpenHackerNews: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit
) {
    when (tab) {
        AppTab.Featured -> FeaturedTab(
            onItemClick = onItemClick,
            onOpenUrl = onOpenUrl
        )
        AppTab.All -> AllTab(onItemClick = onItemClick)
        AppTab.Daily -> DailyTab(
            onItemClick = onItemClick,
            onOpenArchive = onOpenArchive,
            onOpenUrl = onOpenUrl
        )
        AppTab.More -> MoreScreen(
            onOpenArchive = onOpenArchive,
            onOpenSearch = onOpenSearch,
            onOpenHackerNews = onOpenHackerNews,
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
    onBack: () -> Unit,
    onItemClick: (NewsItem) -> Unit,
    onSelectDate: (String) -> Unit,
    onOpenComments: (HackerNewsStory) -> Unit,
    onOpenUrl: (String, String) -> Unit,
    darkTheme: Boolean = false
) {
    when (page) {
        is Page.Detail -> NewsDetailScreen(
            item = page.item,
            onBack = onBack,
            onOpenUrl = onOpenUrl
        )
        is Page.Web -> WebViewScreen(
            url = page.url,
            title = page.title,
            darkTheme = darkTheme,
            onBack = onBack
        )
        Page.DailyArchive -> DailyArchiveScreen(
            onSelectDate = onSelectDate,
            onBack = onBack
        )
        is Page.DailyDate -> DailyDateScreen(
            date = page.date,
            onBack = onBack,
            onOpenUrl = onOpenUrl
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
            onBack = onBack
        )
        Page.About -> AboutScreen(onBack = onBack)
        Page.HackerNews -> HackerNewsScreen(
            onBack = onBack,
            onOpenComments = onOpenComments
        )
        is Page.HackerNewsComments -> HackerNewsCommentsScreen(
            story = page.story,
            onBack = onBack,
            onOpenUrl = onOpenUrl
        )
    }
}
