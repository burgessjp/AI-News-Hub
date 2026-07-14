package com.example.aihot.ui.webview

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.aihot.ui.anim.Motion
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.AppTopBarDefaults

/**
 * 内置 WebView 屏幕 — 不跳出 App。
 *
 * 显示原文 / AI HOT 阅读页,带:
 *  - 顶栏返回按钮 + 页面标题(随网页加载动态更新)+ 分享(系统分享当前页)
 *  - 顶部线性进度条(加载中)
 *  - 站内导航(链接在同 WebView 内打开,不弹外部浏览器)
 *  - 网页发起的文件下载:HTTP(S) 走 DownloadManager;blob: URL 注入 JS
 *    读成 base64 回传后写入文件(常见于网页用 JS 合成/生成的图片"保存")
 *  - 网页深色模式(算法深色,优先用网页自带的深色主题)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    url: String,
    title: String = "加载中…",
    darkTheme: Boolean = false,
    onBack: () -> Unit,
    onTitleResolved: (url: String, title: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var pageTitle by remember { mutableStateOf(title) }
    // 当前页真实 URL(随导航更新),分享时用它而非初始 url
    var currentUrl by remember { mutableStateOf(url) }
    var progress by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    // 待下载任务:API<29 时等用户授予存储权限后再入队
    var pendingDownload by remember { mutableStateOf<DownloadParams?>(null) }

    // factory 创建的 WebView 引用,供 DisposableEffect 在离开屏幕时 destroy,避免内存泄漏。
    // 注意:必须用普通 Ref(非 mutableStateOf)捕获 —— 若用 State 作 DisposableEffect 的 key,
    // factory 里赋值会触发 key 变化 → dispose 循环 → WebView 被提前 destroy → 页面加载中断。
    // 这里 key 用 Unit,仅在离开 composition 时执行一次 onDispose。
    val webViewRef = remember { object { var web: WebView? = null } }
    DisposableEffect(Unit) {
        onDispose {
            webViewRef.web?.let { web ->
                web.removeJavascriptInterface("AndroidBlobSaver")
                (web.parent as? android.view.ViewGroup)?.removeView(web)
                web.destroy()
            }
        }
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
            Toast.makeText(context, "未授予权限,已取消下载", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = pageTitle.ifBlank { "加载中…" },
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                // 单纯的系统分享:把当前页 URL 作为纯文本交给系统分享面板
                actions = {
                    IconButton(onClick = { shareUrl(context, pageTitle, currentUrl) }) {
                        Icon(Icons.Outlined.IosShare, contentDescription = "分享")
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
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef.web = this
                        configureWebSettings(darkTheme)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                // 按协议分流:http(s)/about/data 留在站内;
                                // blob: 由 DownloadListener 处理,这里直接忽略(避免落到 startActivity);
                                // javascript: 拒绝执行(防注入,不在白名单 → 不 loadUrl);
                                // 其余 scheme(intent://、weixin://、mailto:、tel:…)
                                // 唤起外部 App,失败时优雅降级。
                                val uri = request.url
                                val scheme = uri.scheme?.lowercase()
                                if (scheme == "http" || scheme == "https" ||
                                    scheme == "about" || scheme == "data"
                                ) {
                                    view.loadUrl(uri.toString())
                                    return true
                                }
                                // blob: 的下载已由 setDownloadListener 托管,导航层不处理
                                if (scheme == "blob") return true
                                // javascript: 不执行,直接拦截(防止任意网页注入 JS)
                                if (scheme == "javascript") return true
                                handleExternalUri(view.context, uri)
                                return true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                pageTitle = view.title ?: title
                                currentUrl = url ?: currentUrl
                                loading = false
                                // 回写真实标题到浏览历史(用最终落地 URL,跟随重定向)
                                val resolvedUrl = url ?: currentUrl
                                val resolvedTitle = view.title?.takeIf { it.isNotBlank() }
                                if (resolvedTitle != null) onTitleResolved(resolvedUrl, resolvedTitle)
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
                        }
                        // 网页发起的下载处理。
                        //  - blob: URL:DownloadManager 不认 —— 注入 JS 把 blob 读成 base64,
                        //    经 BlobSaver 接口回传后解码写入文件(网页用 JS 合成图片"保存"时即此路径)。
                        //  - http(s):走 DownloadManager。
                        //  - 其它(data:/非 http):直接提示无法下载,不再崩溃。
                        addJavascriptInterface(
                            BlobSaver(context) { name, mime, data -> saveBlob(context, name, mime, data) },
                            "AndroidBlobSaver"
                        )
                        setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, _ ->
                            when {
                                downloadUrl.startsWith("blob:", ignoreCase = true) -> {
                                    val filename = guessDownloadName(
                                        downloadUrl, contentDisposition, mimetype
                                    )
                                    // 块级作用域函数不能用 JS 关键字做变量名,这里用 fn
                                    val fn = filename.replace("'", "\\'")
                                    val mt = (mimetype ?: "application/octet-stream").replace("'", "\\'")
                                    // downloadUrl 来自网页,需同样转义单引号(与 fn/mt 一致),防 JS 字面量注入
                                    val du = downloadUrl.replace("'", "\\'")
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
                                    evaluateJavascript(js, null)
                                }

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

                                else -> {
                                    Toast.makeText(
                                        context, "暂不支持下载此类型的链接", Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                        loadUrl(url)
                    }
                },
                update = { web ->
                    // 运行时切换主题:重新应用算法深色设置,即时生效
                    applyDarkTheme(web.settings, darkTheme)
                },
                modifier = Modifier.fillMaxSize()
            )

            // 顶部加载进度条(2dp 细线,加载完成淡出,无背景轨道)
            TopProgressBar(loading = loading, progress = { progress / 100f })
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
    val filename = guessDownloadName(params.url, params.contentDisposition, params.mimetype)
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
            Toast.makeText(context, "下载服务不可用", Toast.LENGTH_SHORT).show()
            return
        }
        dm.enqueue(request)
        Toast.makeText(context, "开始下载:$filename", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        // 兜底:非法 URI / 权限 / 路径等任何异常都不再让 App 崩溃
        Toast.makeText(context, "下载失败:${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 推断下载文件名。blob: URL 没有文件名信息,按 mimetype 生成"下载图片.<ext>"。
 */
