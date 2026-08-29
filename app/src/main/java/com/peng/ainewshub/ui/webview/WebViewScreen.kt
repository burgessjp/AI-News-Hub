package com.peng.ainewshub.ui.webview

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peng.ainewshub.R
import com.peng.ainewshub.data.prefs.AiConfig
import com.peng.ainewshub.data.repo.BrowseHistoryRepository
import com.peng.ainewshub.data.repo.FavoritesRepository
import com.peng.ainewshub.data.repo.TranslationRepository
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.LoadingState
import com.peng.ainewshub.ui.anim.Motion
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.theme.AppText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import com.peng.ainewshub.data.prefs.FontScale

/**
 * 内置 WebView 屏幕 — 不跳出 App。
 *
 * 显示原文 / AI HOT 阅读页,带:
 *  - 顶栏:返回 + 标题(随网页加载动态更新)+ 当前域名副标题 + 星标收藏
 *    (toggle 当前页,状态跟随站内导航后的真实 URL)+ 「更多」菜单
 *    (刷新 / 翻译本页 / 复制链接 / 在浏览器打开)
 *  - 底部工具栏:后退 / 前进 / 阅读模式 / 分享(高频导航一级操作,视频全屏时隐藏)。
 *    悬浮于 WebView 之上(网页布局高度固定,不占 bottomBar 槽),网页向下滚动时
 *    滑出隐藏以扩大阅读空间,向上滚动或打开新页时滑回
 *  - 顶部线性进度条(加载中;整页翻译时复用为翻译进度)
 *  - 主帧加载失败错误态([ErrorState] + 重试),不再是空白页
 *  - 站内导航(主帧 http(s) 交 WebView 原生处理;子框架不拦截;外部 scheme 唤起外部 App)
 *  - 阅读模式:注入 assets/readability.js 提取正文,套干净模板重排(见 ReaderMode.kt);
 *    阅读页内可用用户自配 AI 服务「翻译本页」——译文不改写原页,在底部弹层
 *    (ModalBottomSheet,半屏/全屏可拖拽)里与原文对照展示,逐段渐进刷新,可取消
 *  - 长按:图片(保存/复制地址,http/data/blob 均可)与链接(复制/浏览器打开)
 *  - 全屏视频(onShowCustomView 覆盖层,返回键先退全屏)
 *  - 网页发起的文件下载:HTTP(S) 走 DownloadManager;blob:/data: URL 解码后写文件
 *  - 网页深色模式(算法深色,优先用网页自带的深色主题)
 *  - 字号跟随「设置 → 字号档位」(settings.textZoom)
 *
 * 2026-08-29 拆分:下载链路(DownloadsHelper.kt)、系统交互(WebIntents.kt)、
 * 实例配置(WebViewConfig.kt)、底部工具栏与进度条(WebBars.kt)、翻译弹层
 * (TranslateSheet.kt)已移至同包独立文件;本文件保留主组合函数、WebView 装配
 * (两个 Client + 滚动/长按/下载监听)、顶栏、弹窗与「继续上次阅读」chip。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    url: String,
    // 默认空串:顶栏空白标题经 common_loading 资源兜底(调用方一律显式传 title)
    title: String = "",
    darkTheme: Boolean = false,
    fontScale: FontScale = FontScale.Standard,
    aiConfig: AiConfig = AiConfig(),
    favoritesRepo: FavoritesRepository,
    // 浏览历史仓库:阅读进度(「继续上次阅读」)按 URL 落库读取(PageEnv 下传,
    // 与 favoritesRepo 同款方式)
    browseHistoryRepo: BrowseHistoryRepository,
    // 来源标签(如 "GitHub Trending"),随收藏落库;少数入口未标注时为 null
    source: String? = null,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onTitleResolved: (url: String, title: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 无 DI,Repository 就地构造(与项目惯例一致);翻译缓存/用量统计由仓库内部处理
    val translationRepo = remember { TranslationRepository.get(context) }

    var pageTitle by remember { mutableStateOf(title) }
    // 当前页真实 URL(随导航更新,已剥阅读页哨兵),分享/复制/打开浏览器都用它
    var currentUrl by remember { mutableStateOf(url) }
    var progress by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    // 网页内部是否有可回退/前进历史:决定菜单可用态与系统返回键行为
    var webCanGoBack by remember { mutableStateOf(false) }
    var webCanGoForward by remember { mutableStateOf(false) }
    // 主帧加载失败描述(非空即显示错误态);子资源失败不记录(页面可能部分可用)
    var loadError by remember { mutableStateOf<String?>(null) }
    // 待下载任务:API<29 时等用户授予存储权限后再入队
    var pendingDownload by remember { mutableStateOf<DownloadParams?>(null) }
    // 「更多」菜单 / 长按目标 / AI 配置引导弹窗
    var menuExpanded by remember { mutableStateOf(false) }
    var longPressTarget by remember { mutableStateOf<LongPressTarget?>(null) }
    var showAiConfigDialog by remember { mutableStateOf(false) }
    // 全屏视频:onShowCustomView 给的 View 非空即覆盖全屏
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    // 底部工具栏随网页滚动隐现:向下滚动隐藏扩大阅读空间,向上滚动滑回(浏览器惯例)。
    // 累积量为瞬态值(普通 var 即可),方向反转即清零防边缘抖动;阈值按 dp 换算。
    var webBottomBarVisible by remember { mutableStateOf(true) }
    var scrollAccum = 0
    val bottomBarScrollThreshold = with(LocalDensity.current) { 8.dp.toPx() }
    // 阅读模式:readerActive 由 onPageStarted 按哨兵 URL 判定(见 ReaderMode.kt);
    // 翻译状态:原文块 + 译文结果(底部弹层对照展示,不改写原页 DOM)
    var readerActive by remember { mutableStateOf(false) }
    var readerLoading by remember { mutableStateOf(false) }
    var translating by remember { mutableStateOf(false) }
    var translateProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var translateOriginals by remember { mutableStateOf<List<String>?>(null) }
    var translateResults by remember { mutableStateOf<List<String?>?>(null) }
    var showTranslateSheet by remember { mutableStateOf(false) }

    // 延迟挂载 WebView:进入转场(FADE)结束后再创建,避免 factory 的主线程重活
    // 与转场抢帧(此前实测转场被拉长、且淡入目标是白屏,视觉上像没有动画)。
    // 转场期间先展示顶栏 + 加载进度条,WebView 创建完成后再接上。
    var attachWeb by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(Motion.MEDIUM + 50L)
        attachWeb = true
    }

    // factory 创建的 WebView 引用,供 DisposableEffect 在离开屏幕时 destroy,避免内存泄漏。
    // 注意:必须用普通 Ref(非 mutableStateOf)捕获 —— 若用 State 作 DisposableEffect 的 key,
    // factory 里赋值会触发 key 变化 → dispose 循环 → WebView 被提前 destroy → 页面加载中断。
    // 这里 key 用 Unit,仅在离开 composition 时执行一次 onDispose。
    val webViewRef = remember { object { var web: WebView? = null } }
    // 视频全屏回调 / 翻译协程 / readability.js 文本缓存,同样用 Ref 避免触发重组
    val fullscreenCallbackRef = remember { object { var cb: WebChromeClient.CustomViewCallback? = null } }
    val translateJobRef = remember { object { var job: Job? = null } }
    val readabilityRef = remember { object { var js: String? = null } }

    // ===== 阅读进度(「继续上次阅读」) =====
    // 滚动节流只写 Ref(不发重组、不落库);落库统一在 flushReadingProgress()
    // (onDispose / ON_PAUSE / 站内跳转前)。url 记录进度归属,防站内导航后误写新 URL。
    val readingProgressRef = remember { object { var percent: Int = 0; var url: String? = null } }
    // 恢复提示(非 null 即显示 chip);每个 URL 每次会话只提示一次
    var resumeProgress by remember { mutableStateOf<Int?>(null) }
    val resumeShownUrls = remember { mutableSetOf<String>() }

    /** 滚动位置 → 0-100 百分比(contentHeight 为 density 无关单位,需换算)。 */
    fun computeReadingPercent(web: WebView): Int {
        val maxScroll = (web.contentHeight * web.resources.displayMetrics.density).toInt() - web.height
        if (maxScroll <= 0) return 0
        return (web.scrollY * 100 / maxScroll).coerceIn(0, 100)
    }

    /**
     * 进度落库并清空 Ref。仓库内部 fire-and-forget(独立作用域),页面销毁同帧写入
     * 也能完成。url 为空 = 本页未滚过,不动库(保留旧进度供恢复)。
     */
    fun flushReadingProgress() {
        val u = readingProgressRef.url
        if (!u.isNullOrEmpty()) browseHistoryRepo.saveProgress(u, readingProgressRef.percent)
        readingProgressRef.percent = 0
        readingProgressRef.url = null
    }

    DisposableEffect(Unit) {
        onDispose {
            translateJobRef.job?.cancel()
            // 离开页面:进度落库要在 destroy 前读(scrollY 随 destroy 失效)
            flushReadingProgress()
            webViewRef.web?.let { web ->
                web.removeJavascriptInterface("AndroidBlobSaver")
                (web.parent as? android.view.ViewGroup)?.removeView(web)
                web.destroy()
            }
        }
    }

    // WebView 随宿主生命周期暂停/恢复:App 切后台时停掉该页 JS 定时器/动画/音视频的
    // 处理,避免后台白耗 CPU/电量(新闻站大量埋点脚本尤其明显);回前台恢复。
    // 用 per-WebView 的 onPause/onResume,不用全局 pauseTimers —— 后者作用域跨实例,
    // 若在暂停态销毁又新开 WebView,新实例可能继承暂停态。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // 切后台先落进度再暂停:进程被杀时 onDispose 不一定执行,这里是兜底
                Lifecycle.Event.ON_PAUSE -> {
                    flushReadingProgress()
                    webViewRef.web?.onPause()
                }
                Lifecycle.Event.ON_RESUME -> webViewRef.web?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 系统返回键:网页有内部历史时先退历史(WebView 内的站内跳转不应一键退出整页);
    // 无历史时不拦截,交给外层(MainActivity)pop 整页。Compose 内层 BackHandler 优先于外层。
    androidx.activity.compose.BackHandler(enabled = webCanGoBack && fullscreenView == null) {
        webViewRef.web?.goBack()
    }
    // 视频全屏时返回键先退全屏(声明在上方 BackHandler 之后,同层级后声明者优先)。
    // onHideCustomView 是 WebChromeClient 公开方法,直接调用即走我们的 override 复位状态。
    androidx.activity.compose.BackHandler(enabled = fullscreenView != null) {
        webViewRef.web?.webChromeClient?.onHideCustomView()
    }

    // 存储权限请求(API<29 下载需要)。授权回调里把暂存的任务入队 DownloadManager。
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val params = pendingDownload
        pendingDownload = null
        if (granted && params != null) {
            enqueueDownload(context, params)
        } else {
            Toast.makeText(context, context.getString(R.string.webview_toast_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    /** 复制到剪贴板并提示。 */
    fun copyText(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("url", text))
        toast(context.getString(R.string.webview_toast_copied))
    }

    /** 用系统浏览器打开。 */
    fun openInBrowser(target: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(target))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            )
        } catch (e: Exception) {
            toast(context.getString(R.string.webview_toast_no_browser))
        }
    }

    /** 错误态重试:URL 已提交过用 reload;首次就失败(URL 未提交)重新 loadUrl。 */
    fun retryLoad() {
        val web = webViewRef.web ?: return
        loadError = null
        if (web.url.isNullOrBlank()) web.loadUrl(url) else web.reload()
    }

    /** 进入阅读模式:注入 Readability 提取正文,套模板经 loadDataWithBaseURL 渲染。 */
    fun enterReaderMode() {
        val web = webViewRef.web ?: return
        if (readerLoading) return
        scope.launch {
            readerLoading = true
            try {
                // readability.js 文本只读一次 assets,进程内缓存
                val lib = readabilityRef.js ?: withContext(Dispatchers.IO) {
                    context.assets.open("readability.js").bufferedReader().use { it.readText() }
                }.also { readabilityRef.js = it }
                val article = extractReaderArticle(web, lib)
                if (article == null) {
                    toast(context.getString(R.string.webview_toast_reader_extract_failed))
                    return@launch
                }
                // 进入新阅读页前清掉上一页的翻译产物
                translateOriginals = null
                translateResults = null
                showTranslateSheet = false
                // 模板拼接(数十 KB 级大字符串)挪到 Default 线程;loadDataWithBaseURL
                // 是 View 调用,回到主线程执行
                val html = withContext(Dispatchers.Default) { buildReaderHtml(article) }
                // baseUrl 带哨兵 fragment 标记阅读页;相对路径资源仍按原 URL 解析
                web.loadDataWithBaseURL(
                    currentUrl + READER_SENTINEL,
                    html,
                    "text/html", "utf-8", null
                )
            } finally {
                readerLoading = false
            }
        }
    }

    /** 退出阅读模式:阅读页是历史栈顶,goBack 直接回原页(还保留滚动位置)。 */
    fun exitReaderMode() {
        val web = webViewRef.web ?: return
        translateJobRef.job?.cancel()
        if (web.canGoBack()) web.goBack() else web.loadUrl(currentUrl)
    }

    /**
     * 整页翻译:抽块 → 打开翻译弹层 → 逐块翻译,每批结果渐进写入弹层状态。
     * 不改写阅读页 DOM;已有结果(或正在翻译)时直接打开弹层,不重复请求。
     */
    fun startTranslate() {
        if (!aiConfig.isReady) {
            showAiConfigDialog = true
            return
        }
        if (translating || translateResults != null) {
            showTranslateSheet = true
            return
        }
        val web = webViewRef.web ?: return
        translateJobRef.job = scope.launch {
            translating = true
            translateProgress = null
            try {
                val texts = extractBlockTexts(web)
                if (texts.isNullOrEmpty() || texts.all { it.isBlank() }) {
                    toast(context.getString(R.string.webview_toast_nothing_to_translate))
                    return@launch
                }
                translateOriginals = texts
                // 占位结果:弹层立即按原文渲染,译文随后逐批填入
                translateResults = List(texts.size) { null }
                showTranslateSheet = true
                translateResults = translateReaderBlocks(translationRepo, aiConfig, texts) { partial, done, total ->
                    translateResults = partial
                    translateProgress = done to total
                }
            } finally {
                translating = false
                translateProgress = null
            }
        }
    }

    /** 长按图片的「保存图片」:按 URL 形态分流到既有下载链路。 */
    fun savePressedImage(imageUrl: String) {
        when {
            imageUrl.startsWith("http", ignoreCase = true) -> handleDownload(
                context,
                DownloadParams(imageUrl, webViewRef.web?.settings?.userAgentString, null, null),
                storagePermissionLauncher
            ) { pendingDownload = it }
            imageUrl.startsWith("data:", ignoreCase = true) ->
                scope.launch { saveDataUrl(context, imageUrl) }
            imageUrl.startsWith("blob:", ignoreCase = true) ->
                webViewRef.web?.let { downloadBlob(it, context, imageUrl, null, null) }
                    ?: toast(context.getString(R.string.webview_toast_save_image_failed))
            else -> toast(context.getString(R.string.webview_toast_save_image_unsupported))
        }
    }

    // 顶栏域名副标题:让用户始终知道自己在哪个站
    val pageHost = remember(currentUrl) {
        runCatching { Uri.parse(currentUrl).host }.getOrNull().orEmpty()
    }

    // 星标收藏状态:跟随当前真实 URL(站内导航后 currentUrl 变化,星标随之切换)
    val favoriteEntity by remember(currentUrl) { favoritesRepo.observeByUrl(currentUrl) }
        .collectAsStateWithLifecycle(initialValue = null)

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                AppTopBar(
                    title = pageTitle.ifBlank { stringResource(R.string.common_loading) },
                    subtitle = pageHost.ifBlank { null },
                    titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    },
                    actions = {
                        // 星标收藏当前页(toggle);已收藏实心主色,未收藏描边
                        IconButton(onClick = {
                            scope.launch {
                                val favorited = favoritesRepo.toggle(currentUrl, pageTitle, source)
                                toast(
                                    context.getString(
                                        if (favorited) R.string.webview_toast_favorited
                                        else R.string.webview_toast_unfavorited
                                    )
                                )
                            }
                        }) {
                            Icon(
                                imageVector = if (favoriteEntity != null) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = stringResource(
                                    if (favoriteEntity != null) R.string.favorites_remove_desc
                                    else R.string.favorites_add_desc
                                ),
                                tint = if (favoriteEntity != null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.tab_more))
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                // 高频导航(后退/前进/阅读模式/分享)已提到底部工具栏,
                                // 菜单只留低频与场景项
                                if (!readerActive) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.common_refresh)) },
                                        leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                        onClick = {
                                            menuExpanded = false
                                            webViewRef.web?.reload()
                                        }
                                    )
                                }
                                // 翻译入口已提升到底部栏一键操作,不再占溢出菜单
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.webview_menu_copy_link)) },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                    onClick = {
                                        menuExpanded = false
                                        copyText(currentUrl)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.webview_menu_open_browser)) },
                                    leadingIcon = { Icon(Icons.Outlined.Language, null) },
                                    onClick = {
                                        menuExpanded = false
                                        openInBrowser(currentUrl)
                                    }
                                )
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 转场结束后再创建 WebView(见上方 attachWeb 说明)
                if (attachWeb) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewRef.web = this
                                configureWebSettings(darkTheme, fontScale)
                                // 滚动隐现底部栏:按方向累积位移,超阈值切换显隐;方向反转先清零
                                setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                                    val dy = scrollY - oldScrollY
                                    if (dy != 0) {
                                        if ((scrollAccum > 0) != (dy > 0)) scrollAccum = 0
                                        scrollAccum += dy
                                        if (scrollAccum > bottomBarScrollThreshold && webBottomBarVisible) {
                                            webBottomBarVisible = false
                                            scrollAccum = 0
                                        } else if (scrollAccum < -bottomBarScrollThreshold && !webBottomBarVisible) {
                                            webBottomBarVisible = true
                                            scrollAccum = 0
                                        }
                                    }
                                    // 阅读进度:节流只写 Ref(阅读模式是另一套内容,不记录)
                                    if (!readerActive) {
                                        readingProgressRef.percent = computeReadingPercent(this)
                                        readingProgressRef.url = currentUrl
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest
                                    ): Boolean {
                                        // 子框架(iframe 等)导航不拦截,交 WebView 自己处理;
                                        // 主帧按 scheme 分流:
                                        //  - http(s)/about/data:返回 false 原生加载,保留 POST
                                        //    与跳转语义(此前 loadUrl+true 会丢 POST 数据);
                                        //  - blob: 下载由 DownloadListener 托管,导航层忽略;
                                        //  - javascript: 拒绝执行(防注入);
                                        //  - 其余 scheme(intent://、weixin://、mailto:、tel:…)
                                        //    唤起外部 App,失败时优雅降级。
                                        if (!request.isForMainFrame) return false
                                        val uri = request.url
                                        when (uri.scheme?.lowercase()) {
                                            "http", "https", "about", "data" -> return false
                                            "blob", "javascript" -> return true
                                        }
                                        handleExternalUri(view.context, uri)
                                        return true
                                    }

                                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                        loading = true
                                        loadError = null
                                        // 新页面起始:底部栏复位为可见(浏览器惯例)
                                        webBottomBarVisible = true
                                        // 离开当前页(站内跳转/进出阅读模式):旧页进度先落库再清空,
                                        // 防止归到新 URL 头上;恢复提示同时隐藏
                                        flushReadingProgress()
                                        resumeProgress = null
                                        val isReader = url?.endsWith(READER_SENTINEL) == true
                                        if (!isReader) {
                                            // 离开阅读页(点链接/回退/退出):清理翻译状态
                                            translateJobRef.job?.cancel()
                                            translating = false
                                            translateProgress = null
                                            translateOriginals = null
                                            translateResults = null
                                            showTranslateSheet = false
                                        }
                                        readerActive = isReader
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        pageTitle = view.title ?: title
                                        // 阅读页 URL 带哨兵,剥掉后再用于分享/复制/历史
                                        val finishedUrl = url?.removeSuffix(READER_SENTINEL)
                                        currentUrl = finishedUrl ?: currentUrl
                                        loading = false
                                        webCanGoBack = view.canGoBack()
                                        webCanGoForward = view.canGoForward()
                                        // 回写真实标题到浏览历史(用最终落地 URL,跟随重定向)
                                        val resolvedUrl = finishedUrl ?: currentUrl
                                        val resolvedTitle = view.title?.takeIf { it.isNotBlank() }
                                        if (resolvedTitle != null) onTitleResolved(resolvedUrl, resolvedTitle)
                                        // 「继续上次阅读」:查上次进度,深浅适中才提示;每 URL 每会话一次
                                        if (!readerActive && resolvedUrl.isNotBlank()) {
                                            scope.launch {
                                                val saved = browseHistoryRepo.progressOf(resolvedUrl)
                                                if (saved in RESUME_PROGRESS_MIN..RESUME_PROGRESS_MAX &&
                                                    resolvedUrl !in resumeShownUrls
                                                ) {
                                                    resumeShownUrls += resolvedUrl
                                                    resumeProgress = saved
                                                }
                                            }
                                        }
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                        // 站内跳转/回退都会更新历史栈,同步「可回退/前进」状态
                                        webCanGoBack = view.canGoBack()
                                        webCanGoForward = view.canGoForward()
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        error: WebResourceError
                                    ) {
                                        // 只报主帧错误:图片/接口等子资源失败不打扰
                                        if (request.isForMainFrame) {
                                            loadError = "(${error.errorCode}) ${error.description}"
                                            loading = false
                                        }
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        errorResponse: WebResourceResponse
                                    ) {
                                        // 主帧 HTTP 错误(404/500 等)同样进错误态
                                        if (request.isForMainFrame) {
                                            loadError = context.getString(R.string.webview_error_http, errorResponse.statusCode)
                                        }
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress
                                        loading = newProgress < 100
                                    }

                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        if (!title.isNullOrBlank()) {
                                            pageTitle = title
                                            // 部分站点在 onPageFinished 之前/之后才设标题,
                                            // 这里也回写一次,保证历史标题最终是真实标题
                                            onTitleResolved(currentUrl, title)
                                        }
                                    }

                                    // HTML5 视频全屏:把全屏 View 交给 Compose 覆盖层渲染
                                    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                                        fullscreenCallbackRef.cb?.let { runCatching { it.onCustomViewHidden() } }
                                        fullscreenCallbackRef.cb = callback
                                        fullscreenView = view
                                    }

                                    override fun onHideCustomView() {
                                        fullscreenView = null
                                        fullscreenCallbackRef.cb?.let { runCatching { it.onCustomViewHidden() } }
                                        fullscreenCallbackRef.cb = null
                                    }
                                }
                                // 长按:图片/链接弹操作菜单;文本保持系统默认(长按选择)
                                setOnLongClickListener {
                                    val hit = hitTestResult
                                    val extra = hit.extra
                                    when {
                                        extra.isNullOrBlank() -> false
                                        hit.type == WebView.HitTestResult.IMAGE_TYPE ||
                                            hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                                            longPressTarget = LongPressTarget.Image(extra)
                                            true
                                        }
                                        hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                                            longPressTarget = LongPressTarget.Link(extra)
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                // 网页发起的下载处理。
                                //  - blob: URL:DownloadManager 不认 —— 注入 JS 把 blob 读成 base64,
                                //    经 BlobSaver 接口回传后解码写入文件(网页用 JS 合成图片"保存"时即此路径)。
                                //  - http(s):走 DownloadManager。
                                //  - data:(base64):canvas 导出图片的常见形态,解码后同 blob 写文件。
                                //  - 其它:直接提示无法下载,不再崩溃。
                                addJavascriptInterface(
                                    BlobSaver(context) { name, mime, data ->
                                        scope.launch { saveBlob(context, name, mime, data) }
                                    },
                                    "AndroidBlobSaver"
                                )
                                setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, _ ->
                                    when {
                                        downloadUrl.startsWith("blob:", ignoreCase = true) ->
                                            downloadBlob(this, context, downloadUrl, contentDisposition, mimetype)

                                        downloadUrl.startsWith("http", ignoreCase = true) -> {
                                            val params = DownloadParams(
                                                url = downloadUrl,
                                                userAgent = userAgent,
                                                contentDisposition = contentDisposition,
                                                mimetype = mimetype
                                            )
                                            handleDownload(context, params, storagePermissionLauncher) {
                                                pendingDownload = it
                                            }
                                        }

                                        downloadUrl.startsWith("data:", ignoreCase = true) ->
                                            scope.launch { saveDataUrl(context, downloadUrl) }

                                        else -> {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.webview_toast_download_unsupported),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                                loadUrl(url)
                            }
                        },
                        update = { web ->
                            // 运行时切换主题/字号档位:即时生效
                            applyDarkTheme(web.settings, darkTheme)
                            web.settings.textZoom = (fontScale.scale * 100).roundToInt()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // 顶部加载进度条(2dp 细线,加载完成淡出)
                TopProgressBar(loading = loading, progress = { progress / 100f })

                // 主帧加载失败:错误态覆盖(挡住 WebView 自带的错误页),可重试
                loadError?.let { err ->
                    ErrorState(
                        message = err,
                        onRetry = { retryLoad() },
                        title = stringResource(R.string.webview_error_title),
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                    )
                }

                // 正在提取正文(Readability 注入通常 <1s):居中 loading
                if (readerLoading) {
                    LoadingState()
                }
            }
        }

        // 「继续上次阅读」提示:6 秒无操作自动消失;视频全屏时隐藏
        LaunchedEffect(resumeProgress) {
            if (resumeProgress != null) {
                delay(RESUME_HINT_DISMISS_MS)
                resumeProgress = null
            }
        }
        if (fullscreenView == null) {
            AnimatedVisibility(
                visible = resumeProgress != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // 悬浮于底部工具栏之上(工具栏含导航栏留白约 80dp,再加间距)
                    .padding(bottom = 96.dp),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(CircleShape)
                        // inverse 系在浅色/深色网页内容上都足够醒目,不依赖主题
                        .background(MaterialTheme.colorScheme.inverseSurface)
                        .clickable {
                            val percent = resumeProgress
                            val web = webViewRef.web
                            resumeProgress = null
                            if (percent != null && web != null) {
                                // 内容高度就绪前 scrollTo 会被吞,post 到下一帧执行
                                web.post {
                                    val maxScroll = (web.contentHeight *
                                        web.resources.displayMetrics.density).toInt() - web.height
                                    if (maxScroll > 0) web.scrollTo(0, percent * maxScroll / 100)
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.webview_resume_reading, resumeProgress ?: 0),
                        style = AppText.caption,
                        color = MaterialTheme.colorScheme.inverseOnSurface
                    )
                }
            }
        }

        // 底部工具栏:悬浮在 WebView 之上(网页布局高度固定,栏隐现不触发网页重排)。
        // 网页向下滚动时向下滑出以扩大阅读空间,向上滚动/打开新页时滑回;视频全屏时隐藏。
        if (fullscreenView == null) {
            AnimatedVisibility(
                visible = webBottomBarVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                WebBottomBar(
                    canGoBack = webCanGoBack,
                    canGoForward = webCanGoForward,
                    readerActive = readerActive,
                    readerLoading = readerLoading,
                    translateEnabled = aiConfig.translateEnabled,
                    translateActive = translating || translateResults != null,
                    onBack = { webViewRef.web?.goBack() },
                    onForward = { webViewRef.web?.goForward() },
                    onToggleReader = { if (readerActive) exitReaderMode() else enterReaderMode() },
                    onTranslate = { startTranslate() },
                    onShare = { shareUrl(context, pageTitle, currentUrl) }
                )
            }
        }

        // HTML5 视频全屏覆盖层:盖住顶栏与 WebView,黑底(视频功能色,与主题无关)
        fullscreenView?.let { fv ->
            AndroidView(
                factory = { fv },
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }
    }

    // 长按图片/链接的操作弹窗
    longPressTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { longPressTarget = null },
            title = {
                Text(
                    stringResource(
                        if (target is LongPressTarget.Image) R.string.webview_dialog_image
                        else R.string.webview_dialog_link
                    )
                )
            },
            text = {
                Column {
                    Text(
                        text = target.url,
                        style = AppText.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    if (target is LongPressTarget.Image) {
                        TextButton(
                            onClick = {
                                longPressTarget = null
                                savePressedImage(target.url)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.webview_action_save_image)) }
                        TextButton(
                            onClick = {
                                longPressTarget = null
                                copyText(target.url)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.webview_action_copy_image_url)) }
                    } else {
                        TextButton(
                            onClick = {
                                longPressTarget = null
                                copyText(target.url)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.webview_menu_copy_link)) }
                        TextButton(
                            onClick = {
                                longPressTarget = null
                                openInBrowser(target.url)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.webview_menu_open_browser)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { longPressTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // 翻译弹层:原文/译文对照,逐批渐进刷新;半屏起步,可拖拽全屏/拖下关闭
    val sheetOriginals = translateOriginals
    if (showTranslateSheet && sheetOriginals != null) {
        TranslateSheet(
            originals = sheetOriginals,
            results = translateResults,
            progress = translateProgress,
            translating = translating,
            onCancelTranslate = { translateJobRef.job?.cancel() },
            onDismiss = { showTranslateSheet = false }
        )
    }

    // 翻译需要可用的 AI 服务配置:未配置时引导去设置页
    if (showAiConfigDialog) {
        AlertDialog(
            onDismissRequest = { showAiConfigDialog = false },
            title = { Text(stringResource(R.string.webview_ai_config_title)) },
            text = { Text(stringResource(R.string.webview_ai_config_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showAiConfigDialog = false
                    onOpenSettings()
                }) { Text(stringResource(R.string.common_go_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showAiConfigDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 长按命中目标。url 为图片地址(IMAGE/SRC_IMAGE_ANCHOR)或链接地址(SRC_ANCHOR)。 */
private sealed interface LongPressTarget {
    val url: String

    data class Image(override val url: String) : LongPressTarget
    data class Link(override val url: String) : LongPressTarget
}

// ===== 阅读进度(「继续上次阅读」)常量 =====
// 提示区间:太浅不值得恢复(<8),读完了不提示(>92)
private const val RESUME_PROGRESS_MIN = 8
private const val RESUME_PROGRESS_MAX = 92
// 恢复提示自动消失时长(毫秒)
private const val RESUME_HINT_DISMISS_MS = 6_000L
