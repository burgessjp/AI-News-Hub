package com.peng.ainewshub.data

/**
 * JSON 解析通用扩展助手。
 *
 * 收口此前在 NewsItem / HackerNewsStory / HotTopic / HackerNewsRepository 各自重复
 * 的 null 过滤逻辑(org.json 的 optString 对 JSON null 返回字面字符串 "null",非空,
 * 故需额外过滤)。
 */

/**
 * 过滤 JSON null / 空白字符串:返回首个非空且非字面 "null" 的值,否则 null。
 *
 * 用于 org.json 的 optString 返回值清洗:
 * - JSON 字段缺失或为 null → optString 返回 "" 或 "null" → 本函数返回 null
 * - 正常字符串 → 原样返回(保留两端空格由调用方决定是否 trim)
 */
fun String?.asClean(): String? = this?.takeIf { it.isNotBlank() && it != "null" }
