package com.peng.ainewshub.ui.weekly

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.data.repo.SummaryRepository
import com.peng.ainewshub.data.repo.WeeklyDigest
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.ErrorKind
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.DoubleRule
import com.peng.ainewshub.ui.components.NewsCardSkeletonList
import com.peng.ainewshub.ui.components.SectionHeader
import com.peng.ainewshub.ui.components.archiveDateLabel
import com.peng.ainewshub.ui.components.rememberReadUrls
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「AI 周刊」二级页 —— 杂志式周度读物,默认呈现最新一期(承
 * [com.peng.ainewshub.ui.more.HistoryHubScreen] 的「同页换刊 + 日期弹层」范式)。
 *
 * 结构:刊头(期号 + 周区间 + 覆盖注记 + 双细线)→「本周综述」总综述 →
 * 主题板块 ×N(SectionHeader + AI 短评 + 精选条目行,条目点击经 openUrl 进
 * 内置 WebView)→ 终章符(与今天页同款)。顶栏右上角「往期」动作弹期次
 * 选择层,选即整页换刊(默认选最新一期,弹层首行即「回到最新」)。
 *
 * 数据/状态:
 *  - 单实例 [WeeklyViewModel](与今天页报头动作共用,进页秒回缓存);
 *  - 期次全集 = `weekly_history.json` 索引键;选中期经索引按周归档寻址
 *    (路径不可变缓存,来回切换零重复下载);
 *  - [listState] 由 AiNewsHubApp 按 Page 值上提持有,换期滚回顶部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyScreen(
    onBack: () -> Unit,
    onOpenUrl: (url: String, title: String, source: String) -> Unit,
    listState: LazyListState,
    vm: WeeklyViewModel = viewModel()
) {
    // 期次索引(页面级,一次拉取)
    val datesState by vm.dates.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadDates() }
    val dates = (datesState as? UiState.Success)?.data.orEmpty()

    // 选中期次:dates 首次到位落最新一期;rememberSaveable 跨进程恢复
    var selectedWeek by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(dates) {
        if (selectedWeek == null && dates.isNotEmpty()) selectedWeek = dates.first()
    }
    val week = selectedWeek

    // 当前期的正文:换期强制重载(归档按路径缓存,重拉近零成本)+ 滚回顶部
    val digestState by vm.digest.collectAsStateWithLifecycle()
    LaunchedEffect(week) {
        if (week == null) return@LaunchedEffect
        listState.scrollToItem(0)
        vm.retryIssue(week)
    }

    // 往期选择弹层(顶栏右上角「往期」点开)
    var showHistory by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            val context = LocalContext.current
            // 顶栏即刊期身份:主标题 = 期号,副标题 = 周区间(换刊即时反馈读了哪期);
            // 正文未就绪(Loading/Error)回落静态页名
            val issue = (digestState as? UiState.Success)?.data
            AppTopBar(
                title = issue?.let { stringResource(R.string.weekly_issue_label, it.issueNo) }
                    ?: stringResource(R.string.weekly_page_title),
                subtitle = issue?.let {
                    stringResource(
                        R.string.weekly_entry_range,
                        archiveDateLabel(context, it.weekStart),
                        archiveDateLabel(context, it.weekEnd)
                    )
                },
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                             contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // 往期入口文字按钮(去图标,同词云页布局切换动作语言);
                    // 索引就绪且有多期才值得切,单期时隐藏
                    if (dates.size > 1) {
                        Text(
                            text = stringResource(R.string.weekly_history_action),
                            style = AppText.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { showHistory = true }
                                .padding(horizontal = 14.dp, vertical = 14.dp)
                        )
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
                is UiState.Error -> if (s.kind == ErrorKind.NoData) {
                    EmptyState(
                        title = stringResource(R.string.weekly_no_data_title),
                        subtitle = stringResource(R.string.weekly_no_data_subtitle)
                    )
                } else {
                    ErrorState(
                        message = s.message,
                        title = stringResource(R.string.weekly_error_title),
                        onRetry = { vm.loadDates() }
                    )
                }
                is UiState.Success -> {
                    if (dates.isEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.weekly_no_data_title),
                            subtitle = stringResource(R.string.weekly_no_data_subtitle)
                        )
                    } else if (week == null) {
                        NewsCardSkeletonList(count = 6)
                    } else {
                        WeeklyIssueContent(
                            digestState = digestState,
                            listState = listState,
                            onRetry = { vm.retryIssue(week) },
                            onOpenUrl = onOpenUrl
                        )
                    }
                }
            }
        }
    }

    // 往期选择弹层:行 = 周一日期(倒序,最新在前,首行即「回到最新」),
    // 选中 ✓ 高亮(同过刊页「更多日期」弹层行式样)
    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item(key = "history-title") {
                    Text(
                        text = stringResource(R.string.weekly_history_sheet_title),
                        style = AppText.titleSection,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                    )
                }
                itemsIndexed(dates, key = { _, w -> w }) { _, w ->
                    val isSelected = w == week
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedWeek = w
                                showHistory = false
                            }
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = archiveDateLabel(LocalContext.current, w),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
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

