package com.peng.ainewshub.ui.summary

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.peng.ainewshub.data.repo.SourceSummary
import com.peng.ainewshub.data.repo.SummaryContent
import com.peng.ainewshub.data.source.SourceKeys
import com.peng.ainewshub.ui.UiState

/**
 * 摘要域共享渲染件 —— 分源摘要条目的富文本拼装与源强调色。
 *
 * 原摘要卡整页实现(SummaryCardPage 及 chips 导航行)已随 v1.4.x 历史回顾重做删除
 * (「今天」页与「过刊」页统一用 [com.peng.ainewshub.ui.overview.SourceSections]
 * 区块渲染,不再有横滑卡片页);本文件只保留逐条目/逐行的共享函数:
 *  - [renderItemLine] / [renderRichLine]:v2 结构化条目与 v1 纯文本行的富文本拼装
 *  - [sourceAccentOf]:源强调色(分源区块序号着色)
 *  - [hasUnseenDigest]:「新内容未查看」圆点判定(「今天」页分源区块头)
 */

/**
 * 源摘要「新内容未查看」判定(「今天」页分源区块头):结构化条目非空,且当前
 * 快照指纹(落盘时刻)≠ 用户上次查看该源页时记录的指纹 —— 查看页面即消隐
 * (「今天」页区块头进入视口时写入指纹),下一批新快照指纹变化重新亮起;源断供
 * 继承旧快照时指纹不变,不误亮。加载中/失败/旧纯文本格式一律不亮(无内容可看)。
 */
internal fun hasUnseenDigest(state: UiState<SourceSummary>?, seenFingerprint: Long?): Boolean {
    val summary = (state as? UiState.Success)?.data ?: return false
    val structured = summary.content as? SummaryContent.Structured ?: return false
    if (structured.items.isEmpty()) return false
    return seenFingerprint != summary.fetchedAtMs
}

/**
 * 源强调色 —— 分源区块条目序号的差异化锚点。
 * 各源同构(同一产品语言),仅靠强调色区分源。
 */
@Composable
internal fun sourceAccentOf(source: String): Color {
    val cs = MaterialTheme.colorScheme
    return when (source) {
        SourceKeys.HACKERNEWS -> cs.tertiary            // 暖橙,呼应 HN 品牌与热度语义
        SourceKeys.GITHUB_TRENDING -> cs.primary
        SourceKeys.HUGGINGFACE_PAPERS -> cs.primary
        SourceKeys.STORMZHANG_AI -> cs.secondary        // 品牌紫,贴「AI 资讯」语义
        SourceKeys.PRODUCTHUNT -> cs.primary            // PH 品牌橙红由 SourceBrand 承载,区块用 primary
        SourceKeys.RUNDOWN_AI -> cs.secondary           // 品牌紫,贴「AI newsletter」语义(与 stormzhang 同系)
        SourceKeys.OPENAI_ANTHROPIC_NEWS -> cs.tertiary // 暖橙,呼应 OpenAI 品牌绿与厂商动态热度语义
        SourceKeys.AIHOT_FEATURED -> cs.primary         // 自家源,品牌 Future Blue 由 SourceBrand.AiHot 承载
        else -> cs.primary
    }
}

/**
 * 把单条摘要条目拼成 [AnnotatedString]:title(SemiBold)+ desc(Normal)。
 *
 * title 与 desc 之间用全角冒号「：」连接,视觉上对齐 v1 纯文本「**标题**：描述」的观感,
 * 保证新旧格式切换时用户感知一致。
 */
internal fun renderItemLine(title: String, desc: String): AnnotatedString {
    val boldStyle = SpanStyle(fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        withStyle(boldStyle) { append(title) }
        append("：")
        append(desc)
    }
}

/** 加粗段 `**...**` 匹配(非贪婪,不允许内部换行);进程一份,不随行重建。 */
private val BOLD_SEGMENT_REGEX = Regex("\\*\\*(.+?)\\*\\*")

/**
 * 把单行文本解析为 [AnnotatedString]:**...** 段落渲染为 SemiBold,其余 Normal。
 *
 * prompt 要求每条格式「• **标题**：简述」,加粗段即标题,视觉上与正文拉开层级。
 * 实现:正则切 ** 包裹的段,交替应用 Normal / Bold 样式。支持一行内多处加粗。
 */
internal fun renderRichLine(line: String): AnnotatedString {
    // 去掉行首 bullet 与多余空白,统一缩进由排版负责
    val raw = line.trim().removePrefix("•").trimStart()
    if (raw.isBlank()) return AnnotatedString(line)
    val boldStyle = SpanStyle(fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        var idx = 0
        var lastEnd = 0
        for (m in BOLD_SEGMENT_REGEX.findAll(raw)) {
            if (m.range.first > lastEnd) append(raw.substring(lastEnd, m.range.first))
            withStyle(boldStyle) { append(m.groupValues[1]) }
            lastEnd = m.range.last + 1
            idx = lastEnd
        }
        if (idx < raw.length) append(raw.substring(idx))
    }
}
