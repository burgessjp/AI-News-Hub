package com.peng.ainewshub.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地搜索索引单条 —— 一条记录对应一个唯一 URL(去重)。
 *
 * 由各数据源 Repository 在成功取数后回填(见 [SearchIndexRepository]),
 * 「搜索」页的「本地」模式按 title/summary LIKE 查询本表。
 *
 * 字段语义:
 *  - [url]:主键 & 去重键。取「列表条目点击时实际打开的 URL」(与浏览历史/
 *    已读判定同一 URL),保证搜索结果点击后已读状态联动一致。跨源同 URL 覆盖更新。
 *  - [title] / [summary]:可搜索文本(HackerNews 等无摘要的源 summary 为空串)。
 *  - [source]:源标识。归档源存 [SourceKeys] key(UI 经 sourceMeta 转本地化标题);
 *    aihot 动态流存条目自带的原始来源名(如 "TechCrunch",空时回退 key)。
 *  - [indexedAt]:最近一次回填时刻,搜索结果排序键(倒序,近似新鲜度)。
 *    8 源条目时间字段异构,不逐源解析发布时间,统一用写入时刻。
 */
@Entity(
    tableName = "search_items",
    indices = [Index(value = ["indexedAt"])]
)
data class SearchItemEntity(
    @PrimaryKey
    val url: String,
    val title: String,
    val summary: String,
    val source: String,
    val indexedAt: Long
)
