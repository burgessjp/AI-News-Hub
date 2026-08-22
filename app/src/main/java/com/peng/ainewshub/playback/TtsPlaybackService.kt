package com.peng.ainewshub.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.peng.ainewshub.MainActivity
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.i18n.AppLocale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 播放状态快照 —— 供 App 内浮窗([TtsFloatingPill])消费的可观察状态。
 * 服务存活且有播放列表时 [active] 为 true,浮窗据此显隐;
 * 服务被系统杀死时由 [TtsPlaybackService.onDestroy] 兜底复位,防止浮窗残留。
 */
data class TtsPlaybackState(
    val active: Boolean = false,
    val index: Int = 0,
    val total: Int = 0,
    /** 当前条目标题(仅展示,不朗读)。 */
    val title: String = "",
    val paused: Boolean = false
)

/**
 * 语音速报前台服务 —— 按队列播报当日内容(总览速报/我的关注速报),通知栏提供
 * 「上一条 / 播放暂停 / 下一条 / 停止」控制。
 *
 * 播放双通道(用户无感):
 *  - 条目带 [TtsEntry.audioUrl](总览速报,流水线 Qwen3-TTS 预生成)→
 *    [AudioEntryPlayer] 流式播 CDN MP3,真暂停/原地续播,任何失败回落本条系统 TTS;
 *  - 纯文本条目(关注速报/回落)→ 系统 TextToSpeech 引擎朗读。
 *
 * 项目首例前台服务,纪律:
 *  - 任何 [onStartCommand] 路径先升前台(5 秒红线)再处理动作;类型 mediaPlayback
 *    (targetSdk 34+ 强制,manifest 已声明 FOREGROUND_SERVICE(_MEDIA_PLAYBACK) 权限);
 *  - 仅由前台 UI 点击启动(无后台自启,不涉后台启动限制);
 *  - 系统通道语言恒简体中文(流水线内容恒中文,与通知/摘要同策略);预生成音频
 *    本身即中文,不依赖引擎存在;
 *  - 系统 TTS 通道的「暂停」实现为 stop + 记住 index(引擎级 pause 兼容性参差),
 *    恢复时重读当前条 —— 播报条目多在百字级,重听一句可接受,换取全引擎一致的
 *    确定行为;音频通道为播放器级真暂停,原地续播不重读;
 *  - AudioFocus 用 GAIN_TRANSIENT_MAY_DUCK:背景音乐压低而非打断;
 *  - 播放状态经 companion 的 [state] StateFlow 回流 App 内浮窗(浮窗控制走
 *    companion 的 prev/playPause/next/stop 便捷方法,与通知栏 action 同一通路)。
 */
class TtsPlaybackService : Service() {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var playlist: List<TtsEntry> = emptyList()
    private var index = 0
    private var paused = false

    /** 当前条目的预生成音频播放器(音频通道在位时非空;推进/回落即销毁重建)。 */
    private var audioPlayer: AudioEntryPlayer? = null

    /** 当前条是否走音频通道(决定暂停/续播的策略与回落方向)。 */
    private var usingAudio = false

