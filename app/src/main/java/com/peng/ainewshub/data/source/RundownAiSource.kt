package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.RundownAiArticle

/**
 * The Rundown AI 数据源抽象 —— [RundownAiRepository](实时,jsoup)与
 * [RundownAiArchiveRepository](gitcode 归档)的共同接口。
 *
 * 与 [StormzhangAiNewsSource] 平行:双模式源,ViewModel 按
 * [SourceMode] 选择实现(与 LinuxDo「只实时」/ Product Hunt「只归档」不同,
 * The Rundown AI 走标准双模式)。
 */
interface RundownAiSource {
    suspend fun fetch(): RundownAiResult
    suspend fun forceRefresh(): RundownAiResult
}

/**
 * The Rundown AI 抓取结果(对齐 [com.peng.ainewshub.data.StormzhangAiNewsResult])。
 *
 * @param fetchedAt 数据落盘时刻(实时源:抓取时刻;归档源:快照 fetched_at_ms)
 * @param articles  近况 newsletter 文章列表(首页约 16 篇)
 */
data class RundownAiResult(
    val fetchedAt: Long,
    val articles: List<RundownAiArticle>
)