/**
 * 单期周刊主体 —— 单 LazyColumn 自上而下:刊头 → 本周综述 → 主题板块 ×N
 * (短评 + 精选条目行)→ 终章符。二级页无下拉刷新;错误态带重试。
 *
 * 「本周综述」大节头与各主题板块节头走 stickyHeader 吸顶(顶栏正下方,与
 * 今天页同款):交接为标准替换,吸顶头实底 surface 遮住底下滚过的内容;
 * 小节头实底内再垫 6dp 呼吸(短评行无自带上边距,不垫会贴死)。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeeklyIssueContent(
    digestState: UiState<WeeklyDigest>,
    listState: LazyListState,
    onRetry: () -> Unit,
    onOpenUrl: (url: String, title: String, source: String) -> Unit
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val readUrls = rememberReadUrls()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 二级页无悬浮底栏,只留呼吸空间
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        when (val s = digestState) {
            is UiState.Loading -> item(key = "weekly-skeleton", contentType = "skeleton") {
                NewsCardSkeletonList(count = 6)
            }
            is UiState.Error -> if (s.kind == ErrorKind.NoData) {
                item(key = "weekly-empty", contentType = "notice") {
                    EmptyState(
                        title = stringResource(R.string.weekly_no_data_title),
                        subtitle = stringResource(R.string.weekly_no_data_subtitle)
                    )
                }
            } else {
                item(key = "weekly-error", contentType = "notice") {
                    ErrorState(
                        message = s.message,
                        title = stringResource(R.string.weekly_error_title),
                        onRetry = onRetry
                    )
                }
            }
            is UiState.Success -> {
                val digest = s.data

                // ===== 刊头:覆盖注记 + 生成时刻,双细线收底
                //(期号与周区间已上移顶栏标题/副标题,此处不重复) =====
                item(key = "weekly-head", contentType = "lead") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 18.dp, top = 10.dp)
                    ) {
                        // 如实标注覆盖不足(流水线如实记录实际读到归档的天数)
                        if (digest.coverageDays in 1..6) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.weekly_coverage_partial, digest.coverageDays),
                                style = AppText.caption,
                                color = cs.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.weekly_generated_at, formatIssueClock(digest.generatedAt)),
                            style = AppText.caption,
                            color = cs.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        DoubleRule()
                    }
                }

                // ===== 本周综述(AI 总综述;空串不渲染) =====
                if (digest.digest.isNotBlank()) {
                    stickyHeader(key = "weekly-digest-head", contentType = "digest-head") {
                        // 吸顶节头:实底 surface 遮住底下滚过的内容(SectionHeader 本身透明底)
                        SectionHeader(
                            title = stringResource(R.string.weekly_digest_heading),
                            large = true,
                            modifier = Modifier.background(cs.surface)
                        )
                    }
                    item(key = "weekly-digest-body", contentType = "digest-body") {
                        Text(
                            text = digest.digest,
                            style = AppText.body,
                            color = cs.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, end = 18.dp, bottom = 8.dp)
                        )
                    }
                }

                // ===== 主题板块 ×N:章节头 + AI 短评 + 精选条目行 =====
                digest.sections.forEachIndexed { si, section ->
                    stickyHeader(key = "weekly-sec-$si-head", contentType = "sec-head") {
                        // 吸顶实底 + 底部 6dp 呼吸(下方短评行无自带上边距,
                        // 只靠节头自带 6dp 底距吸顶时会贴死;background 在
                        // padding 外层,实底覆盖到垫高层)
                        SectionHeader(
                            title = section.title,
                            modifier = Modifier
                                .background(cs.surface)
                                .padding(bottom = 6.dp)
                        )
                    }
                    if (section.summary.isNotBlank()) {
                        item(key = "weekly-sec-$si-summary", contentType = "sec-summary") {
                            Text(
                                text = section.summary,
                                style = AppText.bodySmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 18.dp, end = 18.dp, bottom = 4.dp)
                            )
                        }
                    }
                    // 条目稳定 key:url 优先,重复/空以出现序号消歧(重复 key 直接崩溃)。
                    // (LazyListScope 非组合上下文不能 remember;小列表逐次重算可忽略)
                    val itemKeys = run {
                        val seen = mutableMapOf<String, Int>()
                        section.items.map { item ->
                            val base = item.url.ifBlank { "item" }
                            val dup = seen.getOrPut(base) { 0 }
                            seen[base] = dup + 1
                            base + if (dup == 0) "" else "#$dup"
                        }
                    }
                    itemsIndexed(
                        section.items,
                        key = { i, _ -> "weekly-sec-$si-item-${itemKeys[i]}" },
                        contentType = { _, _ -> "weekly-item" }
                    ) { _, item ->
                        WeeklyItemRow(
                            item = item,
                            isRead = item.url.isNotBlank() && item.url in readUrls,
                            onClick = {
                                onOpenUrl(item.url, item.title,
                                          SummaryRepository.titleOf(context, item.source))
                            }
                        )
                    }
                }

                // ===== 终章符:一期读完的完稿记号(与今天页同款) =====
                item(key = "weekly-end-mark", contentType = "end-mark") {
                    Text(
                        text = "■",
                        style = AppText.titleCompact,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * 精选条目行 —— 承 [com.peng.ainewshub.ui.overview.TopEntryRow] 的报纸条目
 * 语言(无排名徽章:板块内条目按重要性排序但非名次):标题(突发沿用「头条 · 」
 * 红字前缀)+ AI 一句话点评 + 文末署名行「—— 源名 · 日期 · 指标」。
 */
