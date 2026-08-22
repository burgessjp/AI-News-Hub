package com.peng.ainewshub.playback

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 语音速报的单条播报内容。
 *
 * [title] 供通知栏展示当前条目(纯展示,不朗读);[text] 是朗读正文 —— 拼装方
 * (总览/关注页)负责把内容组织成适合「听」的连贯文本;[id] 作 utteranceId,
 * 用于 TTS 回调对位,保证稳定唯一即可。
 *
 * [audioUrl] 非空表示该条有流水线预生成的神经语音 MP3(Qwen3-TTS,总览
 * 速报专属;关注速报是端上实时拼的个性化内容,恒走系统 TTS)—— 播放服务优先
 * 流式播音频,失败回落系统 TTS 朗读 [text](两侧文本同规则拼装,听感一致);
 * 空值直接系统 TTS。[durationMs] 为预生成音频时长,预读展示用。
 */
@Parcelize
data class TtsEntry(
    val id: String,
    val title: String,
    val text: String,
    val audioUrl: String? = null,
    val durationMs: Long = 0L
) : Parcelable
