package com.peng.ainewshub.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 浏览历史单条 —— 一条记录对应一个唯一 URL(去重)。
 *
 * 字段语义:
 *  - [url]:主键 & 去重键。同一 URL 再次打开时 upsert,不新增行。
 *  - [title]:网页真实标题(WebView 加载完成后回写更新)。
 *  - [host]:域名,从 URL 解析,用于「按域名分组」与列表副行展示。
 *  - [source]:来源标签,记录用户是从哪个入口点开的(如 "GitHub Trending"/"日报"/"AI HOT")。
 *    可空 —— 少数调用点暂未标注时为 null,UI 不显示该标签即可。
 *  - [visitedAt]:最近一次访问的毫秒时间戳,主排序键(倒序),已建索引。
 *  - [visitCount]:累计打开次数,>1 时 UI 显示 ×N 徽章。
 */
@Entity(
    tableName = "browse_history",
    indices = [Index(value = ["visitedAt"])]
)
data class BrowseHistoryEntity(
    @PrimaryKey
    val url: String,
    val title: String,
    val host: String,
    val source: String?,
    val visitedAt: Long,
    val visitCount: Int = 1
)
