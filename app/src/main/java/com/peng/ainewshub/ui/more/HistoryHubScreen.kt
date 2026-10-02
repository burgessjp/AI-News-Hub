package com.peng.ainewshub.ui.more

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.data.repo.OverviewDigest
import com.peng.ainewshub.data.repo.SourceSummary
import com.peng.ainewshub.data.repo.SummaryContent
import com.peng.ainewshub.data.repo.SummaryRepository
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.SectionNotice
import com.peng.ainewshub.ui.SummaryArchiveViewModel
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.NewsCardSkeletonList
import com.peng.ainewshub.ui.components.SectionHeader
import com.peng.ainewshub.ui.components.archiveDateLabel
import com.peng.ainewshub.ui.components.rememberReadUrls
import com.peng.ainewshub.ui.overview.OverviewArchiveViewModel
import com.peng.ainewshub.ui.overview.OverviewFooter
import com.peng.ainewshub.ui.overview.OverviewLead
import com.peng.ainewshub.ui.overview.SourcePlainLineRow
import com.peng.ainewshub.ui.overview.SourceSectionHeader
import com.peng.ainewshub.ui.overview.SourceSectionSkeleton
import com.peng.ainewshub.ui.overview.SourceSummaryItemRow
import com.peng.ainewshub.ui.overview.SOURCE_SECTION_MAX_ITEMS
import com.peng.ainewshub.ui.overview.TopEntryRow
import com.peng.ainewshub.ui.summary.sourceAccentOf
import com.peng.ainewshub.ui.theme.AppText

/**
 * 「过刊」页 —— 报纸合订本式的历史回看:顶部刊期条选一天,下方同页渲染
 * 「那天的日报」(综述 Hero + 当日重点 Top10 + 分源摘要区块),与「今天」页
 * 同构渲染(共享 OverviewLead / TopEntryRow / 分源区块组件)。
 *
 * 原三段式 hub(总览/摘要/热词分段 + 空壳日期索引页)已废弃:那是流水线产物
 * 视角,回看者的心智是「翻某一天的报纸」,不是按产物类型分三次进。
 *
 * 数据/状态:
 *  - 日期全集 = `overview_history` 索引键(90 天,流水线逐日恒写,最全);
 *  - 分源摘要受 `history` 索引窗口限制(仅保留 31 天):选中日不在窗口内时
 *    分源区块整段不渲染,以一行注记说明(总览不受影响);
 *  - VM 均为单实例宿主(非按日期隔离):换日期经 [OverviewArchiveViewModel.retryDigest]
 *    强制重载 + [SummaryArchiveViewModel.clearDate] 清守卫后重拉;
 *  - [listState] 由 AiNewsHubApp 按 Page 值上提持有,换日期滚回顶部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryHubScreen(
    onBack: () -> Unit,
    onOpenUrl: (url: String, title: String, source: String) -> Unit,
    listState: LazyListState
) {
    val context = LocalContext.current
    // 日期索引(页面级,一次拉取)
    val listVm: OverviewArchiveViewModel = viewModel()
    val datesState by listVm.dates.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { listVm.loadDates() }
    val dates = (datesState as? UiState.Success)?.data.orEmpty()

    // 分源摘要窗口(31 天键集,页面级一次拉取;失败/未到位视为窗口未知)
    val summaryListVm: SummaryArchiveViewModel = viewModel()
    val summaryDatesState by summaryListVm.dates.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { summaryListVm.loadDates() }
    val summaryWindow = (summaryDatesState as? UiState.Success)?.data?.map { it.first }?.toSet()

    // 选中日期:dates 首次到位落最新一天;rememberSaveable 跨进程恢复
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(dates) {
        if (selectedDate == null && dates.isNotEmpty()) selectedDate = dates.first()
    }
    val date = selectedDate

    // 当日两路:单实例 VM,换日期强制重载(见类注释);同时滚回顶部
    val overviewVm: OverviewArchiveViewModel = viewModel()
    val summaryVm: SummaryArchiveViewModel = viewModel()
    val digestState by overviewVm.digest.collectAsStateWithLifecycle()
    val summaryStates by summaryVm.dateStates.collectAsStateWithLifecycle()
    LaunchedEffect(date) {
        if (date == null) return@LaunchedEffect
        listState.scrollToItem(0)
        overviewVm.retryDigest(date)
        summaryVm.clearDate()
        summaryVm.loadDate(date)
    }

    // 更多日期弹层(90 天全集;刊期条只直选近期)
    var showAllDates by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.history_hub_title),
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val s = datesState) {
                is UiState.Loading -> NewsCardSkeletonList(count = 6)
                is UiState.Error -> ErrorState(
                    message = s.message,
                    title = stringResource(R.string.overview_archive_load_failed),
                    onRetry = { listVm.loadDates() }
                )
                is UiState.Success -> {
                    if (dates.isEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.overview_archive_empty_title),
                            subtitle = stringResource(R.string.overview_archive_empty_subtitle)
                        )
                    } else {
                        // 刊期条:近期日期直选 + 尾部「更多」进全量日期弹层
                        IssueStrip(
                            dates = dates,
                            selected = date,
                            onSelect = { selectedDate = it },
                            onMore = { showAllDates = true }
                        )
                        if (date == null) {
                            NewsCardSkeletonList(count = 6)
                        } else {
                            IssueDayContent(
                                digestState = digestState,
                                summaryStates = summaryStates,
                                // 窗口已知且不含选中日 → 分源段不渲染,给注记
                                summaryAvailable = summaryWindow?.contains(date) == true,
                                summaryWindowKnown = summaryWindow != null,
                                listState = listState,
                                onRetryDigest = { overviewVm.retryDigest(date) },
                                onRetrySource = { summaryVm.retrySource(date, it) },
                                onOpenUrl = onOpenUrl
                            )
                        }
                    }
                }
            }
        }
    }

    // 全量日期弹层:行 = 相对日期 + 周几(同刊期条词条),选中日高亮
    if (showAllDates) {
        ModalBottomSheet(onDismissRequest = { showAllDates = false }) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item(key = "title") {
                    Text(
                        text = stringResource(R.string.history_hub_pick_date),
                        style = AppText.titleSection,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                    )
                }
                itemsIndexed(dates, key = { _, d -> d }) { _, d ->
                    val selected = d == date
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedDate = d
                                showAllDates = false
                            }
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = archiveDateLabel(context, d),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Text(
                                text = "✓",
                                style = AppText.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 刊期条直选的近期日期数,余量经「更多」弹层触达(90 天全集)。 */