private fun guessDownloadName(
    url: String,
    contentDisposition: String?,
    mimetype: String?
): String {
    if (!url.startsWith("blob:", ignoreCase = true)) {
        return URLUtil.guessFileName(url, contentDisposition, mimetype)
    }
    // blob: 无法从 URL 取扩展名,按 mime 推断
    val ext = android.webkit.MimeTypeMap.getSingleton()
        .getExtensionFromMimeType(mimetype) ?: "bin"
    // 中文名更友好:image/* → "下载图片",其余 → "下载文件"
    val base = if (mimetype?.startsWith("image/") == true) "下载图片" else "下载文件"
    return "$base.$ext"
}

/**
 * 把 base64 数据写入公共「下载」目录(blob 下载专用)。
 *
 * - API ≥ 29:scoped storage 下不能直接写公共目录,改用 MediaStore(Downloads)。
 * - API < 29:走传统 File 路径 + MediaScanner 扫描。
 *
 * [data] 为 null 表示网页侧 fetch 失败 —— 提示后返回,不写空文件。
 */
private fun saveBlob(context: Context, filename: String, mimetype: String?, data: String?) {
    if (data.isNullOrEmpty()) {
        Toast.makeText(context, "下载失败:网页未能提供文件数据", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val bytes = Base64.decode(data, Base64.DEFAULT)
        val displayName = filename.ifBlank { "下载文件.bin" }
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
                Toast.makeText(context, "保存失败:无法创建文件", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(context, "已保存:$displayName", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "保存失败:${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
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
    @JavascriptInterface
    fun save(filename: String, mimetype: String?, data: String?) {
        onSave(filename, mimetype.takeIf { !it.isNullOrBlank() }, data)
    }
}

/** 系统分享:把当前页 URL 作为纯文本交给系统分享面板。 */
private fun shareUrl(context: Context, title: String, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(intent, "分享"))
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
                Toast.makeText(context, "未找到可打开此链接的应用", Toast.LENGTH_SHORT).show()
            }
        } else {
            // weixin://、mailto:、tel:、sms: 等普通自定义 scheme
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            )
        }
    } catch (e: android.content.ActivityNotFoundException) {
        Toast.makeText(context, "未找到可打开此链接的应用", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "无法打开此链接", Toast.LENGTH_SHORT).show()
    }
}

/** 应用网页深色模式:优先用网页自带深色主题,无则算法深色(自动转深)。 */
private fun applyDarkTheme(settings: android.webkit.WebSettings, darkTheme: Boolean) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, darkTheme)
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureWebSettings(darkTheme: Boolean) {
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
        cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        // 安全加固:显式关闭文件/内容访问(默认 false,此处声明防后续误开)
        allowFileAccess = false          // 禁止 file:// 内容访问
        allowContentAccess = false       // 禁止 content:// 访问(本 App 无需)
        mediaPlaybackRequiresUserGesture = true  // 禁止页面自动播放音视频
        // User-Agent 用默认浏览器 UA,避免被网站黑名单挡
        userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
    applyDarkTheme(settings, darkTheme)
}
