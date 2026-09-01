package com.peng.ainewshub.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.theme.AppText

/**
 * 更新日志条目渲染共享件 —— [ChangelogScreen](更新日志页)与关于页更新弹窗
 * ([AboutScreen] 的「本次更新了什么」块)两处共用的样式:分类小标签(primary 色
 * bodySmall)+ 条目行(`**加粗**` 段渲染 SemiBold)。
 *
 * 不含水平内边距:页内版式(18dp 列缩进)与弹窗版式(弹窗自身 24dp padding)
 * 各自包裹,这里只管纵向节奏。
 */

/** 分类名 → 双语词条映射;未收录的分类(如未来新增的节名)原样显示。 */
internal fun categoryRes(category: String): Int? = when (category) {
    "新增" -> R.string.changelog_category_added
    "修复" -> R.string.changelog_category_fixed
    "改进" -> R.string.changelog_category_improved
    "核心功能" -> R.string.changelog_category_core
    "数据流水线" -> R.string.changelog_category_pipeline
    "工程化" -> R.string.changelog_category_engineering
    else -> null
}

@Composable
internal fun localizedCategory(category: String): String =
    categoryRes(category)?.let { stringResource(it) } ?: category

/** 加粗段 `**...**` 匹配(非贪婪,不允许内部换行);进程一份,不随行重建。 */
private val BOLD_SEGMENT_REGEX = Regex("\\*\\*(.+?)\\*\\*")

/**
 * 把单条日志解析为 [AnnotatedString]:`**标题** — 说明` 的加粗段渲染 SemiBold。
 * 与摘要卡(SummaryCard.renderRichLine)同一私有实现模式,按屏幕各留一份。
 */
internal fun renderBoldLine(line: String): AnnotatedString {
    if (!line.contains("**")) return AnnotatedString(line)
    val boldStyle = SpanStyle(fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        var lastEnd = 0
        for (m in BOLD_SEGMENT_REGEX.findAll(line)) {
            if (m.range.first > lastEnd) append(line.substring(lastEnd, m.range.first))
            withStyle(boldStyle) { append(m.groupValues[1]) }
            lastEnd = m.range.last + 1
        }
        if (lastEnd < line.length) append(line.substring(lastEnd))
    }
}

/** 版本内全部分类小节:分类标签 → 条目列表,纵向排布(见文件头样式说明)。 */
@Composable
internal fun ChangelogSections(sections: List<ChangelogSection>, modifier: Modifier = Modifier) {
    Column(modifier) {
        sections.forEach { section ->
            Text(
                text = localizedCategory(section.category),
                style = AppText.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
            )
            section.entries.forEach { entry ->
                Text(
                    text = renderBoldLine(entry),
                    style = AppText.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}