@Composable
private fun WeeklyItemRow(
    item: com.peng.ainewshub.data.repo.WeeklyIssueItem,
    isRead: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    // 「头条」前缀与今天页 Top10 共用词条(同一突发语义,不另造同义文案)
    val tagText = stringResource(R.string.overview_breaking_tag)
    val title = remember(item.title, item.breaking, tagText, cs.primary) {
        buildAnnotatedString {
            if (item.breaking) {
                withStyle(SpanStyle(color = cs.primary, fontWeight = FontWeight.Bold)) {
                    append("$tagText · ")
                }
            }
            append(item.title)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(
            text = title,
            style = AppText.body,
            color = if (isRead) cs.onSurface.copy(alpha = AppAlpha.readDim) else cs.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (item.comment.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.comment,
                style = AppText.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(6.dp))
        // 文末署名行(同 TopEntryRow 的报纸署名语言):源名 · 上榜日期 · 原始指标
        val sourceTitle = SummaryRepository.titleOf(LocalContext.current, item.source)
        val meta = buildList {
            add(sourceTitle)
            // "2026-10-15" → "10-15"(同年内短形;异常长度原样展示)
            if (item.date.length >= 5) add(item.date.takeLast(5))
            if (item.metrics.isNotBlank()) add(item.metrics)
        }.joinToString(" · ")
        Text(
            text = "—— $meta",
            style = AppText.caption,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 生成时刻格式化为「MM-dd HH:mm」(周刊跨天阅读,带日期比纯 HH:mm 明确)。 */
private fun formatIssueClock(ms: Long): String =
    runCatching { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms)) }.getOrDefault("")
