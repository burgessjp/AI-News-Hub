package com.peng.ainewshub.ui.nav

import android.app.Activity
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.PredictiveBackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peng.ainewshub.R
import com.peng.ainewshub.data.AiConfig
import com.peng.ainewshub.data.AiConfigStore
import com.peng.ainewshub.data.AiUsageStore
import com.peng.ainewshub.data.AppDatabase
import com.peng.ainewshub.data.BrowseHistoryRepository
import com.peng.ainewshub.data.FavoritesRepository
import com.peng.ainewshub.data.SummaryRepository
import com.peng.ainewshub.data.source.ArchiveHttpClient
import com.peng.ainewshub.notify.DailyNotifyScheduler
import com.peng.ainewshub.playback.TtsFloatingPill
import com.peng.ainewshub.ui.anim.pageTransition
import com.peng.ainewshub.ui.anim.predictivePopTransition
import com.peng.ainewshub.ui.components.AppBottomBar
import com.peng.ainewshub.ui.components.AppTab
import com.peng.ainewshub.ui.i18n.AppLanguage
import com.peng.ainewshub.ui.i18n.AppLocale
import com.peng.ainewshub.ui.more.FontChoice
import com.peng.ainewshub.ui.more.FontScale
import com.peng.ainewshub.ui.more.SettingsStore
import com.peng.ainewshub.ui.more.ThemeMode
import com.peng.ainewshub.ui.theme.AiNewsHubTheme
import com.peng.ainewshub.widget.HotNowWidgetUpdater
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 浅色系统栏 scrim(与 AndroidX enableEdgeToEdge 默认值一致)。ARGB 32 位带符号整数。 */
private val LIGHT_SCRIM = 0xE6FFFFFF.toInt()

/** 深色系统栏 scrim(与 AndroidX enableEdgeToEdge 默认值一致)。ARGB 32 位带符号整数。 */
private val DARK_SCRIM = 0x801B1B1B.toInt()

/**
 * App 顶层路由 —— 多栈底部导航。
 *
 * 模型(详见 [AppNavState]):
 *  - currentTab: 当前选中的 4 个根 tab 之一(总览 / 摘要 / 趋势 / 更多)
 *  - pageStacks: 每个 tab 独立的二级页栈(栈空 = 处于根)
 *
 * 行为:
 *  - 点底栏切 tab:仅换 currentTab(各 tab 二级栈保留)
 *  - 进入二级页:push 到当前 tab 的栈
 *  - 返回:pop 当前 tab 栈;栈空时交系统默认退出 App
 *  - 底栏始终常驻(见下方浮动药丸底栏说明)
 */
