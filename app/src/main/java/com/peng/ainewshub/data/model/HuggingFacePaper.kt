package com.peng.ainewshub.data.model

import androidx.compose.runtime.Immutable

/**
 * HuggingFace Trending Paper 单篇论文(来源:https://huggingface.co/papers/trending,
 * 由数据流水线抓取归档)。
 *
 * 与 [TrendingRepo] / [StormzhangAiNews] 平行:这是独立热榜数据源之一。
 * HuggingFace 的 Trending Papers 由 AK 每日精选 arXiv 论文,按社区 upvote 排序,
 * 是跟踪前沿 AI 研究的常用入口。
 *
 * 不加 @Parcelize:点击走 [url](内置 WebView),URL 是普通字符串,无需跨页面传整个对象。
 *
 * @param rank       1 起的排名(由列表位置决定)
 * @param id         论文 id,即 arXiv 编号,如 "2403.08299"
 * @param url        论文页完整 HTTPS 地址,如 "https://huggingface.co/papers/2403.08299"
 * @param title      论文标题
 * @param summary    一句话摘要;可能为空
 * @param upvotes    社区 upvote 数(热度主指标)
 * @param published  发布日期原文,如 "Jul 8, 2026";原样展示不做解析
 * @param authors    作者信息文本,如 "5 authors" 或 "A, B, C";可能为空
 * @param githubUrl  论文关联的 GitHub 仓库地址;无则为空
 */
@Immutable

data class HuggingFacePaper(
    val rank: Int,
    val id: String,
    val url: String,
    val title: String,
    val summary: String = "",
    val upvotes: Int = 0,
    val published: String = "",
    val authors: String = "",
    val githubUrl: String = ""
)

/**
 * Trending Papers 拉取结果(带数据新鲜度),与 [TrendingResult] / [StormzhangAiNewsResult] 同构。
 *
 * [fetchedAt] 是归档快照顶层的 fetched_at_ms(数据落盘时刻),
 * UI 据此在列表头显示「数据更新时间」。
 */
data class HuggingFacePapersResult(
    override val fetchedAt: Long,
    val papers: List<HuggingFacePaper>
) : SourceListResult<HuggingFacePaper> {
    override val items: List<HuggingFacePaper> get() = papers
}
