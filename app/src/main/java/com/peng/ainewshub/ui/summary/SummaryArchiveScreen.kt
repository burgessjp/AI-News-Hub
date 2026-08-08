package com.peng.ainewshub.ui.summary

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.SummaryArchiveViewModel
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.NewsCardSkeletonList
import com.peng.ainewshub.ui.components.weekdayLabel
import com.peng.ainewshub.ui.theme.AppText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 历史摘要 —— 可选日期列表(index.json `history` 索引:全源日期并集,倒序)。
 *
 * 点击某天进入该日的全源摘要卡页([SummaryDateScreen])。视觉对齐「历史日报」
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
                title = stringResource(R.string.summary_archive_title),
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
                    title = stringResource(R.string.summary_archive_load_failed),
                    onRetry = { vm.loadDates() }
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
            text = dateLabelOf(context, date),
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

/**
 * 日期标签:今天/昨天/前天/M月d日,均带周几(与历史日报列表同规格)。
 * 例:今天 · 周一 / 昨天 · 周日 / 7月4日 · 周六;取词走 common time_* / date_fmt_month_day / weekdayLabel
 */
private fun dateLabelOf(context: Context, date: String): String {
    return runCatching {
        val d = LocalDate.parse(date)
        val today = LocalDate.now()
        val days = ChronoUnit.DAYS.between(d, today)
        val base = when {
            days == 0L -> context.getString(R.string.time_today)
            days == 1L -> context.getString(R.string.time_yesterday)
            days == 2L -> context.getString(R.string.time_day_before_yesterday)
            else -> d.format(
                DateTimeFormatter.ofPattern(
                    context.getString(R.string.date_fmt_month_day),
                    Locale.getDefault()
                )
            )
        }
        "$base · ${weekdayLabel(context, d.dayOfWeek.value)}"
    }.getOrDefault(date)
}
