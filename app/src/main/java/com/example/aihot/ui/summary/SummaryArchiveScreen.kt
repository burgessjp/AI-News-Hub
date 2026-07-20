package com.example.aihot.ui.summary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aihot.ui.EmptyState
import com.example.aihot.ui.ErrorState
import com.example.aihot.ui.SummaryArchiveViewModel
import com.example.aihot.ui.UiState
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.AppTopBarDefaults
import com.example.aihot.ui.components.NewsCardSkeletonList
import com.example.aihot.ui.theme.AppText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 历史摘要 —— 可选日期列表(index.json `history` 索引:7 源日期并集,倒序)。
 *
 * 点击某天进入该日的 7 源摘要卡页([SummaryDateScreen])。视觉对齐「历史日报」
 * (DailyArchiveScreen):左栏相对日期 + 周几,右栏当天有归档的源数,行间发丝线。
 *
 * 纯归档语义,与全局 SourceMode 无关(同摘要 Tab);history 每源仅保留最近 31 天,
 * 且功能上线前的日期不在索引内(见 docs/news-hub-data-usage.md)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryArchiveScreen(
    onSelectDate: (String) -> Unit,
    onBack: () -> Unit,
    listState: LazyListState,
    vm: SummaryArchiveViewModel = viewModel()
) {
    val state by vm.dates.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadDates() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "历史摘要",
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is UiState.Loading -> NewsCardSkeletonList(count = 6)
                is UiState.Error -> ErrorState(
                    message = s.message,
                    title = "历史摘要加载失败",
                    onRetry = { vm.loadDates() }
                )
                is UiState.Success -> {
                    if (s.data.isEmpty()) {
                        EmptyState(
                            title = "暂无历史摘要",
                            subtitle = "每天 07:00 / 15:00(北京时间)更新\n历史记录自功能上线起累积(最近 31 天)",
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = dateLabelOf(date),
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "$sourceCount 个源有当日摘要",
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

/**
 * 日期标签:今天/昨天/前天/M月d日,均带周几(与历史日报列表同规格)。
 * 例:今天 · 周一 / 昨天 · 周日 / 7月4日 · 周六
 */
private fun dateLabelOf(date: String): String {
    return runCatching {
        val d = LocalDate.parse(date)
        val today = LocalDate.now()
        val days = ChronoUnit.DAYS.between(d, today)
        val base = when {
            days == 0L -> "今天"
            days == 1L -> "昨天"
            days == 2L -> "前天"
            else -> d.format(DateTimeFormatter.ofPattern("M月d日"))
        }
        "$base · ${d.dayOfWeek.toChinese()}"
    }.getOrDefault(date)
}

private fun java.time.DayOfWeek.toChinese(): String = when (this) {
    java.time.DayOfWeek.MONDAY -> "周一"
    java.time.DayOfWeek.TUESDAY -> "周二"
    java.time.DayOfWeek.WEDNESDAY -> "周三"
    java.time.DayOfWeek.THURSDAY -> "周四"
    java.time.DayOfWeek.FRIDAY -> "周五"
    java.time.DayOfWeek.SATURDAY -> "周六"
    java.time.DayOfWeek.SUNDAY -> "周日"
}
