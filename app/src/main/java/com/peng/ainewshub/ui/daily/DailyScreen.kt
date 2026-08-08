package com.peng.ainewshub.ui.daily

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.data.DailyEntry
import com.peng.ainewshub.data.DailyReport
import com.peng.ainewshub.data.Flash
import com.peng.ainewshub.data.NewsItem
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.DailyViewModel
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.ArchiveIconButton
import com.peng.ainewshub.ui.components.SectionHeader
import com.peng.ainewshub.ui.components.weekdayLabel
import com.peng.ainewshub.ui.theme.AppText
import com.peng.ainewshub.ui.theme.TrackingWide
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 日报屏幕:展示最新日报,顶部入口进入归档。
 *
 * 顶部 AppTopBar 带历史归档按钮。
 * [onBack] 非 null 时左侧显示返回箭头(作为从「全部」页 push 进入的二级页使用);
 * 为 null 时无返回箭头(保留兼容旧调用方)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyScreen(
    onItemClick: (NewsItem) -> Unit,
    onOpenArchive: () -> Unit = {},
    onOpenUrl: (String, String) -> Unit = { _, _ -> },
    onBack: (() -> Unit)? = null,
    // 列表状态由 MainActivity 上提持有:进 WebView/归档返回后保持滚动位置
    listState: LazyListState,
    vm: DailyViewModel = viewModel()
) {
    val state by vm.latest.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.daily_title),
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = if (onBack != null) {
                    {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back)
                            )
                        }
                    }
                } else null,
                actions = {
                    ArchiveIconButton(onClick = onOpenArchive)
                    Text(
                        text = stringResource(R.string.daily_every_morning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is UiState.Loading -> DailySkeleton()
                is UiState.Error -> ErrorState(
                    message = s.message,
                    title = stringResource(R.string.daily_load_failed),
                    onRetry = { vm.refreshLatest() }
                )
                // 下拉刷新:不翻回 Loading,仅转刷新指示(对齐 8 源页既有模式)
                is UiState.Success -> PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { vm.pullRefreshLatest() }
                ) {
                    DailyContent(
                        report = s.data,
                        onOpen = { url -> onOpenUrl(url, "AI HOT") },
                        listState = listState
                    )
                }
            }
        }
    }
}

@Composable
internal fun DailyContent(report: DailyReport, onOpen: (String) -> Unit, listState: LazyListState) {
    LazyColumn(
        state = listState,
        // 现作为二级页(底栏隐藏),底部只需常规留白,不再预留浮动底栏高度
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 顶部汇总:日期 + 头条 + 统计(扁平无卡片,与精选列表风格一致)
        item(key = "summary") {
            DailySummaryHeader(report = report)
        }

        // 各分节:分节标题(统一 SectionHeader,透明底 + 小竖条) + 扁平行 + hairline 分隔线
        val visibleSections = report.sections.filter { it.items.isNotEmpty() }
        visibleSections.forEachIndexed { sIdx, section ->
            item(key = "divider-$sIdx") { DailyRowDivider() }
            item(key = "section-title-$sIdx") {
                SectionHeader(title = section.label)
            }
            itemsIndexed(
                items = section.items,
                key = { i, _ -> "entry-$sIdx-$i" }
            ) { i, entry ->
                DailyEntryRow(entry = entry, onOpen = onOpen)
                if (i != section.items.lastIndex) {
                    DailyRowDivider()
                }
            }
        }

        // 快讯:时间线样式,单列展示
        if (report.flashes.isNotEmpty()) {
            item(key = "divider-flashes") { DailyRowDivider() }
            item(key = "flashes-title") {
                // 与同页分隔线/条目同一边距(18dp),不再贴屏幕左缘
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 18.dp)
                ) {
                    Icon(
                        Icons.Filled.Thunderstorm,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.daily_flashes),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            item(key = "flashes-list") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 18.dp)
                ) {
                    report.flashes.forEachIndexed { idx, flash ->
                        FlashTimelineRow(flash = flash, onOpen = onOpen, isLast = idx == report.flashes.lastIndex)
                    }
                }
            }
        }
    }
}

/** 行间 hairline 分隔线 —— 与精选列表一致,缩进对齐主内容列。 */
@Composable
private fun DailyRowDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = 18.dp)
    )
}

/**
 * 顶部汇总区 —— 扁平布局,无卡片描边,与精选 item 视觉统一。
 *
 * 结构(自上而下):
 *  1. 日期 label(primary,小号大写感)
 *  2. 头条标题(headlineSmall,SemiBold)
 *  3. lead 摘要(bodyMedium,多行)
 *  4. 统计行:条目数 · 分类数 · 快讯数 · 预计阅读(数字 primary 加粗,标签灰色)
 */
