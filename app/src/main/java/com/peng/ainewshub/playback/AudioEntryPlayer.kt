package com.peng.ainewshub.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.PowerManager

/**
 * 流水线预生成播报音频的单条播放器 —— MediaPlayer 流式播 gitcode CDN MP3
 * (REST API raw 端点,公开仓库匿名可读,与归档 JSON 同端点,WAF 合规)。
 *
 * 一个实例只服务一个条目,队列推进时由服务销毁重建;**真暂停/续播**
 * (pause() 保留播放器与已缓冲数据,resume() 原地续播不二次请求网络),
 * 与系统 TTS 路径「stop + 重读」的兜底语义区分。
 *
 * 回调时机:prepare 成功自动起播;播完 [onCompletion] 推进队列;任何失败
 * (数据源/网络/解码)经 [onError] 上抛,由服务决定回落系统 TTS —— 播放器
 * 只上报不决策。回调在主线程生效(构造方在主线程,MediaPlayer 事件回调
 * 落构造线程的 Looper)。
 */
class AudioEntryPlayer(
    context: Context,
    url: String,
    private val onCompletion: () -> Unit,
    private val onError: (String) -> Unit
) {

    private var player: MediaPlayer? = null
    private var prepared = false

    /** prepare 完成前被暂停:起播动作挂起,resume() 时补 start。 */
    private var pauseBeforeStart = false

    init {
        try {
            val mp = MediaPlayer()
            player = mp
            // 息屏通勤场景 CPU 不睡(前台服务已保进程,wake lock 是 MediaPlayer 流播标配)
            mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            // 与服务的 AudioFocus 属性同源(USAGE_MEDIA + SPEECH)
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                prepared = true
                if (!pauseBeforeStart) it.start()
            }
            mp.setOnCompletionListener { onCompletion() }
            mp.setOnErrorListener { _, what, extra ->
                onError("MediaPlayer error $what/$extra")
                true // 已按「回落系统 TTS」处置,不让系统再弹播放错误
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            // setDataSource/prepareAsync 抛出的同步异常(非法 URL 等)同样走回落
            onError(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 暂停:保留播放器与缓冲,可原地续播(prepare 未完成则挂起起播)。 */
    fun pause() {
        pauseBeforeStart = true
        runCatching { player?.takeIf { prepared }?.pause() }
    }

    /**
     * 原地续播。返回是否成功 —— false 表示播放器已不在位/未就绪,
     * 调用方应销毁重起当前条(而非误判为已续播)。
     */
    fun resume(): Boolean {
        val mp = player ?: return false
        if (!prepared) return false
        pauseBeforeStart = false
        runCatching { mp.start() }
        return true
    }

    /** 释放资源(队列推进/停止/回落),幂等。 */
    fun release() {
        runCatching { player?.release() }
        player = null
        prepared = false
    }
}
