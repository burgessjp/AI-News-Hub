package com.peng.ainewshub.data

import androidx.compose.runtime.Immutable


import org.json.JSONObject

/**
 * OpenAI x Anthropic 厂商动态单条(来源:OpenAI RSS + Anthropic HTML 合并源)。
 *
 * 与 [ProductHunt] / [RundownAiArticle] / [HuggingFacePaper] / [StormzhangAiNews] 平行:
 * 第八个独立数据源。两家头部 AI 厂商(OpenAI 与 Anthropic)的官方博客/新闻合并而成,
 * 用户最关心的「发版/研究/政策」第一手来源,补齐现有源无厂商一方的缺口。数据由
 * [scripts/fetch_data.py] 的 fetch_openai_anthropic_news 分别抓 OpenAI RSS 与
 * Anthropic 列表页 HTML,合并后按发布时间倒序、取最新 20 条归档,App 端只读归档快照。
 *
 * 纯归档源:无 LIVE 实现(两家均无稳定公开 API,且 Anthropic 无官方 RSS)。
 * 不加 @Parcelize:点击走 [url](官方博客页,内置 WebView),URL 是普通字符串,
 * 无需跨页面传整个对象(与 [RundownAiArticle] 同套路)。
 *
 * @param rank        1 起的排名(由发布时间倒序决定)
 * @param title       文章标题
 * @param url         文章完整 HTTPS 地址(OpenAI 博客或 Anthropic 新闻页)
 * @param summary     英文摘要(RSS description / Anthropic 列表卡简介);可空
 * @param vendor      厂商,"OpenAI" / "Anthropic";UI 徽章用
 * @param category    分类标签,如 "Product" / "Research" / "Announcements";可空
 * @param publishedAt 发布时间 ISO,如 "2026-07-22T13:00:00Z";原样展示不做解析
 */
@Immutable

data class OpenAiAnthropicNews(
    val rank: Int,
    val title: String,
    val url: String,
    val summary: String = "",
    val vendor: String = "",
    val category: String = "",
    val publishedAt: String = ""
) {
    companion object {
        /**
         * 从归档快照的一个 item JSON 抽取动态信息。
         *
         * 字段名对齐 [scripts/fetch_data.py] 的 fetch_openai_anthropic_news 落盘结构。
         * title 与 url 必有(对齐抓取端:缺则跳过);其余字段缺失走类型默认值。
         *
         * @return title 或 url 为空返回 null,调用方 mapNotNull 跳过
         */
        fun fromJson(obj: JSONObject, fallbackRank: Int): OpenAiAnthropicNews? {
            val title = obj.optString("title").trim()
            val url = obj.optString("url").trim()
            if (title.isBlank() || url.isBlank()) return null
            return OpenAiAnthropicNews(
                rank = obj.optInt("rank", fallbackRank),
                title = title,
                url = url,
                summary = obj.optString("summary").trim(),
                vendor = obj.optString("vendor").trim(),
                category = obj.optString("category").trim(),
                publishedAt = obj.optString("publishedAt").trim()
            )
        }
    }
}

/**
 * OpenAI x Anthropic 厂商动态拉取结果(带数据新鲜度),对齐 [ProductHuntResult]。
 *
 * @param fetchedAt 数据落盘时刻(归档快照的 fetched_at_ms)
 * @param articles  厂商动态列表(最新 20 条,按发布时间倒序)
 */
data class OpenAiAnthropicNewsResult(
    override val fetchedAt: Long,
    val articles: List<OpenAiAnthropicNews>
) : SourceListResult<OpenAiAnthropicNews> {
    override val items: List<OpenAiAnthropicNews> get() = articles
}
