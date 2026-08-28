package com.peng.ainewshub.ui.summary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.NewsCardSkeletonList
import com.peng.ainewshub.ui.components.archiveDateLabel
import com.peng.ainewshub.ui.theme.AppText

/**
 * 历史摘要日期列表内容 —— 「历史回顾」hub 摘要段(index.json `history` 索引:
 * 全源日期并集,倒序)。原为独立二级页(SummaryArchiveScreen),现抽出内容
 * composable 嵌入 hub,由 hub 持 VM 与滚动状态。
 *
 * 点击某天进入该日的全源摘要卡页(SummaryDateScreen)。视觉:左栏相对日期 +
 * 周几,右栏当天有归档的源数,行间发丝线。
 *
 * 纯归档语义,与全局 SourceMode 无关(同摘要 Tab);history 每源仅保留最近
 * 31 天,且功能上线前的日期不在索引内(见 docs/news-hub-data-usage.md)。
 */
@Composable
internal fun SummaryArchiveContent(
    state: UiState<List<Pair<String, Int>>>,
    listState: LazyListState,
    onSelectDate: (String) -> Unit,
    onRetry: () -> Unit
) {
    when (val s = state) {
        is UiState.Loading -> NewsCardSkeletonList(count = 6)
        is UiState.Error -> ErrorState(
            message = s.message,
            title = stringResource(R.string.summary_archive_load_failed),
            onRetry = onRetry
        )
        is UiState.Success -> {
            if (s.data.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.summary_archive_empty_title),
                    subtitle = stringResource(R.string.summary_archive_empty_subtitle),
                    icon = Icons.Outlined.Inventory2
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = s.data,
                        key = { _, it -> it.first }
                    ) { i, (date, sourceCount) ->
                        SummaryArchiveRow(
                            date = date,
                            sourceCount = sourceCount,
                            onClick = { onSelectDate(date) }
                        )
                        if (i != s.data.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(start = 118.dp, end = 18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 日期条目 —— 扁平无卡片风格(同 DailySummaryRow):
 * 左栏(固定宽):相对日期(今天/昨天/前天/M月d日 · 周X);
 * 右栏(权重 1):当天有归档的源数;右缘 chevron。
 */
@Composable
private fun SummaryArchiveRow(date: String, sourceCount: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = archiveDateLabel(context, date),
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = pluralStringResource(R.plurals.summary_sources_with_digest, sourceCount, sourceCount),
            style = AppText.bodySmall,
            color = cs.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = cs.outlineVariant
        )
    }
}
