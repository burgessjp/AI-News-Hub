package com.peng.ainewshub.data.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * APK 应用内直装链路(与 [UpdateChecker] 配套):
 * 下载(OkHttp 流式写缓存目录 + 进度回调)→ [install] 拉起系统安装器。
 *
 * 取代此前「跳 Release 网页找 APK 资产链接」的流程,用户全程不出 App。
 *
 * 约束与取舍:
 *  - 必须从 [HttpClients.base] 派生并清零 callTimeout:base 的 30s 总超时是为
 *    普通接口设的,APK 数 MB 在慢网下轻松超过;connect/read 逐次超时保留即可
 *    (停滞 20s 仍会断,避免死等),且派生实例与 base 共享连接池;
 *  - 下载落 app 私有缓存(cacheDir/updates/):无任何存储权限问题,系统低存储时
 *    可自动回收;每次下载前清空目录,不积压旧版本 APK;
 *  - 下载经 FileProvider(content://)交给系统安装器,Android 7+ 强制 file://
 *    禁止直发 FileUriExposedException;
 *  - Android 8+ 安装未知来源应用需用户在系统设置里授权本 App(运行时不可弹窗
 *    申请,只能跳设置页),见 [InstallState.NeedPermission]。
 */
object UpdateDownloader {

    /** APK 直链下载目标目录(cacheDir 下,FileProvider 经 cache-path 暴露)。 */
    private const val UPDATE_DIR = "updates"

    /** 下载专用 client:仅放宽 callTimeout,其余(连接池/UA 策略同 base)不变。 */
    private val downloadClient: OkHttpClient by lazy {
        HttpClients.base.newBuilder()
            .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

    /** 当前进行中的下载(同一时刻只会有一个,弹窗按钮独占触发)。 */
    @Volatile
    private var activeCall: Call? = null

    /**
     * 取消进行中的下载:立即 abort 底层请求,解除阻塞中的 socket read。
     *
     * 调用方协程直接取消时(read 阻塞中,逐块 ensureActive 检查不到)线程最多
     * 悬到 read 超时 20s 后自然结束,无泄漏、对用户不可见 —— 故不做更花哨的联动。
     */
    fun cancel() {
        activeCall?.cancel()
    }

    /** 安装发起结果。 */
    sealed interface InstallState {
        /** 已拉起系统安装器。 */
        data object Started : InstallState

        /** Android 8+ 未获「安装未知应用」授权,需先跳系统设置(见 [requestInstallPermission])。 */
        data object NeedPermission : InstallState
    }

    /**
     * 流式下载 APK 到私有缓存目录。
     *
     * @param url Release 资产直链([UpdateChecker.UpdateInfo.downloadUrl])
     * @param version 版本号(用于落盘文件名)
     * @param onProgress 进度回调(已读字节,总字节;总字节未知时为 -1,UI 走不确定进度)
     * @return 下载完成的 APK 文件
     * @throws Exception 网络/磁盘错误或调用方协程取消(取消时底层请求同步 abort)
     */
    suspend fun download(
        context: Context,
        url: String,
        version: String,
        onProgress: (Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        // 清空历史:同一目录只保留当前这次下载的 APK
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "ainewshub-v$version.apk")

        val call = downloadClient.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", HttpClients.DEFAULT_BROWSER_UA)
                .build()
        )
        activeCall = call
        try {
            call.execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                val total = resp.body?.contentLength() ?: -1L
                var read = 0L
                resp.body?.byteStream()?.use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            // 每写一块检查取消,让「取消下载」即时生效
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            onProgress(read, total)
                        }
                    }
                } ?: error("empty body")
                target
            }
        } finally {
            activeCall = null
        }
    }

    /**
     * 拉起系统安装器安装已下载的 APK。
     *
     * Android 8+ 首次安装前需用户在系统设置授予本 App「安装未知应用」权限,
     * 未授予时返回 [InstallState.NeedPermission],调用方提示后经
     * [requestInstallPermission] 跳设置页;授权返回后再点安装即可。
     */
    fun install(context: Context, apk: File): InstallState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return InstallState.NeedPermission
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return InstallState.Started
    }

    /** 跳系统「安装未知应用」设置页,让用户为本 App 开启安装权限。 */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }
}
