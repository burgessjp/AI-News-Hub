package com.example.aihot.ui.daily

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aihot.data.DailySummary
import com.example.aihot.ui.ErrorState
import com.example.aihot.ui.UiState
import com.example.aihot.ui.DailyViewModel
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.AppTopBarDefaults
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 日报归档列表(/dailies)。点击某天进入该日日报详情。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyArchiveScreen(
    onSelectDate: (String) -> Unit,
    onBack: () -> Unit,
    vm: DailyViewModel = viewModel()
) {
    val state by vm.archive.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadArchive() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "历史日报",
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
                is UiState.Loading -> com.example.aihot.ui.components.NewsCardSkeletonList(count = 6)
                is UiState.Error -> ErrorState(
                    message = s.message,
                    title = "归档加载失败",
                    onRetry = { vm.loadArchive() }
                )
                is UiState.Success -> {
                    LazyColumn(
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

/**
 * 历史日报条目 —— 扁平无卡片风格,与精选列表视觉统一。
 *
 * 布局(与 [com.example.aihot.ui.NewsCard] 同构):
 *  - 左栏(固定):相对日期(今天/昨天/前天/M月d日 · 周X)
 *  - 右栏(权重 1):lead 标题(SemiBold,2 行)
 *
 * 行间依靠 [DailyArchiveDivider] 区分,不再使用 AppClickableCard 描边卡片。
 */
@Composable
private fun DailySummaryRow(summary: DailySummary, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
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
            text = dateLabelOf(summary.date),
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
 * 日期标签:今天/昨天/前天/M月d日,均带周几。
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
