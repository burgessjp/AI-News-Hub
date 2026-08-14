package com.peng.ainewshub.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 收藏(稍后读)单条 —— 一条记录对应一个唯一 URL(去重)。
 *
 * 字段语义:
 *  - [url]:主键 & 去重键。重复收藏同一 URL 即取消(toggle 语义)。
 *  - [title]:收藏时的页面标题(WebView 已解析出真实标题时用之;
 *    之后 WebView 回写真实标题也会同步更新本字段)。
 *  - [host]:域名,从 URL 解析,用于列表副行展示与占位块字母。
 *  - [source]:来源标签,记录用户从哪个入口进的文章(如 "GitHub Trending"/"AI HOT")。
 *    可空 —— 少数调用点未标注时为 null,UI 不显示该标签即可。
 *  - [savedAt]:收藏时刻的毫秒时间戳,主排序键(倒序),已建索引。
 *
 * 与浏览历史的差异:收藏是用户主动动作,无 visitCount;
 * 缓存清理的任何开关都不触碰本表。
 */
@Entity(
    tableName = "favorites",
    indices = [Index(value = ["savedAt"])]
)
data class FavoriteEntity(
    @PrimaryKey
    val url: String,
    val title: String,
    val host: String,
    val source: String?,
    val savedAt: Long
)
