package com.peng.ainewshub.ui.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.WebAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.peng.ainewshub.R
import com.peng.ainewshub.data.AiConfig
import com.peng.ainewshub.data.TranslationRepository
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.LoadingState
import com.peng.ainewshub.ui.anim.Motion
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.more.FontScale
import com.peng.ainewshub.ui.theme.AppText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 内置 WebView 屏幕 — 不跳出 App。
 *
 * 显示原文 / AI HOT 阅读页,带:
 *  - 顶栏:返回 + 标题(随网页加载动态更新)+ 当前域名副标题 + 「更多」菜单
 *    (刷新 / 翻译本页 / 复制链接 / 在浏览器打开)
 *  - 底部工具栏:后退 / 前进 / 阅读模式 / 分享(高频导航一级操作,视频全屏时隐藏)
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
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onTitleResolved: (url: String, title: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 无 DI,Repository 就地构造(与项目惯例一致);翻译缓存/用量统计由仓库内部处理
    val translationRepo = remember { TranslationRepository(context) }

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
    DisposableEffect(Unit) {
        onDispose {
            translateJobRef.job?.cancel()
            webViewRef.web?.let { web ->
                web.removeJavascriptInterface("AndroidBlobSaver")
                (web.parent as? android.view.ViewGroup)?.removeView(web)
                web.destroy()
            }
        }
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
                // baseUrl 带哨兵 fragment 标记阅读页;相对路径资源仍按原 URL 解析
                web.loadDataWithBaseURL(
                    currentUrl + READER_SENTINEL,
                    buildReaderHtml(article),
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
            imageUrl.startsWith("data:", ignoreCase = true) -> saveDataUrl(context, imageUrl)
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
            },
            bottomBar = {
                // 高频导航一级操作(浏览器惯例);视频全屏时隐藏,不挡画面
                if (fullscreenView == null) {
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
                                    BlobSaver(context) { name, mime, data -> saveBlob(context, name, mime, data) },
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
                                            saveDataUrl(context, downloadUrl)

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

/**
 * 翻译弹层 —— 原文/译文平铺对照列表(高信息密度)。
 *
 * [ModalBottomSheet] 半屏起步(skipPartiallyExpanded=false),可手势拖拽到全屏、
 * 拖下关闭。每段平铺:原文在上(主色 [cs.onSurface])、译文在下(次要色
 * [cs.onSurfaceVariant]),靠颜色区分层次不靠卡片,密度高、阅读连贯。译文未到时
 * 不占位,到达后在原文下方追加。翻译中头部展示线性进度条,完成态列表末尾给
 * 「翻译完成」收尾提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslateSheet(
    originals: List<String>,
    results: List<String?>?,
    progress: Pair<Int, Int>?,
    translating: Boolean,
    onCancelTranslate: () -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    // 半屏起步:展开后立即拉回部分展开态,避免内容多时默认撑满全屏
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    LaunchedEffect(Unit) { sheetState.partialExpand() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        // 头部:图标 + 标题 + 进度/取消
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Translate,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.webview_translate_sheet_title),
                style = AppText.titleItem,
                color = cs.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            if (translating && progress != null) {
                Text(
                    text = stringResource(R.string.webview_translate_progress, progress.first, progress.second),
                    style = AppText.caption,
                    color = cs.onSurfaceVariant
                )
            }
            if (translating) {
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onCancelTranslate) { Text(stringResource(R.string.common_cancel)) }
            }
        }
        // 头部进度条:翻译中展示确定性进度;无轨道细线,与顶栏加载条同语言
        val progressFraction = if (translating && progress != null && progress.second > 0) {
            progress.first.toFloat() / progress.second
        } else null
        if (progressFraction != null) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(2.dp),
                color = cs.primary,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Round
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        // 空白块(空段落等)不参与展示
        val blocks = remember(originals) { originals.indices.filter { originals[it].isNotBlank() } }
        // 翻译完成判定:非翻译中、有结果、且有至少一条译文
        val finished = !translating && results != null && results.any { it != null }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(blocks.size) { position ->
                val index = blocks[position]
                val original = originals[index]
                val translated = results?.getOrNull(index)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    // 原文(在上):主色,信息主体
                    Text(
                        text = original,
                        style = AppText.body,
                        color = cs.onSurface
                    )
                    // 译文(在下):左侧 primary 色细条做视觉锚点 + 弱化色文字,
                    // 靠色条明确区分译文与原文(单靠 onSurfaceVariant 与 onSurface 差别太小)
                    if (translated != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(cs.primary, RoundedCornerShape(2.dp))
                            )
                            Text(
                                text = translated,
                                style = AppText.body,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
            // 完成态收尾:居中 check + 「翻译完成」
            if (finished) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.webview_translate_done),
                            style = AppText.caption,
                            color = cs.primary
                        )
                    }
                }
            }
            item {
                Spacer(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .height(16.dp)
                )
            }
        }
    }
}

/**
 * 顶部加载进度条 —— Safari/Chrome 风格的细线进度。
 *
 * 与默认 [LinearProgressIndicator] 的差异:
 *  1. 更细(2dp),贴顶精致,不抢视觉
 *  2. 无背景轨道(trackColor = transparent),加载区是干净的细线,
 *     不再铺满整条灰轨显得笨重
 *  3. [AnimatedVisibility] 包裹,加载完成时平滑淡出,而非硬切消失
 *
 * @param loading  是否加载中(控制显隐)
 * @param progress 0f..1f 加载进度
 */
@Composable
private fun TopProgressBar(
    loading: Boolean,
    progress: () -> Float
) {
    AnimatedVisibility(
        visible = loading,
        enter = fadeIn(tween(Motion.SHORT)),
        exit = fadeOut(tween(Motion.MEDIUM))
    ) {
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {}
        )
    }
}

/** 待下载文件参数。被 DownloadListener 产出,经权限流程后交给 [enqueueDownload]。 */
private data class DownloadParams(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimetype: String?
)

/**
 * 处理网页发起的下载。
 *
 * API ≥ 29:scoped storage 下写公共「下载」目录无需权限,直接入队。
 * API < 29:需 WRITE_EXTERNAL_STORAGE;未授权时发起请求并暂存任务,授权回调里入队。
 */
private fun handleDownload(
    context: Context,
    params: DownloadParams,
    launcher: androidx.activity.compose.ManagedActivityResultLauncher<String, Boolean>,
    onPending: (DownloadParams) -> Unit
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        enqueueDownload(context, params)
        return
    }
    val granted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED
    if (granted) {
        enqueueDownload(context, params)
    } else {
        onPending(params)
        launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}

/** 把下载任务交给系统 DownloadManager,文件存到公共「下载」目录。 */
private fun enqueueDownload(context: Context, params: DownloadParams) {
    val filename = guessDownloadName(context, params.url, params.contentDisposition, params.mimetype)
    try {
        val request = DownloadManager.Request(Uri.parse(params.url)).apply {
            setMimeType(params.mimetype)
            params.userAgent?.let { addRequestHeader("User-Agent", it) }
            // 通知栏可见 & 下载完成后提示
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            setTitle(filename)
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm == null) {
            Toast.makeText(context, context.getString(R.string.webview_toast_download_manager_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        dm.enqueue(request)
        Toast.makeText(context, context.getString(R.string.webview_toast_download_started, filename), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        // 兜底:非法 URI / 权限 / 路径等任何异常都不再让 App 崩溃
        Log.w("Download", "下载失败", e)
        Toast.makeText(context, context.getString(R.string.webview_toast_download_failed), Toast.LENGTH_SHORT).show()
    }
}

/**
 * 下载 blob: URL —— DownloadManager 不认 blob,注入 JS 把 blob 读成 base64,
 * 经 BlobSaver 接口回传后解码写入文件。
 */
private fun downloadBlob(
    webView: WebView,
    context: Context,
    blobUrl: String,
    contentDisposition: String?,
    mimetype: String?
) {
    val filename = guessDownloadName(context, blobUrl, contentDisposition, mimetype)
    // 块级作用域函数不能用 JS 关键字做变量名,这里用 fn
    val fn = filename.replace("'", "\\'")
    val mt = (mimetype ?: "application/octet-stream").replace("'", "\\'")
    // blobUrl 来自网页,需同样转义单引号(与 fn/mt 一致),防 JS 字面量注入
    val du = blobUrl.replace("'", "\\'")
    // 用 fetch 拿到 blob 后转 base64 回传原生,避开 DownloadManager 对 blob 的限制
    val js = """
    (function(){
      try {
        fetch('$du').then(function(r){return r.blob();}).then(function(b){
          var fr = new FileReader();
          fr.onload = function(){
            var data = fr.result.split(',')[1];
            AndroidBlobSaver.save('$fn', '$mt', data);
          };
          fr.readAsDataURL(b);
        }).catch(function(e){
          AndroidBlobSaver.save('$fn', '$mt', null);
        });
      } catch(e) {
        AndroidBlobSaver.save('$fn', '$mt', null);
      }
    })();
    """.trimIndent()
    webView.evaluateJavascript(js, null)
    Toast.makeText(context, context.getString(R.string.webview_toast_saving_file), Toast.LENGTH_SHORT).show()
}

/**
 * 下载 data: URL(canvas 导出图片的常见形态)。仅处理 base64 形态,
 * 解码后走与 blob 相同的写文件路径;非 base64 的 data: 提示不支持。
 */
private fun saveDataUrl(context: Context, url: String) {
    // data:[<mime>][;base64],<data>
    val comma = url.indexOf(',')
    val meta = if (comma > 5) url.substring(5, comma) else ""
    if (comma < 0 || !meta.contains(";base64")) {
        Toast.makeText(context, context.getString(R.string.webview_toast_download_unsupported), Toast.LENGTH_SHORT).show()
        return
    }
    val mime = meta.substringBefore(';').ifBlank { "application/octet-stream" }
    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
    // 文件名按类型区分:image/* → "下载图片",其余 → "下载文件"(文案随界面语言)
    val base = context.getString(
        if (mime.startsWith("image/")) R.string.webview_download_image_name
        else R.string.webview_download_file_name
    )
    saveBlob(context, "$base.$ext", mime, url.substring(comma + 1))
}

/**
 * 推断下载文件名。blob: URL 没有文件名信息,按 mimetype 生成"下载图片.<ext>"。
 */
private fun guessDownloadName(
    context: Context,
    url: String,
    contentDisposition: String?,
    mimetype: String?
): String {
    if (!url.startsWith("blob:", ignoreCase = true)) {
        return URLUtil.guessFileName(url, contentDisposition, mimetype)
    }
    // blob: 无法从 URL 取扩展名,按 mime 推断
    val ext = MimeTypeMap.getSingleton()
        .getExtensionFromMimeType(mimetype) ?: "bin"
    // 文件名按类型区分:image/* → "下载图片",其余 → "下载文件"(文案随界面语言)
    val base = context.getString(
        if (mimetype?.startsWith("image/") == true) R.string.webview_download_image_name
        else R.string.webview_download_file_name
    )
    return "$base.$ext"
}

/**
 * 把 base64 数据写入公共「下载」目录(blob / data: 下载共用)。
 *
 * - API ≥ 29:scoped storage 下不能直接写公共目录,改用 MediaStore(Downloads)。
 * - API < 29:走传统 File 路径 + MediaScanner 扫描。
 *
 * [data] 为 null 表示网页侧 fetch 失败 —— 提示后返回,不写空文件。
 */
private fun saveBlob(context: Context, filename: String, mimetype: String?, data: String?) {
    if (data.isNullOrEmpty()) {
        Toast.makeText(context, context.getString(R.string.webview_toast_download_no_data), Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val bytes = Base64.decode(data, Base64.DEFAULT)
        val displayName = filename.ifBlank { context.getString(R.string.webview_download_fallback_name) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+:MediaStore 写入公共 Downloads,无需任何存储权限
            val resolver = context.contentResolver
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName)
                mimetype?.let { put(android.provider.MediaStore.Downloads.MIME_TYPE, it) }
            }
            val uri = resolver.insert(collection, values)
            if (uri == null) {
                Toast.makeText(context, context.getString(R.string.webview_toast_save_create_failed), Toast.LENGTH_SHORT).show()
                return
            }
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
        } else {
            // API < 29:传统公共目录 File 写入
            val downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloads.exists()) downloads.mkdirs()
            val file = java.io.File(downloads, displayName)
            java.io.FileOutputStream(file).use { it.write(bytes) }
            // 扫描媒体,使文件立刻在相册/文件 App 中可见
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf(mimetype ?: "*/*"), null
            )
        }
        Toast.makeText(context, context.getString(R.string.webview_toast_saved, displayName), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Log.w("Save", "保存失败", e)
        Toast.makeText(context, context.getString(R.string.webview_toast_save_failed), Toast.LENGTH_SHORT).show()
    }
}

/**
 * 注入给网页的 JS 桥接对象 —— 把从 blob 读出的 base64 数据回传原生。
 *
 * @param onSave 回调: (文件名, mimetype, base64数据或null)
 */
private class BlobSaver(
    private val context: Context,
    private val onSave: (filename: String, mimetype: String?, data: String?) -> Unit
) {
    // @JavascriptInterface 跑在 WebView JavaBridge 线程(无 Looper),
    // 回调链里有 Toast,必须切主线程,否则抛 "Can't toast on a thread that has not called Looper.prepare()"
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @JavascriptInterface
    fun save(filename: String, mimetype: String?, data: String?) {
        mainHandler.post { onSave(filename, mimetype.takeIf { !it.isNullOrBlank() }, data) }
    }
}

/** 系统分享:把当前页 URL 作为纯文本交给系统分享面板。 */
private fun shareUrl(context: Context, title: String, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.common_share)))
}

/**
 * 唤起外部 App —— 处理网页发起的非 http(s) 协议链接。
 *
 * 分两类:
 *  - intent:// URI:按 Chrome 规范解析 Intent。优先解析其中的 fallback URL
 *    (S.browser_fallback_url),目标 App 不存在时用它(通常跳应用市场/网页)。
 *  - 普通自定义 scheme(weixin://、mailto:、tel:…):直接构造 ACTION_VIEW 唤起。
 *
 * 任何无法解析 / 无 App 接收的异常都用 Toast 提示,绝不崩溃。
 */
private fun handleExternalUri(context: Context, uri: Uri) {
    val scheme = uri.scheme?.lowercase()
    try {
        if (scheme == "intent") {
            // Chrome intent:// 规范 —— Intent.parseUri 解析,带 fallback URL
            val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                .addCategory(Intent.CATEGORY_BROWSABLE)
            // 目标 App 未安装时,优先用网页声明的 fallback URL
            val fallback = intent.getStringExtra("browser_fallback_url")
            // fallback 仅允许 http(s):防止恶意页面用 file:///、content:// 作 fallback 泄漏本地文件
            val fallbackScheme = fallback?.let { runCatching { Uri.parse(it).scheme?.lowercase() }.getOrNull() }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else if (!fallback.isNullOrBlank() &&
                (fallbackScheme == "http" || fallbackScheme == "https")
            ) {
                // 仅 http(s) 网址,交给系统浏览器/市场打开
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(fallback))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                )
            } else {
                Toast.makeText(context, context.getString(R.string.webview_toast_no_app_for_link), Toast.LENGTH_SHORT).show()
            }
        } else {
            // weixin://、mailto:、tel:、sms: 等普通自定义 scheme
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            )
        }
    } catch (e: android.content.ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.webview_toast_no_app_for_link), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.webview_toast_open_link_failed), Toast.LENGTH_SHORT).show()
    }
}

