package com.peng.ainewshub.ui.overview

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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.NewsCardSkeletonList
import com.peng.ainewshub.ui.components.archiveDateLabel

/**
 * 历史总览日期列表内容 —— 「历史回顾」hub 总览段(index.json `overview_history`
 * 索引键,倒序)。原为独立二级页(OverviewArchiveScreen),现抽出内容
 * composable 嵌入 hub,由 hub 持 VM 与滚动状态。
 *
 * 点击某天进入该日的总览页(OverviewDateScreen,复用总览 Tab 内容渲染)。
 * 视觉:左栏相对日期 + 周几,行间发丝线;无右栏计数(总览是跨源单份产物)。
 *
 * 纯归档语义;索引每源仅保留最近 90 天,更早的日期不在列表内
 * (见 docs/news-hub-data-usage.md)。
 */
@Composable
internal fun OverviewArchiveContent(
    state: UiState<List<String>>,
    listState: LazyListState,
    onSelectDate: (String) -> Unit,
    onRetry: () -> Unit
) {
    when (val s = state) {
        is UiState.Loading -> NewsCardSkeletonList(count = 6)
        is UiState.Error -> ErrorState(
            message = s.message,
            title = stringResource(R.string.overview_archive_load_failed),
            onRetry = onRetry
        )
        is UiState.Success -> {
            if (s.data.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.overview_archive_empty_title),
                    subtitle = stringResource(R.string.overview_archive_empty_subtitle),
                    icon = Icons.Outlined.AutoAwesome
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = s.data,
                        key = { _, it -> it }
                    ) { i, date ->
                        OverviewArchiveRow(
                            date = date,
                            onClick = { onSelectDate(date) }
                        )
                        if (i != s.data.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(start = 18.dp, end = 18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 日期条目 —— 与历史摘要日期行同语言:左栏相对日期(今天/昨天/前天/M月d日 · 周X),
 * 右缘 chevron;无右栏计数(总览为跨源单份产物)。
 */
@Composable
private fun OverviewArchiveRow(date: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = archiveDateLabel(context, date),
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = cs.outlineVariant
        )
    }
}