@Composable
internal fun AiNewsHubApp(
    openSettingsOnLaunch: Boolean = false,
    /** 「直达设置」消费回调(冷启动 extras 与 ainewshub://settings 深链共用;消费后复位,支持热启动重复触发)。 */
    onSettingsConsumed: () -> Unit = {},
    /** 桌面小组件深链(待消费):非 null 时经 openUrl 统一入口打开,随后回调清空。 */
    pendingOpenUrl: Triple<String, String, String?>? = null,
    onPendingUrlConsumed: () -> Unit = {},
    /** ainewshub://tab/<name> 深链(待消费):非 null 时切到该根 tab(清空其二级栈),随后回调清空。 */
    pendingTab: AppTab? = null,
    onPendingTabConsumed: () -> Unit = {}
) {
    val appContext = LocalContext.current.applicationContext
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
    // 收藏(稍后读)仓库(进程级单例):同一 Room 库,WebView 顶栏星标读写。
    val favoritesRepo = remember {
        FavoritesRepository(AppDatabase.get(appContext).favoriteDao())
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
    // 每日更新通知自查链上次运行时刻(设置页「上次检查」,排障可观测出口)
    val lastNotifyCheckAt by settingsStore.lastNotifyCheckAtFlow.collectAsStateWithLifecycle(
        initialValue = 0L
    )
    // AI 服务全局配置:除设置页外,WebView 整页翻译也读取(开关/就绪态判定)
    val aiConfig by configStore.configFlow.collectAsStateWithLifecycle(
        initialValue = AiConfig()
    )
    val onSelectTheme: (ThemeMode) -> Unit = { scope.launch { settingsStore.updateTheme(it) } }
    val onToggleDynamicColor: (Boolean) -> Unit = { scope.launch { settingsStore.updateDynamicColor(it) } }
    val onSelectFont: (FontChoice) -> Unit = { scope.launch { settingsStore.updateFont(it) } }
    val onSelectFontScale: (FontScale) -> Unit = { scope.launch { settingsStore.updateFontScale(it) } }
    // 应用内语言:持久化 + 重建 Activity 生效;小组件同步刷新文案。
    // recreate 会销毁组合并取消 rememberCoroutineScope,故整体包 NonCancellable ——
    // 否则 select 之后的「小组件刷新」大概率被中途取消,语言切换后小组件文案不更新
    val activity = LocalContext.current as? Activity
    val onSelectLanguage: (AppLanguage) -> Unit = { lang ->
        scope.launch {
            withContext(NonCancellable) {
                activity?.let { AppLocale.select(it, settingsStore, lang) }
                HotNowWidgetUpdater.refreshFromApp(appContext)
            }
        }
    }

    // 缓存占用统计与清理:状态已下沉到 SettingsScreen —— 全 cacheDir 递归 walk 是
    // 重活,随冷启动白做(多数用户根本不进设置页),改为首次进入设置页时才计算。

    // 每日更新通知:持久化开关 + 同步 WorkManager 自查链调度(见 notify/DailyUpdateNotifier.kt)
    val onToggleDailyNotify: (Boolean) -> Unit = { enabled ->
        scope.launch {
            settingsStore.updateDailyNotify(enabled)
            DailyNotifyScheduler.sync(appContext, enabled)
        }
    }

    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    // 系统栏(状态栏/导航栏)外观跟随 App 实际暗色态(用户 ThemeMode 或系统暗色),
    // 而非 enableEdgeToEdge() 默认依据的系统 uiMode —— 否则用户强制浅色 / 深色时,
    // scrim 色与图标明暗会与界面背景错配(如浅色 scrim 配深色 Surface)。用带参重载 +
    // key 驱动,darkTheme 变化时即时重发 scrim 色 + 图标明暗,切换深/浅色即时生效。
    val systemBarsHost = LocalContext.current as ComponentActivity
    DisposableEffect(darkTheme) {
        systemBarsHost.enableEdgeToEdge(
            statusBarStyle = if (darkTheme) SystemBarStyle.dark(DARK_SCRIM)
                else SystemBarStyle.light(LIGHT_SCRIM, DARK_SCRIM),
            navigationBarStyle = if (darkTheme) SystemBarStyle.dark(DARK_SCRIM)
                else SystemBarStyle.light(LIGHT_SCRIM, DARK_SCRIM)
        )
        onDispose { }
    }

    // 导航状态机:currentTab + 每 tab 二级页栈 + 转场方向/重击信号,统一收编于
    // AppNavState(默认「总览」——端侧 AI 当日分析,首屏即默认首页);
    // rememberSaveable 持久化,转屏/进程被杀后仍可恢复。
    // Web 页占位标题(common_loading):恢复导航栈时 Web 页缺 title 的兜底取词,随语言生效
    val webLoadingTitle = stringResource(R.string.common_loading)
    val nav = rememberAppNavState(webLoadingTitle)
    // 预测返回手势状态:收到首个手势事件后置 true,transitionSpec 切换为
    // predictivePopTransition(LinearEasing,动画进度与手指 1:1);
    // backSwipeEdge 记录手势起始边缘,决定退出页滑出方向。
    var seekMode by remember { mutableStateOf(false) }
    var backSwipeEdge by remember { mutableStateOf(BackEventCompat.EDGE_LEFT) }

    val currentPages: List<Page> = nav.currentPages
    val isRoot: Boolean = nav.isRoot

    // 统一"打开内置 WebView"。
    //
    // 记录浏览历史:在此唯一入口拦截,全 App 覆盖。source 为来源标签
    // ("GitHub Trending"/"日报"/"AI HOT"…),由各调用点显式传入,可空。
    val openUrl: (String, String, String?) -> Unit = { url, title, source ->
        scope.launch { browseHistoryRepo.record(url, title, source) }
        nav.push(Page.Web(url, title, source))
    }

    // 网页标题回写:WebView 加载完成后拿到真实标题,更新历史记录(而非占位"加载中…")。
    // 不更新 visitedAt,避免回写把条目顶到最前。同一 URL 已收藏时同步更新收藏标题。
    val onTitleResolved: (String, String) -> Unit = { url, resolvedTitle ->
        scope.launch {
            browseHistoryRepo.updateTitle(url, resolvedTitle)
            favoritesRepo.updateTitle(url, resolvedTitle)
        }
    }

    // 外部入口要求直达设置页(系统选中翻译的「去设置」/ ainewshub://settings 深链)
    LaunchedEffect(openSettingsOnLaunch) {
        if (openSettingsOnLaunch) {
            nav.push(Page.Settings)
            onSettingsConsumed()
        }
    }

    // 桌面小组件深链:经 openUrl 统一入口(记浏览历史 + push Page.Web),消费后清空。
    // 与 openSettings 同范式 —— Activity 侧保证冷启动只消费一次(旋转重建不重复 push)。
    LaunchedEffect(pendingOpenUrl) {
        pendingOpenUrl?.let { (url, title, source) ->
            openUrl(url, title, source)
            onPendingUrlConsumed()
        }
    }

    // 外部深链切 tab:goToRoot(切 tab + 清空该 tab 二级栈),消费后清空。
    // 热启动(singleTask onNewIntent)可重复触发 —— 每次深链都是新的非 null 值写入。
    LaunchedEffect(pendingTab) {
        pendingTab?.let { tab ->
            nav.goToRoot(tab)
            onPendingTabConsumed()
        }
    }

    // 离线兜底提示:归档取数落到盘上旧数据时弹一次性 Snackbar(每次离线事件只提示一次,
    // 任一请求网络成功后状态复位,下次再离线会重新提示)。不做常驻横幅 —— 会盖住各页
    // 顶栏的返回导航;数据新旧由列表页自带的「数据更新时间」头体现。
    val offlineMode by ArchiveHttpClient.offlineMode.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val offlineBannerText = stringResource(R.string.offline_banner)
    LaunchedEffect(Unit) {
        snapshotFlow { offlineMode }.collect { offline ->
            if (offline) {
                snackbarHostState.showSnackbar(offlineBannerText, duration = SnackbarDuration.Long)
            }
        }
    }

    // 当前屏幕:根(tab) 或 二级页。用作转场的 currentState/targetState。
    val screen: Screen = nav.screen

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
            Screen.Root(nav.currentTab)
        }
        nav.isNavigatingBack = true
        try {
            progress.collect { event ->
                // 首个手势事件到达才切 predictive 规格(LinearEasing 跟手);
                // 先于 seekTo 写入,保证 transition 创建时捕获新规格。
                seekMode = true
                backSwipeEdge = event.swipeEdge
                navTransitionState.seekTo(event.progress, to)
            }
            navTransitionState.animateTo(to)
            nav.pop()
            seekMode = false
        } catch (e: kotlinx.coroutines.CancellationException) {
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
    // 摘要 tab 的 Pager 状态(顶部圆点页指示器跳页与内容 Pager 共用此状态)
    val summaryPagerState = rememberPagerState(pageCount = { SummaryRepository.SOURCE_KEYS.size })
    // 总览 tab 的列表滚动状态(与 summaryPagerState 同层上提)
    val overviewListState = rememberLazyListState()
    // 趋势 tab 的列表滚动状态(同上提)
    val trendsListState = rememberLazyListState()
    // 二级页滚动状态:以 Page 值(data class,可作 key)索引,页面弹出后清理。
    val pageListStates = remember { mutableMapOf<Page, LazyListState>() }
    // 二级页 Pager 状态(历史摘要按日期页):与列表状态同上提、同清理。
    val pagePagerStates = remember { mutableMapOf<Page, PagerState>() }
    LaunchedEffect(nav.pageStacks) {
        val alive = nav.pageStacks.values.flatten().toSet()
        pageListStates.keys.removeAll { it !in alive }
        pagePagerStates.keys.removeAll { it !in alive }
    }

    // 二级页共用环境 + 显示偏好控制:构造一次分组下传 PageView,
    // 取代原先 40+ 个散参数的层层穿线。
    val pageEnv = PageEnv(
        settingsStore = settingsStore,
        configStore = configStore,
        usageStore = usageStore,
        aiConfig = aiConfig,
        lastNotifyCheckAt = lastNotifyCheckAt,
        browseHistoryRepo = browseHistoryRepo,
        favoritesRepo = favoritesRepo
    )
    val displayControls = DisplayControls(
        prefs = displayPrefs,
        onSelectTheme = onSelectTheme,
        onToggleDynamicColor = onToggleDynamicColor,
        onSelectFont = onSelectFont,
        onSelectFontScale = onSelectFontScale,
        onSelectLanguage = onSelectLanguage,
        onToggleDailyNotify = onToggleDailyNotify
    )

    AiNewsHubTheme(
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
                                back = nav.isNavigatingBack
                            )
                        }
                    },
                    contentAlignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                ) { s ->
                    when (s) {
                        is Screen.Root -> TabRoot(
                            tab = s.tab,
                            nav = nav,
                            reselectTick = nav.reselectTick,
                            summaryPagerState = summaryPagerState,
                            overviewListState = overviewListState,
                            trendsListState = trendsListState,
                            onOpenUrl = openUrl
                        )
                        is Screen.Secondary -> PageView(
                            page = s.page,
                            nav = nav,
                            onOpenUrl = openUrl,
                            onTitleResolved = onTitleResolved,
                            listStates = pageListStates,
                            pagerStates = pagePagerStates,
                            env = pageEnv,
                            display = displayControls,
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
                    AppBottomBar(current = nav.currentTab, onSelect = { nav.selectTab(it) })
                }

                // 离线兜底提示的宿主:悬浮在浮动药丸底栏上方,不遮挡内容交互。
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 92.dp)
                )

                // 语音速报播放浮窗:播放期间悬浮于任意 tab / 二级页(含 WebView 页)之上,
                // 与底栏/Snackbar 同级挂载(转场层之上,不随页面 push/pop 销毁);
                // 组件以屏幕绝对坐标定位(TopStart + offset),默认停在右下、底栏上方,
                // 整条可拖,松手后吸附到左右边缘并缩小为悬浮球。显隐由服务 state 驱动。
                TtsFloatingPill(modifier = Modifier.align(Alignment.TopStart))
            }
        }
    }

    // 冷启动新数据全局弹窗:悬浮于任意 tab / 二级页之上(检查与渲染见 NewDataPromptHost)。
    // 「查看」直达总览根页(切 tab + 清空该 tab 二级栈);数据经 ArchiveHttpClient
    // 共享 2 分钟缓存(与总览 Tab 取数同源),到屏时已是最新,无需再触发刷新。
    NewDataPromptHost(
        settingsStore = settingsStore,
        onGoOverview = { nav.goToRoot(AppTab.Overview) }
    )
}