/** 应用网页深色模式:优先用网页自带深色主题,无则算法深色(自动转深)。 */
private fun applyDarkTheme(settings: WebSettings, darkTheme: Boolean) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, darkTheme)
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureWebSettings(darkTheme: Boolean, fontScale: FontScale) {
    layoutParams = android.view.ViewGroup.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        android.view.ViewGroup.LayoutParams.MATCH_PARENT
    )
    settings.apply {
        javaScriptEnabled = true          // AI HOT 站内页是 Next.js,需要 JS
        domStorageEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        cacheMode = WebSettings.LOAD_DEFAULT
        // 字号跟随「设置 → 字号档位」(100=标准)
        textZoom = (fontScale.scale * 100).roundToInt()
        // 安全加固:显式关闭文件/内容访问(默认 false,此处声明防后续误开)
        allowFileAccess = false          // 禁止 file:// 内容访问
        allowContentAccess = false       // 禁止 content:// 访问(本 App 无需)
        mediaPlaybackRequiresUserGesture = true  // 禁止页面自动播放音视频
        // UA 以系统 WebView 自带 UA 为基础(版本随 WebView 自动更新,不再硬编码老化),
        // 去掉 "; wv" 与 "Version/4.0" 标记伪装成移动版 Chrome —— 部分站点
        // (如 Google 登录)会拒绝原生 WebView UA
        userAgentString = WebSettings.getDefaultUserAgent(context)
            .replace("; wv", "")
            .replace(Regex("Version/\\d+(\\.\\d+)*\\s+"), "")
    }
    applyDarkTheme(settings, darkTheme)
}

