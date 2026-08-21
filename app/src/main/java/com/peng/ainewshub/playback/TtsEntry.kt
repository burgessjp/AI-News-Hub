package com.peng.ainewshub.playback

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 语音速报的单条播报内容。
 *
 * [title] 供通知栏展示当前条目(纯展示,不朗读);[text] 是朗读正文 —— 拼装方
 * (总览/关注页)负责把内容组织成适合「听」的连贯文本;[id] 作 utteranceId,
 * 用于 TTS 回调对位,保证稳定唯一即可。
 */
@Parcelize
data class TtsEntry(
    val id: String,
    val title: String,
    val text: String
) : Parcelable
