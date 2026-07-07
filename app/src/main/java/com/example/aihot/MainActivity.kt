package com.example.aihot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import com.example.aihot.data.NewsItem
import com.example.aihot.ui.NewsDetailScreen
import com.example.aihot.ui.components.AppBottomBar
import com.example.aihot.ui.components.AppTab
import com.example.aihot.ui.daily.DailyArchiveScreen
import com.example.aihot.ui.daily.DailyDateScreen
import com.example.aihot.ui.items.SearchScreen
import com.example.aihot.ui.more.AboutScreen
import com.example.aihot.ui.more.MoreScreen
import com.example.aihot.ui.more.SettingsScreen
import com.example.aihot.ui.more.ThemeMode
import com.example.aihot.ui.tabs.AllTab
import com.example.aihot.ui.tabs.DailyTab
import com.example.aihot.ui.tabs.FeaturedTab
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
 */
private sealed interface Page {
    data class Detail(val item: NewsItem) : Page
    data class Web(val url: String, val title: String = "加载中…") : Page
    data object DailyArchive : Page
    data class DailyDate(val date: String) : Page
    data object Search : Page
    data object Settings : Page
    data object About : Page
}

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
 *  - 底栏显隐:当前 tab 二级栈非空时隐藏底栏(MD3 规范)
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

    // 当前 tab + 每个 tab 的二级页栈
    var currentTab by rememberSaveable { mutableStateOf(AppTab.Featured) }
    var pageStacks by remember {
        mutableStateOf<Map<AppTab, List<Page>>>(
            AppTab.entries.associateWith { emptyList() }
        )
    }

    val currentPages: List<Page> = pageStacks[currentTab].orEmpty()
    val isRoot: Boolean = currentPages.isEmpty()

    // 进入二级页:push 到当前 tab 栈
    val push: (Page) -> Unit = { page ->
        pageStacks = pageStacks.toMutableMap().apply {
            this[currentTab] = (this[currentTab].orEmpty()) + page
        }
    }
    // 弹当前 tab 栈顶
    val pop: () -> Unit = {
        if (pageStacks[currentTab].orEmpty().isNotEmpty()) {
            pageStacks = pageStacks.toMutableMap().apply {
                this[currentTab] = (this[currentTab].orEmpty()).dropLast(1)
            }
        }
    }
    // 切 tab
    val selectTab: (AppTab) -> Unit = { tab ->
        if (tab != currentTab) currentTab = tab
    }

    // 统一"打开内置 WebView"
    val openUrl: (String, String) -> Unit = { url, title -> push(Page.Web(url, title)) }

    // 系统返回键:当前 tab 栈非空时 pop
    BackHandler(enabled = !isRoot) { pop() }

    // 当前屏幕:根(tab) 或 二级页。用作 AnimatedContent 的 key。
    val screen: Screen = if (isRoot) Screen.Root(currentTab) else Screen.Secondary(currentPages.last())

    AIHotTheme(darkTheme = darkTheme) {
        Surface {
            Scaffold(
                bottomBar = {
                    // 仅根页显示底栏;二级页隐藏(沉浸)
                    if (isRoot) {
                        AppBottomBar(current = currentTab, onSelect = selectTab)
                    }
                }
            ) { padding ->
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        val targetDetail = (targetState as? Screen.Secondary)?.page as? Page.Detail
                        val initialDetail = (initialState as? Screen.Secondary)?.page as? Page.Detail
                        when {
                            // tab 之间切换(根→根):crossfade
                            initialState is Screen.Root && targetState is Screen.Root ->
                                fadeIn(tween(280)) togetherWith fadeOut(tween(280))
                            // 进入详情:scale + fade(向前推进感)
                            targetDetail != null ->
                                (fadeIn(tween(300)) + scaleIn(initialScale = 0.92f, animationSpec = tween(300))) togetherWith
                                    (fadeOut(tween(180)) + scaleOut(targetScale = 1.05f, animationSpec = tween(180)))
                            // 退出详情:反向 scale
                            initialDetail != null && targetState is Screen.Root ->
                                (fadeIn(tween(220)) + scaleIn(initialScale = 1.05f, animationSpec = tween(220))) togetherWith
                                    (fadeOut(tween(180)) + scaleOut(targetScale = 0.92f, animationSpec = tween(180)))
                            // 其余(根↔二级页 / 二级页↔二级页):横向推入
                            else -> slideInHorizontally(initialOffsetX = { it / 3 }, animationSpec = tween(300)) + fadeIn(tween(300)) togetherWith
                                slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(300)) + fadeOut(tween(300))
                        }
                    },
                    contentAlignment = androidx.compose.ui.Alignment.TopCenter,
                    label = "nav",
                    // 应用 Scaffold 的 padding(含底栏高度),避免内容被底栏遮挡。
                    // 二级页时底栏隐藏,底部 padding 退化为 0,不影响沉浸效果。
                    // consumeWindowInsets 防止内层各 Tab 的 Scaffold 重复读取系统底部 inset
                    // (手势条),导致底部空白叠加。
                    modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                ) { s ->
                    when (s) {
                        is Screen.Root -> TabRoot(
                            tab = s.tab,
                            onItemClick = { push(Page.Detail(it)) },
                            onOpenSearch = { push(Page.Search) },
                            onOpenArchive = { push(Page.DailyArchive) },
                            onOpenUrl = openUrl,
                            onOpenSettings = { push(Page.Settings) },
                            onOpenAbout = { push(Page.About) }
                        )
                        is Screen.Secondary -> PageView(
                            page = s.page,
                            themeMode = themeMode,
                            onSelectTheme = { themeMode = it },
                            onBack = pop,
                            onItemClick = { push(Page.Detail(it)) },
                            onSelectDate = { push(Page.DailyDate(it)) },
                            onOpenUrl = openUrl
                        )
                    }
                }
            }
        }
    }
}

/**
 * 顶层屏幕标识:根(tab) 或 二级页。供 [AnimatedContent] 区分转场策略。
 */
private sealed interface Screen {
    data class Root(val tab: AppTab) : Screen
    data class Secondary(val page: Page) : Screen
}

/** 渲染某个 tab 的根屏幕。 */
@Composable
private fun TabRoot(
    tab: AppTab,
    onItemClick: (NewsItem) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenArchive: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit
) {
    when (tab) {
        AppTab.Featured -> FeaturedTab(
            onItemClick = onItemClick
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
    onBack: () -> Unit,
    onItemClick: (NewsItem) -> Unit,
    onSelectDate: (String) -> Unit,
    onOpenUrl: (String, String) -> Unit
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
            onBack = onBack
        )
        Page.About -> AboutScreen(onBack = onBack)
    }
}
