package com.peng.ainewshub.data

import com.peng.ainewshub.data.source.ArchiveHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 语音速报「预生成音频」清单的单条 —— 对应 index.json `latest_audio.entries[i]`。
 *
 * @param file 仓库根相对路径(如 `audio/2026-08-19/entry-00.mp3`),经
 *   [ArchiveHttpClient.audioUrl] 拼 CDN 直读 URL
 * @param title 条目标题(清单参考值;App 侧播放列表仍用本地化标题,不用它)
 * @param durationMs 音频时长(毫秒,ffprobe 预读)
 * @param bytes 文件大小(字节)
 */
data class AudioEntry(
    val file: String,
    val title: String,
    val durationMs: Long,
    val bytes: Long
)

/**
 * 语音速报预生成音频清单 —— 流水线 `scripts/tts_broadcast.py` 用 Qwen3-TTS
 * (神经语音)按总览内容预合成的 MP3 列表,gitcode 数据仓库托管,App 流式播放。
 *
 * @param generatedAt 生成时刻(毫秒)—— 与 `latest_overview.generatedAt` 严格
 *   同值(音频按总览文本合成,批次绑定),App 侧据此判定新鲜度
 * @param voice 音色名(信息字段,如 "serena")
 * @param entries 播报条目(综述条在前,顺序与总览播放列表一致)
 */
data class AudioBroadcast(
    val generatedAt: Long,
    val voice: String,
    val entries: List<AudioEntry>
)

/**
 * 预生成音频清单 Repository —— 与 [OverviewRepository] 同范式:**流水线预生成、
 * App 只读归档**。只做反序列化与新鲜度判定,不拉音频本体(播放由
 * TtsPlaybackService 经 MediaPlayer 流式拉 CDN)。
 *
 * 清单缺失/批次滞后/条数对不上 → 一律返回 null,调用方(总览播报入口)回落
 * 系统 TTS —— 预生成音频是音质增强项,不是依赖项,任何情况下不拦播报。
 */
class BroadcastRepository {

    /**
     * 拉预生成音频清单。
     *
     * @param overviewGeneratedAt 当前展示总览的 generatedAt;传入时做新鲜度判定 ——
     *   清单不一致(音频是旧批次,如总览刚刷新而音频尚未合成/断网读到旧盘数据)
     *   同样返回 null,避免「看着新总览、听着旧播报」
     * @param force true 绕过 index.json 2 分钟缓存
     * @return 清单;不存在/无有效条目/批次不新鲜为 null;网络/解析失败原样抛
     *   (由调用方 runCatching 兜成回落)
     */
    suspend fun load(
        overviewGeneratedAt: Long? = null,
        force: Boolean = false
    ): AudioBroadcast? = withContext(Dispatchers.IO) {
        val json: JSONObject = ArchiveHttpClient.fetchLatestAudio(force) ?: return@withContext null
        val generatedAt = json.optLong("generatedAt", 0L)
        if (overviewGeneratedAt != null && generatedAt != overviewGeneratedAt) {
            return@withContext null
        }
        val arr = json.optJSONArray("entries") ?: return@withContext null
        val entries = (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val file = obj.optString("file").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AudioEntry(
                file = file,
                title = obj.optString("title"),
                durationMs = obj.optLong("durationMs", 0L),
                bytes = obj.optLong("bytes", 0L)
            )
        }
        if (entries.isEmpty()) null else AudioBroadcast(generatedAt, json.optString("voice"), entries)
    }
}