/**
 * WebView 底部工具栏 —— 高频导航操作(后退/前进/阅读模式/分享)提为一级操作,
 * 不再藏在「更多」菜单里(对齐浏览器惯例);阅读模式按 [readerActive] 切换进出。
 * 视频全屏时由调用方整体隐藏。
 */
@Composable
private fun WebBottomBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    readerActive: Boolean,
    readerLoading: Boolean,
    translateEnabled: Boolean,
    translateActive: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onToggleReader: () -> Unit,
    onTranslate: () -> Unit,
    onShare: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surface)
            .navigationBarsPadding()
    ) {
        // 顶部发丝线,与网页内容区分隔
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(cs.outlineVariant)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            WebBarItem(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                label = stringResource(R.string.webview_bar_back),
                enabled = canGoBack,
                onClick = onBack,
                modifier = Modifier.weight(1f)
            )
            WebBarItem(
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                label = stringResource(R.string.webview_bar_forward),
                enabled = canGoForward,
                onClick = onForward,
                modifier = Modifier.weight(1f)
            )
            WebBarItem(
                icon = if (readerActive) Icons.Outlined.WebAsset else Icons.AutoMirrored.Outlined.MenuBook,
                label = stringResource(
                    if (readerActive) R.string.webview_bar_exit_reader else R.string.webview_bar_reader
                ),
                enabled = !readerLoading,
                onClick = onToggleReader,
                modifier = Modifier.weight(1f)
            )
            // 翻译:阅读模式下可用;翻译中或已有结果时图标高亮表示已激活
            WebBarItem(
                icon = Icons.Outlined.Translate,
                label = stringResource(R.string.webview_bar_translate),
                enabled = readerActive && translateEnabled,
                highlight = translateActive,
                onClick = onTranslate,
                modifier = Modifier.weight(1f)
            )
            WebBarItem(
                icon = Icons.Outlined.Share,
                label = stringResource(R.string.common_share),
                enabled = true,
                onClick = onShare,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 底部工具栏单项:图标 + 小字标签,触摸区 ≥48dp;禁用态用 outline 色压低。 */
@Composable
private fun WebBarItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    // 高亮(已激活)用 primary 色,否则用常规禁用/可用色
    val color = when {
        highlight -> MaterialTheme.colorScheme.primary
        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.outline
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(20.dp))
        Text(text = label, style = AppText.caption, color = color)
    }
}