@Composable
private fun DailySummaryHeader(report: DailyReport) {
    val accent = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
            Text(
                text = dateLabel(context, report.date),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                // 日报日期标签专用大字距,非 labelLarge 通用属性,不进 Type.kt
                letterSpacing = TrackingWide
            )
            report.lead?.let { lead ->
                if (lead.title.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = lead.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = MaterialTheme.typography.headlineSmall.lineHeight
                    )
                }
                lead.leadParagraph?.let { p ->
                    if (p.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = p,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            DailySummaryStats(report = report, accent = accent)
        }
    }
}

/** 把 YYYY-MM-DD(UTC)格式化为本地化标签:今天 / 昨天 / 前天 / M月d日 · 周X(英文 Today / Yesterday / MMM d)。 */
private fun dateLabel(context: Context, date: String): String {
    return runCatching {
        val d = java.time.LocalDate.parse(date)
        // 日报 date 按 UTC 切日,基准也用 UTC,避免系统时区边缘用户把今天/昨天算错
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        val days = java.time.temporal.ChronoUnit.DAYS.between(d, today)
        val base = when {
            days == 0L -> context.getString(R.string.time_today)
            days == 1L -> context.getString(R.string.time_yesterday)
            days == 2L -> context.getString(R.string.time_day_before_yesterday)
            else -> d.format(
                DateTimeFormatter.ofPattern(context.getString(R.string.date_fmt_month_day), Locale.getDefault())
            )
        }
        "$base · ${weekdayLabel(context, d.dayOfWeek.value)}"
    }.getOrDefault(context.getString(R.string.daily_date_title, date))
}

/** 统计行:每个统计项 = 数字(primary,加粗)+ 标签(灰);项间中点分隔。 */
@Composable
private fun DailySummaryStats(report: DailyReport, accent: androidx.compose.ui.graphics.Color) {
    val entries = report.sections.sumOf { it.items.size }
    val flashes = report.flashes.size
    val sections = report.sections.count { it.items.isNotEmpty() }
    val readMin = (entries * 2).coerceAtLeast(1) // 粗估:每条约 2 分钟
    // 单位文案提前取出:buildList 是非 Composable lambda,不能在内调 stringResource
    val itemsUnit = stringResource(R.string.daily_stat_items)
    val sectionsUnit = stringResource(R.string.daily_stat_sections)
    val flashesUnit = stringResource(R.string.daily_stat_flashes)
    val minutesUnit = stringResource(R.string.daily_stat_minutes)
    val stats = buildList {
        if (entries > 0) add(entries to itemsUnit)
        if (sections > 0) add(sections to sectionsUnit)
        if (flashes > 0) add(flashes to flashesUnit)
        add(readMin to minutesUnit)
    }
    if (stats.isEmpty()) return
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        stats.forEachIndexed { idx, (num, label) ->
            Text(
                text = num.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp, bottom = 1.dp)
            )
            if (idx != stats.lastIndex) {
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(end = 4.dp, bottom = 1.dp)
                )
            }
        }
    }
}

/**
 * 快讯时间线行 —— 左侧 primary 圆点 + 连接竖线(Twitter 线风格)。
 */
@Composable
private fun FlashTimelineRow(flash: Flash, onOpen: (String) -> Unit, isLast: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val url = flash.permalink ?: flash.sourceUrl
    val clickable = !url.isNullOrBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (clickable) Modifier.clickable { url?.let { onOpen(it) } }
                else Modifier
            )
            .padding(vertical = 2.dp)
    ) {
        // 时间线左侧
        Box(
            modifier = Modifier.width(20.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                if (!isLast) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(36.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        // 快讯内容
        Column(modifier = Modifier.weight(1f).padding(bottom = 10.dp)) {
            Text(
                text = flash.title,
                style = AppText.bodyCompact,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (flash.sourceName.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = flash.sourceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 日报条目行 —— 扁平无卡片风格,与精选 [com.peng.ainewshub.ui.NewsCard] 视觉统一。
 *
 * 布局:整行可点击;标题(SemiBold)+ 摘要(灰,3 行)+ 底部来源行。
 * 不再使用 AppClickableCard 的描边圆角卡片,行间依靠 [DailyRowDivider] 区分。
 */
@Composable
private fun DailyEntryRow(entry: DailyEntry, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val url = entry.permalink ?: entry.sourceUrl
    val clickable = !url.isNullOrBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (clickable) Modifier.clickable { url?.let { onOpen(it) } }
                else Modifier
            )
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        if (entry.title.isNotBlank()) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        entry.summary?.let { sum ->
            if (sum.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = sum,
                    style = AppText.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (entry.sourceName.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = entry.sourceName,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 日报加载骨架:大头条块 + 几条骨架卡片。 */
@Composable
private fun DailySkeleton() {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 头条 lead 块骨架
        com.peng.ainewshub.ui.components.ShimmerBox(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            cornerRadius = 22.dp
        )
        // 几条 section 骨架
        com.peng.ainewshub.ui.components.NewsCardSkeleton()
        com.peng.ainewshub.ui.components.NewsCardSkeleton()
        com.peng.ainewshub.ui.components.NewsCardSkeleton()
    }
}
