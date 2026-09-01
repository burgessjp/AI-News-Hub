package com.peng.ainewshub.data.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.peng.ainewshub.MainActivity
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.i18n.AppLocale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 后台下载状态(全进程单份,[UpdateDownloadService.state] 回流 UI)。 */
sealed interface UpdateDownloadState {
    /** 空闲(未下载 / 已取消 / 已消费)。 */
    data object Idle : UpdateDownloadState

    /** 下载中:[progress] 为 0..1,总长度未知(不确定进度)时为 null。 */
    data class Downloading(val version: String, val progress: Float?) : UpdateDownloadState

    /** 下载完成,APK 就绪可安装([apk] 被系统清缓存后由消费方校验存在性)。 */
    data class Done(val version: String, val apk: File) : UpdateDownloadState

    /** 下载失败(网络/磁盘),弹窗内可重试。 */
    data class Failed(val version: String) : UpdateDownloadState
}

/**
 * 应用内更新后台下载前台服务 —— 承载 [UpdateDownloader.download] 的宿主进程,
 * 让用户关弹窗 / 离开关于页 / App 退后台后下载继续(此前下载协程挂在关于页
 * 组合作用域上,页面一退即取消)。
 *
 * 结构对齐 [com.peng.ainewshub.playback.TtsPlaybackService] 的纪律:
 *  - 任何 [onStartCommand] 路径先升前台(5 秒红线)再处理动作;类型 dataSync
 *    (targetSdk 34+ 强制声明,manifest 已配 FOREGROUND_SERVICE(_DATA_SYNC) 权限);
 *  - 仅由前台 UI 点击启动(companion [start]),取消可经通知栏 action(通知
 *    PendingIntent 走系统临时放行,无后台自启限制问题);
 *  - 状态经 companion 的 [state] StateFlow 回流弹窗(关于页跨页面重进可见);
 *  - 下载完成:前台进度通知撤下,改发「点击安装」常驻通知(contentIntent 直连
 *    [MainActivity] extra,Android 12+ 禁止通知经 service/receiver 转跳 Activity)。
 *
 * 边界:
 *  - 进程死亡:companion 状态随进程复位回 Idle,弹窗回「待下载」重来(与旧版
 *    行为一致,小 APK 可接受);
 *  - 通知权限未授权(API 33+,本 App 经设置页 opt-in):通知被系统静默丢弃,
 *    但前台服务与下载照常,用户回 App 经弹窗 Done 态安装;
 *  - dataSync 类型 Android 15 起 6 小时运行上限,APK 体量远不及。
 */
class UpdateDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downloadJob: Job? = null

    /** 进度通知节流基准(elapsedRealtime);~4Hz 平滑刷新不刷爆通知管线。 */
    private var lastProgressNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先升前台(5 秒红线)再处理动作:版本优先取「已在下载的」,其次本次 START
        // extras,都没有则不确定进度占位(START 分支随即以真实参数刷新)
        val stateVersion = (_state.value as? UpdateDownloadState.Downloading)?.version
        startForegroundWithNotification(stateVersion ?: intent?.getStringExtra(EXTRA_VERSION) ?: "")
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val version = intent.getStringExtra(EXTRA_VERSION)
                if (url.isNullOrBlank() || version.isNullOrBlank()) {
                    _state.value = UpdateDownloadState.Idle
                    stopSelf()
                    return START_NOT_STICKY
                }
                startDownload(url, version)
            }
            ACTION_CANCEL -> cancelDownload()
            // 空启动(理论不可达):无事可做即退
            else -> if (downloadJob == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 主动 abort 底层请求,避免阻塞中的 socket read 悬到 20s 超时才结束
        UpdateDownloader.cancel()
        serviceScope.cancel()
        // 兜底:服务被系统杀时 UI 不残留「下载中」(正常收尾路径此刻已是终态)
        val s = _state.value
        if (s is UpdateDownloadState.Downloading) _state.value = UpdateDownloadState.Failed(s.version)
        super.onDestroy()
    }

    private fun startDownload(url: String, version: String) {
        // 同版本去重:下载中重复 START(重进关于页再点等)忽略
        val s = _state.value
        if (s is UpdateDownloadState.Downloading && s.version == version) return
        downloadJob?.cancel()
        UpdateDownloader.cancel()
        _state.value = UpdateDownloadState.Downloading(version, null)
        downloadJob = serviceScope.launch {
            try {
                val apk = UpdateDownloader.download(this@UpdateDownloadService, url, version) { read, total ->
                    val progress = if (total > 0) read.toFloat() / total else null
                    _state.value = UpdateDownloadState.Downloading(version, progress)
                    notifyProgressThrottled(version, progress)
                }
                _state.value = UpdateDownloadState.Done(version, apk)
                showDoneNotification()
            } catch (e: CancellationException) {
                // 用户取消:状态与通知已由 [cancelDownload] 处置(置 Idle + 停服务),只收尾
                throw e
            } catch (_: Exception) {
                // 仅在仍处「下载中」时置失败:取消路径 socket abort 抛出的 IOException
                // 是取消副产品,不得把 Idle 回写成 Failed
                if (_state.value is UpdateDownloadState.Downloading) {
                    _state.value = UpdateDownloadState.Failed(version)
                }
            } finally {
                stopSelf()
            }
        }
    }

    private fun cancelDownload() {
        // 先置 Idle 再取消:下载协程被 abort 后抛的异常才不会覆盖终态(见 startDownload)
        _state.value = UpdateDownloadState.Idle
        downloadJob?.cancel()
        UpdateDownloader.cancel()
        stopSelf()
    }

    // ===== 通知 =====

    private fun startForegroundWithNotification(version: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFY_ID, buildProgressNotification(version, null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFY_ID, buildProgressNotification(version, null))
        }
    }

    private fun notifyProgressThrottled(version: String, progress: Float?) {
        val now = SystemClock.elapsedRealtime()
        // 完成态(>=1f)与不确定进度立即刷新,其余 250ms 节流
        if (progress != null && progress < 1f && now - lastProgressNotifyAt < 250L) return
        lastProgressNotifyAt = now
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFY_ID, buildProgressNotification(version, progress))
    }

    private fun buildProgressNotification(version: String, progress: Float?) =
        buildBaseNotification().apply {
            val localized = AppLocale.wrap(this@UpdateDownloadService)
            setContentTitle(
                if (version.isEmpty()) localized.getString(R.string.update_notification_channel_name)
                else localized.getString(R.string.update_notification_downloading, version)
            )
            if (progress != null) {
                setProgress(100, (progress * 100).toInt(), false)
            } else {
                setProgress(0, 0, true)
            }
            setOngoing(true)
            setOnlyAlertOnce(true)
            // 取消走 PendingIntent.getService(通知交互进系统临时放行,同 TtsPlaybackService)
            addAction(
                NotificationCompat.Action(
                    0,
                    localized.getString(R.string.common_cancel),
                    PendingIntent.getService(
                        this@UpdateDownloadService, 1,
                        Intent(this@UpdateDownloadService, UpdateDownloadService::class.java)
                            .setAction(ACTION_CANCEL),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            )
        }.build()

    /**
     * 「下载完成,点击安装」常驻通知:前台进度通知会随 stopForeground 撤下,完成态
     * 改经 NotificationManager 普通通知存活(autoCancel)。点击直连 [MainActivity]
     * extra —— Android 12+ 禁止通知经 service/receiver trampoline 拉起 Activity。
     */
    private fun showDoneNotification() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val localized = AppLocale.wrap(this)
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_INSTALL_UPDATE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = buildBaseNotification()
            .setContentTitle(localized.getString(R.string.update_notification_done_title))
            .setContentText(localized.getString(R.string.update_notification_done_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        // 通知权限未授权时系统静默丢弃(同 DailyUpdateNotifier 口径),不另行检查
        NotificationManagerCompat.from(this).notify(NOTIFY_ID, notification)
    }

    private fun buildBaseNotification(): NotificationCompat.Builder {
        ensureChannel()
        // 下载中点击回 App(singleTask 带回前台,不指定页面)
        val backToApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentIntent(backToApp)
    }

    /** 懒建通知渠道(幂等);API 26 以下无渠道概念直接跳过。 */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val name = AppLocale.wrap(this).getString(R.string.update_notification_channel_name)
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "update_download"

        /** 固定通知 id:进度与完成通知同 id,后者覆盖前者。 */
        private const val NOTIFY_ID = 1003

        private const val ACTION_START = "com.peng.ainewshub.update.DOWNLOAD"
        private const val ACTION_CANCEL = "com.peng.ainewshub.update.CANCEL"
        private const val EXTRA_URL = "url"
        private const val EXTRA_VERSION = "version"

        private val _state = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)

        /** 后台下载状态(进程级单份),关于页弹窗经 collectAsStateWithLifecycle 订阅。 */
        val state: StateFlow<UpdateDownloadState> = _state.asStateFlow()

        /**
         * 启动后台下载。仅由前台 UI 点击调用(关于页弹窗);同一版本重复调用被
         * [onStartCommand] 的 START 分支去重。
         */
        fun start(context: Context, url: String, version: String) {
            val intent = Intent(context, UpdateDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_VERSION, version)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 取消下载(弹窗主按钮 / 通知栏取消 action 共用)。 */
        fun cancel(context: Context) {
            // 普通startService 即可:调用点必在前台(弹窗)或通知交互(临时放行)
            context.startService(
                Intent(context, UpdateDownloadService::class.java).setAction(ACTION_CANCEL)
            )
        }

        /** 已下载 APK 失效(被系统清缓存等)时复位回 Idle,供通知安装入口调用。 */
        fun invalidateDone() {
            if (_state.value is UpdateDownloadState.Done) _state.value = UpdateDownloadState.Idle
        }
    }
}