    /** 音频失败回落系统 TTS、但引擎尚未就绪时的暂存条目(TTS init 回调里消费)。 */
    private var fallbackOnReady: TtsEntry? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先升前台(5 秒红线),再处理动作
        startForegroundWithNotification()
        when (intent?.action) {
            ACTION_START -> {
                val list = safeEntries(intent)
                if (list.isEmpty()) {
                    stopPlayback()
                    return START_NOT_STICKY
                }
                // 换列表:清上一轮音频播放器与回落暂存;起播交给 playCurrent 分派 ——
                // 首条即音频时不预建系统 TTS(引擎缺失也不拦预生成音频的播放)
                audioPlayer?.release()
                audioPlayer = null
                usingAudio = false
                fallbackOnReady = null
                playlist = list
                index = 0
                paused = false
                playCurrent()
            }
            ACTION_PREV -> if (playlist.isNotEmpty()) {
                index = (index - 1).coerceAtLeast(0)
                paused = false
                playCurrent()
            }
            ACTION_PLAY_PAUSE -> if (playlist.isNotEmpty()) {
                if (paused) {
                    paused = false
                    // 音频通道:播放器仍在位则原地续播(真暂停成果);不在位(被系统
                    // 回收/未就绪)则销毁重起当前条。TTS 通道维持重读语义。
                    if (!(usingAudio && audioPlayer?.resume() == true)) playCurrent()
                } else {
                    paused = true
                    if (usingAudio) audioPlayer?.pause() else tts?.stop()
                    updateNotification()
                }
            }
            ACTION_NEXT -> if (playlist.isNotEmpty()) {
                if (index >= playlist.lastIndex) {
                    stopPlayback()
                    return START_NOT_STICKY
                }
                index++
                paused = false
                playCurrent()
            }
            ACTION_STOP -> {
                stopPlayback()
                return START_NOT_STICKY
            }
            // 空 intent(service 被杀重建的 sticky 回调,本服务非 sticky 正常不触发)
            else -> if (playlist.isEmpty()) stopPlayback()
        }
        updateNotification()
        publishState()
        return START_NOT_STICKY
    }

    /** 播放列表经 Bundle 传输,异常兜底为空(老进程残留 intent 等脏数据)。 */
    @Suppress("DEPRECATION")
    private fun safeEntries(intent: Intent): List<TtsEntry> = runCatching {
        intent.getParcelableArrayListExtra<TtsEntry>(EXTRA_ENTRIES).orEmpty().filter { it.text.isNotBlank() }
    }.getOrDefault(emptyList())

    /** 懒建 TTS;就绪后消费音频回落暂存(优先)或起播当前队列(纯文本首条先到时)。 */
    private fun ensureTtsAndPlay() {
        if (tts != null) {
            if (ttsReady) speakPendingOrCurrent()
            return
        }
        tts = TextToSpeech(applicationContext) { status ->
            val t = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                mainHandler.post { toastEngineMissing(); stopPlayback() }
                return@TextToSpeech
            }
            val lang = t.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                mainHandler.post { toastEngineMissing(); stopPlayback() }
                return@TextToSpeech
            }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    mainHandler.post { advanceAfterFinish() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    // 单条出错跳下一条,不让队列卡死
                    mainHandler.post { advanceAfterFinish() }
                }
            })
            ttsReady = true
            mainHandler.post { speakPendingOrCurrent() }
        }
    }

    /**
     * 播放当前条:带 [TtsEntry.audioUrl] 走音频通道(MediaPlayer 流式播,失败由
     * 播放器回调回落),否则系统 TTS(TTS 未建则先建,init 回调里补播)。
     */
    private fun playCurrent() {
        val entry = playlist.getOrNull(index) ?: return stopPlayback()
        updateNotification()
        publishState()
        val audioUrl = entry.audioUrl
        if (audioUrl != null) {
            usingAudio = true
            requestAudioFocus()
            audioPlayer?.release()
            audioPlayer = AudioEntryPlayer(
                this, audioUrl,
                onCompletion = { mainHandler.post { advanceAfterFinish() } },
                onError = { _ -> mainHandler.post { fallbackToSystemTts(entry) } }
            )
            return
        }
        usingAudio = false
        audioPlayer?.release()
        audioPlayer = null
        val t = tts ?: return ensureTtsAndPlay()
        if (!ttsReady) return
        requestAudioFocus()
        t.speak(entry.text, TextToSpeech.QUEUE_FLUSH, null, entry.id)
    }

    /** 预生成音频播放失败:当前条回落系统 TTS 重读(引擎未就绪则先建,回调里读)。 */
    private fun fallbackToSystemTts(entry: TtsEntry) {
        audioPlayer?.release()
        audioPlayer = null
        usingAudio = false
        val t = tts
        if (ttsReady && t != null) {
            requestAudioFocus()
            updateNotification()
            publishState()
            t.speak(entry.text, TextToSpeech.QUEUE_FLUSH, null, entry.id)
        } else {
            fallbackOnReady = entry
            ensureTtsAndPlay()
        }
    }

    /** TTS 就绪后的统一起播口:音频回落暂存优先,否则(未暂停)播当前条。 */
    private fun speakPendingOrCurrent() {
        val pending = fallbackOnReady
        fallbackOnReady = null
        if (pending != null) {
            requestAudioFocus()
            updateNotification()
            publishState()
            tts?.speak(pending.text, TextToSpeech.QUEUE_FLUSH, null, pending.id)
        } else if (!paused && playlist.isNotEmpty()) {
            playCurrent()
        }
    }

    /** 一条读完/出错:未暂停则推进;已到末尾自然收尾(停服务、散通知)。 */
    private fun advanceAfterFinish() {
        if (paused) return
        if (index < playlist.lastIndex) {
            index++
            playCurrent()
        } else {
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        paused = true
        audioPlayer?.release()
        audioPlayer = null
        usingAudio = false
        fallbackOnReady = null
        runCatching { tts?.stop() }
        abandonAudioFocus()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        _state.value = TtsPlaybackState()
    }

    /** 状态回流统一出口:把服务内私有播放态快照进 companion 的 StateFlow 供浮窗消费。 */
    private fun publishState() {
        _state.value = TtsPlaybackState(
            active = true,
            index = index,
            total = playlist.size,
            title = playlist.getOrNull(index)?.title.orEmpty(),
            paused = paused
        )
    }

    override fun onDestroy() {
        audioPlayer?.release()
        audioPlayer = null
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
        abandonAudioFocus()
        // 兜底复位:stopPlayback 之外的销毁路径(系统杀服务)也不让浮窗残留
        _state.value = TtsPlaybackState()
        super.onDestroy()
    }

    // ===== 通知 ====

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceCompat.startForeground(
                this, NOTIFY_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFY_ID, buildNotification())
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFY_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val localized = AppLocale.wrap(this)
        val entry = playlist.getOrNull(index)
        // 单条播放(总览单段音频)没有序号概念,直接显标题
        val text = when {
            entry == null -> localized.getString(R.string.tts_notification_title)
            playlist.size > 1 -> localized.getString(
                R.string.tts_playing_index, index + 1, playlist.size
            ) + " · " + entry.title
            else -> entry.title.ifBlank { localized.getString(R.string.tts_notification_title) }
        }
        // 点击正文回 App(singleTask,无 extra 落默认总览 tab)
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 控制 action:requestCode 各不相同(小组件同款教训 —— PendingIntent 等价判断
        // 忽略 extras,靠 action + requestCode 双重区分)
        fun control(action: String, requestCode: Int, icon: Int, label: String) =
            NotificationCompat.Action(
                icon, label,
                PendingIntent.getService(
                    this, requestCode,
                    Intent(this, TtsPlaybackService::class.java).setAction(action),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(localized.getString(R.string.tts_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(control(ACTION_PREV, 1, R.drawable.ic_tts_prev, localized.getString(R.string.tts_action_prev)))
            .addAction(
                control(
                    ACTION_PLAY_PAUSE, 2,
                    if (paused) R.drawable.ic_tts_play else R.drawable.ic_tts_pause,
                    localized.getString(if (paused) R.string.tts_action_play else R.string.tts_action_pause)
                )
            )
            .addAction(control(ACTION_NEXT, 3, R.drawable.ic_tts_next, localized.getString(R.string.tts_action_next)))
            .addAction(control(ACTION_STOP, 4, R.drawable.ic_tts_stop, localized.getString(R.string.tts_action_stop)))
            .build()
    }

    /** 懒建通知渠道(幂等);API 26 以下无渠道概念直接跳过。 */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val name = AppLocale.wrap(this).getString(R.string.tts_channel_name)
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun toastEngineMissing() {
        Toast.makeText(this, getText(R.string.tts_engine_missing), Toast.LENGTH_LONG).show()
    }

    // ===== 音频焦点(MAY_DUCK:压低背景音乐而非打断) =====

    private fun requestAudioFocus() {
        val am = audioManager
            ?: getSystemService(AudioManager::class.java)?.also { audioManager = it }
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = audioFocusRequest ?: AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
                .also { audioFocusRequest = it }
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    companion object {
        private const val CHANNEL_ID = "tts_playback"

        /** 固定通知 id:重复启动覆盖而非堆叠。 */
        private const val NOTIFY_ID = 1002

        private const val ACTION_START = "com.peng.ainewshub.playback.START"
        private const val ACTION_PREV = "com.peng.ainewshub.playback.PREV"
        private const val ACTION_PLAY_PAUSE = "com.peng.ainewshub.playback.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.peng.ainewshub.playback.NEXT"
        private const val ACTION_STOP = "com.peng.ainewshub.playback.STOP"
        private const val EXTRA_ENTRIES = "entries"

        /**
         * 入口:替换播放列表并从头播。仅由前台 UI 点击调用(无后台自启);
         * 服务已在播时重复调用 = 换列表重播(先走 onStartCommand 再由 ACTION_START 重置)。
         */
        fun start(context: Context, entries: List<TtsEntry>) {
            if (entries.isEmpty()) return
            val intent = Intent(context, TtsPlaybackService::class.java)
                .setAction(ACTION_START)
                .putParcelableArrayListExtra(EXTRA_ENTRIES, ArrayList(entries))
            ContextCompat.startForegroundService(context, intent)
        }

        /** App 内浮窗消费的播放状态(无 DI 框架,companion 单例即服务的对外观察面)。 */
        private val _state = MutableStateFlow(TtsPlaybackState())
        val state: StateFlow<TtsPlaybackState> = _state.asStateFlow()

        // ===== 浮窗控制便捷方法:与通知栏 action 同一通路(仅 onStartCommand 分支不同) =====
        // 仅由前台 UI 调用:服务必已在运行(active=true 才显示浮窗)且 App 在前台,
        // 普通 startService 即可(不再走 startForegroundService,规避 5 秒前台红线误伤)。

        fun prev(context: Context) = send(context, ACTION_PREV)

        fun playPause(context: Context) = send(context, ACTION_PLAY_PAUSE)

        fun next(context: Context) = send(context, ACTION_NEXT)

        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            context.startService(
                Intent(context, TtsPlaybackService::class.java).setAction(action)
            )
        }
    }
}
