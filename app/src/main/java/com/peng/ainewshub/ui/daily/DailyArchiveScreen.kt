package com.peng.ainewshub.ui.daily

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.data.DailySummary
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.DailyViewModel
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.weekdayLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 日报归档列表(/dailies)。点击某天进入该日日报详情。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyArchiveScreen(
    onSelectDate: (String) -> Unit,
    onBack: () -> Unit,
    // 列表状态由 MainActivity 上提持有:进单日日报返回后保持滚动位置
    listState: LazyListState,
    vm: DailyViewModel = viewModel()
) {
    val state by vm.archive.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadArchive() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.daily_archive_title),
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is UiState.Loading -> com.peng.ainewshub.ui.components.NewsCardSkeletonList(count = 6)
                is UiState.Error -> ErrorState(
                    message = s.message,
                    title = stringResource(R.string.common_load_failed),
                    onRetry = { vm.loadArchive() }
                )
                is UiState.Success -> {
                    if (s.data.isEmpty()) {
                        // 空数据空态:与 SummaryArchiveScreen 同语言,不再是一页纯白
                        EmptyState(
                            title = stringResource(R.string.daily_archive_empty_title),
                            subtitle = stringResource(R.string.daily_archive_empty_subtitle),
                            icon = Icons.Outlined.Inventory2,
                            actionLabel = stringResource(R.string.common_retry),
                            onAction = { vm.loadArchive() }
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(vertical = 4.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(items = s.data, key = { _, it -> it.date }) { i, summary ->
                                DailySummaryRow(summary = summary, onClick = { onSelectDate(summary.date) })
                                if (i != s.data.lastIndex) {
                                    DailyArchiveDivider()
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
 * 历史日报条目 —— 扁平无卡片风格,与精选列表视觉统一。
 *
 * 布局(与 [com.peng.ainewshub.ui.NewsCard] 同构):
 *  - 左栏(固定):相对日期(今天/昨天/前天/M月d日 · 周X)
 *  - 右栏(权重 1):lead 标题(SemiBold,2 行)
 *
 * 行间依靠 [DailyArchiveDivider] 区分,不再使用 AppClickableCard 描边卡片。
 */
@Composable
private fun DailySummaryRow(summary: DailySummary, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 左栏:日期(相对 + 周几)
        Text(
            text = dateLabelOf(context, summary.date),
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp)
        )
        // 右栏:标题
        val title = summary.leadTitle?.takeIf { it.isNotBlank() }
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 行间 hairline 分隔线 —— 缩进对齐右栏(避开日期列)。 */
@Composable
private fun DailyArchiveDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = 72.dp, end = 18.dp)
    )
}

/**
 * 日期标签:今天/昨天/前天/M月d日,均带周几(模式串走 date_fmt_month_day)。
 * 例:今天 · 周一 / 昨天 · 周日 / 7月4日 · 周六
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
                DateTimeFormatter.ofPattern(context.getString(R.string.date_fmt_month_day), Locale.getDefault())
            )
        }
        "$base · ${weekdayLabel(context, d.dayOfWeek.value)}"
    }.getOrDefault(date)
}
