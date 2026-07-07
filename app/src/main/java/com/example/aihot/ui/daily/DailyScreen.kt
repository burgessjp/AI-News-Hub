package com.example.aihot.ui.daily

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aihot.data.DailyEntry
import com.example.aihot.data.DailyReport
import com.example.aihot.data.Flash
import com.example.aihot.data.NewsItem
import com.example.aihot.ui.ErrorState
import com.example.aihot.ui.UiState
import com.example.aihot.ui.DailyViewModel
import com.example.aihot.ui.components.AppTopBar

/**
 * 日报屏幕:展示最新日报,顶部入口进入归档。
 *
 * 顶部 AppTopBar 带历史归档按钮,作为日报 tab 的根使用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyScreen(
    onItemClick: (NewsItem) -> Unit,
    onOpenArchive: () -> Unit = {},
    onOpenUrl: (String, String) -> Unit = { _, _ -> },
    vm: DailyViewModel = viewModel()
) {
    val state by vm.latest.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "AI 日报",
                actions = {
                    Text(
                        text = "每早八时",
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
                is UiState.Error -> ErrorState(message = s.message, onRetry = { vm.refreshLatest() })
                is UiState.Success -> DailyContent(report = s.data, onOpen = { url -> onOpenUrl(url, "AI HOT") })
            }
        }
    }
}

@Composable
internal fun DailyContent(report: DailyReport, onOpen: (String) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 顶部汇总:日期 + 头条 + 统计(扁平无卡片,与精选列表风格一致)
        item(key = "summary") {
            DailySummaryHeader(report = report)
        }

        // 各分节:分节标题(横贯背景条) + 扁平行 + hairline 分隔线
        val visibleSections = report.sections.filter { it.items.isNotEmpty() }
        visibleSections.forEachIndexed { sIdx, section ->
            item(key = "divider-$sIdx") { DailyRowDivider() }
            item(key = "section-title-$sIdx") {
                SectionHeader(label = section.label)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Thunderstorm,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "快讯",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            item(key = "flashes-list") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
 *  1. 日期 label(cyan,小号大写感)
 *  2. 头条标题(headlineSmall,SemiBold)
 *  3. lead 摘要(bodyMedium,多行)
 *  4. 统计行:条目数 · 分类数 · 快讯数 · 预计阅读(数字 cyan 加粗,标签灰色)
 */
@Composable
private fun DailySummaryHeader(report: DailyReport) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
            Text(
                text = dateLabel(report.date),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                letterSpacing = 1.sp
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 22.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            DailySummaryStats(report = report, accent = accent)
        }
    }
}

/** 把 YYYY-MM-DD(UTC)格式化为中文标签:今天 / 昨天 / 前天 / M月d日 · 周X。 */
private fun dateLabel(date: String): String {
    return runCatching {
        val d = java.time.LocalDate.parse(date)
        val today = java.time.LocalDate.now()
        val days = java.time.temporal.ChronoUnit.DAYS.between(d, today)
        val base = when {
            days == 0L -> "今天"
            days == 1L -> "昨天"
            days == 2L -> "前天"
            else -> "${d.monthValue}月${d.dayOfMonth}日"
        }
        "$base · ${d.dayOfWeek.toChinese()}"
    }.getOrDefault("$date 日报")
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

/** 统计行:每个统计项 = 数字(cyan,加粗)+ 标签(灰);项间中点分隔。 */
@Composable
private fun DailySummaryStats(report: DailyReport, accent: androidx.compose.ui.graphics.Color) {
    val entries = report.sections.sumOf { it.items.size }
    val flashes = report.flashes.size
    val sections = report.sections.count { it.items.isNotEmpty() }
    val readMin = (entries * 2).coerceAtLeast(1) // 粗估:每条约 2 分钟
    val stats = buildList {
        if (entries > 0) add(entries to "条")
        if (sections > 0) add(sections to "类")
        if (flashes > 0) add(flashes to "快讯")
        add(readMin to "分钟")
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
 * 分节标题 —— 横贯全宽的背景条 + cyan 左竖线 accent + 加粗 label。
 *
 * 视觉强度高于普通文字标题,用于在扁平列表中划清大类边界。
 * 与精选列表的 DateGroupHeader 风格一致(同样的 surfaceContainerHigh 底)。
 */
@Composable
private fun SectionHeader(label: String) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // cyan 左竖线 accent
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(16.dp)
                .background(accent)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 快讯时间线行 —— 左侧 cyan 圆点 + 连接竖线(Twitter 线风格)。
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
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 21.sp
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
 * 日报条目行 —— 扁平无卡片风格,与精选 [com.example.aihot.ui.NewsCard] 视觉统一。
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
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )
        }
        entry.summary?.let { sum ->
            if (sum.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = sum,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
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
        com.example.aihot.ui.components.ShimmerBox(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            cornerRadius = 22.dp
        )
        // 几条 section 骨架
        com.example.aihot.ui.components.NewsCardSkeleton()
        com.example.aihot.ui.components.NewsCardSkeleton()
        com.example.aihot.ui.components.NewsCardSkeleton()
    }
}