private const val STRIP_DATES = 14

/**
 * 刊期条 —— 水平日期 chips(相对日期词条,选中 primary 实底,同关注词 chip 语言)
 * + 尾部「更多日期」入口。选即切,下方日报原地换刊。
 */
@Composable
private fun IssueStrip(
    dates: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    onMore: () -> Unit
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    LazyRow(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        itemsIndexed(dates.take(STRIP_DATES), key = { _, d -> d }) { _, d ->
            val isSelected = d == selected
            Text(
                text = archiveDateLabel(context, d),
                style = AppText.bodySmall,
                fontWeight = if (isSelected) FontWeight.SemiBold else null,
                color = if (isSelected) cs.onPrimary else cs.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) cs.primary else cs.surfaceContainerHigh)
                    .clickable { onSelect(d) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        item(key = "more") {
            Text(
                text = stringResource(R.string.history_hub_more_dates) + " ›",
                style = AppText.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = cs.primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(cs.surfaceContainerHigh)
                    .clickable(onClick = onMore)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * 当日日报主体 —— 单 LazyColumn 自上而下:综述 Hero → 「当日重点」Top10 →
 * 总览页脚 → 分源摘要区块 ×8(窗口外整段不渲染)。
 * 与「今天」页同构(共享渲染件),差异:无下拉刷新、无关注行、区块头「新内容」
 * 圆点恒灭(回看无未读语义)、条目点击 source 标签取归档源名。
 */
@Composable
private fun IssueDayContent(
    digestState: UiState<OverviewDigest>,
    summaryStates: Map<String, UiState<SourceSummary>>,
    summaryAvailable: Boolean,
    summaryWindowKnown: Boolean,
    listState: LazyListState,
    onRetryDigest: () -> Unit,
    onRetrySource: (String) -> Unit,
    onOpenUrl: (url: String, title: String, source: String) -> Unit
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val readUrls = rememberReadUrls()
    // Top10 稳定 key:url 优先,重复/空 url 以出现序号消歧保证唯一(重复 key 直接崩溃)
    val topItems = (digestState as? UiState.Success)?.data?.items.orEmpty()
    val topKeys = remember(topItems) {
        val seen = mutableMapOf<String, Int>()
        topItems.map { e ->
            val base = e.url.ifBlank { "top" }
            val dup = seen.getOrPut(base) { 0 }
            seen[base] = dup + 1
            "top-" + base + if (dup == 0) "" else "#$dup"
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 二级页无悬浮底栏,只留呼吸空间
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // ===== 总览段:综述 Hero + 当日重点 Top10 =====
        when (val s = digestState) {
            is UiState.Loading -> item(key = "digest-skeleton", contentType = "skeleton") {
                NewsCardSkeletonList(count = 6)
            }
            is UiState.Error -> item(key = "digest-error", contentType = "notice") {
                ErrorState(
                    message = s.message,
                    title = stringResource(R.string.overview_archive_load_failed),
                    onRetry = onRetryDigest
                )
            }
            is UiState.Success -> {
                val digest = s.data
                if (digest.dataFetchedAt > 0 || digest.digest.isNotBlank()) {
                    item(key = "lead", contentType = "lead") { OverviewLead(digest = digest) }
                }
                if (digest.items.isNotEmpty()) {
                    item(key = "focus_head", contentType = "focus-head") {
                        SectionHeader(title = stringResource(R.string.history_hub_focus_section), large = true)
                    }
                    itemsIndexed(
                        digest.items,
                        key = { i, _ -> topKeys[i] },
                        contentType = { _, e -> if (e.breaking) "top10-breaking" else "top10" }
                    ) { index, entry ->
                        TopEntryRow(
                            rank = index + 1,
                            entry = entry,
                            isRead = entry.url in readUrls,
                            onClick = {
                                onOpenUrl(entry.url, entry.title, SummaryRepository.titleOf(context, entry.source))
                            }
                        )
                    }
                }
            }
        }

        // ===== 分源摘要段:仅当该日在 31 天摘要归档窗口内 =====
        if (summaryAvailable) {
            SummaryRepository.SOURCE_KEYS.forEach { key ->
                val state = summaryStates[key] ?: UiState.Loading
                item(key = "src-$key-head", contentType = "src-head") {
                    // 回看无「新内容未查看」语义,圆点恒灭(hasUnseen 恒 false);
                    // 「查看全部」出口指向今日列表页,历史日期跳转语义不符 → 隐藏
                    SourceSectionHeader(
                        source = key,
                        state = state,
                        hasUnseen = false,
                        onOpen = {},
                        viewAllEnabled = false
                    )
                }
                when (state) {
                    is UiState.Loading -> item(key = "src-$key-skeleton", contentType = "skeleton") {
                        SourceSectionSkeleton()
                    }
                    is UiState.Error -> item(key = "src-$key-error", contentType = "notice") {
                        ErrorState(
                            message = state.message,
                            title = stringResource(R.string.summary_error_title),
                            onRetry = { onRetrySource(key) }
                        )
                    }
                    is UiState.Success -> when (val content = state.data.content) {
                        is SummaryContent.Structured -> {
                            val items = content.items.take(SOURCE_SECTION_MAX_ITEMS)
                            val itemKeys = run {
                                val seenUrls = mutableMapOf<String, Int>()
                                items.map { item ->
                                    val base = item.url.ifBlank { "item" }
                                    val dup = seenUrls.getOrPut(base) { 0 }
                                    seenUrls[base] = dup + 1
                                    base + if (dup == 0) "" else "#$dup"
                                }
                            }
                            itemsIndexed(
                                items,
                                key = { i, _ -> "src-$key-item-${itemKeys[i]}" },
                                contentType = { _, _ -> "src-item" }
                            ) { index, item ->
                                SourceSummaryItemRow(
                                    index = index,
                                    item = item,
                                    accent = sourceAccentOf(key),
                                    isRead = item.url.isNotBlank() && item.url in readUrls,
                                    onClick = {
                                        onOpenUrl(item.url, item.title, SummaryRepository.titleOf(context, key))
                                    }
                                )
                            }
                        }
                        is SummaryContent.Plain -> {
                            val lines = content.text.lines().filter { it.isNotBlank() }.take(SOURCE_SECTION_MAX_ITEMS)
                            itemsIndexed(
                                lines,
                                key = { i, _ -> "src-$key-line-$i" },
                                contentType = { _, _ -> "src-item" }
                            ) { index, line ->
                                SourcePlainLineRow(index = index, line = line, accent = sourceAccentOf(key))
                            }
                        }
                        is SummaryContent.Unavailable -> item(key = "src-$key-unavailable", contentType = "notice") {
                            SectionNotice(
                                title = stringResource(R.string.summary_unavailable_title),
                                message = stringResource(R.string.summary_unavailable_subtitle)
                            )
                        }
                    }
                }
            }
        } else if (summaryWindowKnown) {
            // 窗口已知、该日不在 31 天摘要归档内:总览照常,分源段以一行注记说明
            item(key = "summary-window-note", contentType = "notice") {
                Text(
                    text = stringResource(R.string.history_hub_summary_window),
                    style = AppText.caption,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                )
            }
        }

        // ===== 页脚:该日生成时刻 / 基于源数 / 缺源标注 =====
        val digest = (digestState as? UiState.Success)?.data
        if (digest != null) {
            item(key = "footer", contentType = "footer") {
                OverviewFooter(digest = digest)
            }
        }
    }
}
