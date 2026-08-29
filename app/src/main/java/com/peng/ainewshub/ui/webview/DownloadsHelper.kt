package com.peng.ainewshub.ui.webview

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.peng.ainewshub.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebView 下载链路 —— 网页发起的文件下载与长按图片保存共用的全家桶。
 *
 * 按形态分流(分发处在 WebViewScreen 的 DownloadListener / 长按菜单):
 *  - http(s):系统 DownloadManager(见 [handleDownload] 的权限流程)
 *  - blob:   注 JS 读成 base64 经 [BlobSaver] 回传,解码后 [saveBlob] 写文件
 *  - data:   base64 解码后同 blob 路径([saveDataUrl])
 *  - 其它:   Toast 提示不支持,不崩溃
 */

/** 待下载文件参数。被 DownloadListener 产出,经权限流程后交给 [enqueueDownload]。 */
internal data class DownloadParams(
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
internal fun handleDownload(
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
internal fun enqueueDownload(context: Context, params: DownloadParams) {
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
internal fun downloadBlob(
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
internal suspend fun saveDataUrl(context: Context, url: String) {
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
 * 挂起函数:Base64 解码与写盘在 IO 线程执行(canvas 导出图常见 1-5MB,主线程做
 * 会超帧预算甚至 ANR);须从主线程协程调用 —— 结果 Toast 留在调用方上下文(主线程)。
 */
internal suspend fun saveBlob(context: Context, filename: String, mimetype: String?, data: String?) {
    if (data.isNullOrEmpty()) {
        Toast.makeText(context, context.getString(R.string.webview_toast_download_no_data), Toast.LENGTH_SHORT).show()
        return
    }
    val outcome = withContext(Dispatchers.IO) {
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
                    ?: return@withContext BlobSaveOutcome.CreateFailed
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
            BlobSaveOutcome.Saved(displayName)
        } catch (e: Exception) {
            Log.w("Save", "保存失败", e)
            BlobSaveOutcome.Failed
        }
    }
    val msg = when (outcome) {
        is BlobSaveOutcome.Saved -> context.getString(R.string.webview_toast_saved, outcome.displayName)
        BlobSaveOutcome.CreateFailed -> context.getString(R.string.webview_toast_save_create_failed)
        BlobSaveOutcome.Failed -> context.getString(R.string.webview_toast_save_failed)
    }
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

/** [saveBlob] 的落盘结果:成功携带展示名;失败区分「建 MediaStore 记录失败」与「其他异常」。 */
private sealed interface BlobSaveOutcome {
    data class Saved(val displayName: String) : BlobSaveOutcome
    data object CreateFailed : BlobSaveOutcome
    data object Failed : BlobSaveOutcome
}

/**
 * 注入给网页的 JS 桥接对象 —— 把从 blob 读出的 base64 数据回传原生。
 *
 * @param onSave 回调: (文件名, mimetype, base64数据或null)
 */
internal class BlobSaver(
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
