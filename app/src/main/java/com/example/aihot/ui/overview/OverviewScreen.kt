package com.example.aihot.ui.overview

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aihot.data.OverviewDigest
import com.example.aihot.data.OverviewEntry
import com.example.aihot.data.SummaryRepository
import com.example.aihot.ui.EmptyState
import com.example.aihot.ui.ErrorState
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.BottomBarReservedHeight
import com.example.aihot.ui.components.RankBadge
import com.example.aihot.ui.components.SectionHeader
import com.example.aihot.ui.theme.AppAlpha
import com.example.aihot.ui.theme.AppText
import com.example.aihot.ui.theme.BrandGradient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 今日总览 Tab 根屏 —— 端侧 AI 对 7 个归档源当日榜单的跨源综合分析。
 *
 * 结构:
 *  - Breaking News 模块(0-3 条,[BrandGradient] 高亮卡;AI 判定没有则整模块不渲染)
 *  - 今日热点 Top10([RankBadge] + 原标题 + 一句话分析 + 来源/互动指标)
 *  - 页脚:生成时间 / 数据截至 / 模型与 token 消耗 / 缺源标注
 *
 * 与「摘要」tab 的分工:摘要是流水线预生成的分源要点(只读归档);总览是端侧实时
 * 调用用户自配 AI 的整体研判(见 [com.example.aihot.data.OverviewRepository]),
 * 未配置 AI 服务时显示全屏引导([OverviewState.ConfigMissing])。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    onOpenUrl: (url: String, title: String, source: String) -> Unit,
    onOpenAiService: () -> Unit,
    // 列表状态由 MainActivity 上提持有:切 tab / 进二级页返回后保持滚动位置
    listState: LazyListState,
    reselectSignal: Int = 0,
    vm: OverviewViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()

    // 重击当前 tab:滚回顶部 + 缓存感知刷新(指纹未变零开销,归档更新才重新生成)。
    // lastHandled 防「重新进入组合就自动刷新」(同摘要 tab 套路)。
    var lastHandledReselect by remember { mutableIntStateOf(reselectSignal) }
    LaunchedEffect(reselectSignal) {
        if (reselectSignal != lastHandledReselect) {
            lastHandledReselect = reselectSignal
            listState.animateScrollToItem(0)
            vm.load()
        }
    }

    val dateText = remember { formatToday() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            // 一级根 tab 规格(对齐摘要/更多):titleHero 主标题 + 右侧日期 + 刷新
            AppTopBar(
                title = "今日总览",
                horizontalPadding = 18.dp,
                actions = {
                    // 刷新按钮在左(刷新中转圈),日期文案在右
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        IconButton(onClick = { vm.refresh() }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "重新生成",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        text = dateText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val s = state) {
                is OverviewState.Loading -> OverviewLoading()
                is OverviewState.ConfigMissing -> EmptyState(
                    title = "配置 AI 服务后可用",
                    subtitle = "「今日总览」由你在 设置 → AI 服务 配置的服务实时生成\n(每天 1-2 次,结果当日缓存)",
                    icon = Icons.Outlined.AutoAwesome,
                    actionLabel = "去设置",
                    onAction = onOpenAiService,
                    modifier = Modifier.padding(bottom = BottomBarReservedHeight)
                )
                is OverviewState.Error -> ErrorState(
                    message = s.message,
                    onRetry = { vm.load() },
                    title = "总览生成失败",
                    modifier = Modifier.padding(bottom = BottomBarReservedHeight)
                )
                is OverviewState.Success -> OverviewContent(
                    digest = s.digest,
                    listState = listState,
                    onOpenUrl = onOpenUrl
                )
            }
        }
    }
}

/** 加载中:转圈 + 预期耗时说明(AI 长输出,避免用户误以为卡死)。 */
@Composable
private fun OverviewLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "AI 正在分析今日各源榜单…",
            style = AppText.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "首次生成约需半分钟,之后当日缓存秒开",
            style = AppText.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OverviewContent(
    digest: OverviewDigest,
    listState: LazyListState,
    onOpenUrl: (url: String, title: String, source: String) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = BottomBarReservedHeight),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Breaking News:AI 返回空数组时整模块不渲染(不硬塞)
        if (digest.breaking.isNotEmpty()) {
            item(key = "breaking-header", contentType = "header") {
                SectionHeader(
                    title = "Breaking News",
                    accent = MaterialTheme.colorScheme.tertiary,
                    trailing = {
                        Text(
                            text = "AI 判定",
                            style = AppText.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
            itemsIndexed(
                digest.breaking,
                key = { i, e -> "breaking-$i-${e.url}" },
                contentType = { _, _ -> "breaking" }
            ) { _, entry ->
                BreakingCard(
                    entry = entry,
                    onClick = { onOpenUrl(entry.url, entry.title, SummaryRepository.titleOf(entry.source)) },
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            }
        }

        item(key = "top10-header", contentType = "header") {
            SectionHeader(title = "今日热点 Top${digest.top10.size}")
        }
        itemsIndexed(
            digest.top10,
            key = { i, e -> "top-$i-${e.url}" },
            contentType = { _, _ -> "top10" }
        ) { index, entry ->
            TopEntryRow(
                rank = index + 1,
                entry = entry,
                onClick = { onOpenUrl(entry.url, entry.title, SummaryRepository.titleOf(entry.source)) },
                modifier = Modifier.padding(horizontal = 18.dp)
            )
        }

        item(key = "footer", contentType = "footer") {
            OverviewFooter(digest = digest)
        }
    }
}

/**
 * Breaking 卡 —— [BrandGradient] 高亮(AI 特性专用渐变),onPrimary 文字。
 * 点击进内置 WebView(统一经 openUrl 记录浏览历史)。
 */
@Composable
private fun BreakingCard(
    entry: OverviewEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(BrandGradient)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Text(
            text = entry.title,
            style = AppText.titleItem,
            fontWeight = FontWeight.Bold,
            color = cs.onPrimary
        )
        if (entry.comment.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.comment,
                style = AppText.bodySmall,
                color = cs.onPrimary.copy(alpha = AppAlpha.primaryEmphasis)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceChip(
                title = SummaryRepository.titleOf(entry.source),
                onGradient = true
            )
            if (entry.metrics.isNotBlank()) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = entry.metrics,
                    style = AppText.caption,
                    color = cs.onPrimary.copy(alpha = AppAlpha.primaryEmphasis),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Top10 行:排名徽章 + 原标题 + AI 一句话 + 来源/指标,与其它榜单屏同语言。 */
@Composable
private fun TopEntryRow(
    rank: Int,
    entry: OverviewEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = cs.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, cs.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        onClick = onClick
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            RankBadge(rank = rank, modifier = Modifier.padding(top = 1.dp))
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = AppText.body,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.comment.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.comment,
                        style = AppText.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceChip(title = SummaryRepository.titleOf(entry.source), onGradient = false)
                    if (entry.metrics.isNotBlank()) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = entry.metrics,
                            style = AppText.caption,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 来源徽章:渐变卡上用 onPrimary 底衬,普通卡上用 surfaceContainerHigh。 */
@Composable
private fun SourceChip(title: String, onGradient: Boolean) {
    val cs = MaterialTheme.colorScheme
    val bg = if (onGradient) cs.onPrimary.copy(alpha = AppAlpha.onPrimaryOverlay) else cs.surfaceContainerHigh
    val fg = if (onGradient) cs.onPrimary else cs.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text = title, style = AppText.caption, color = fg, maxLines = 1)
    }
}

/** 页脚:生成时间 / 数据截至 / 模型与 token / 缺源标注。 */
@Composable
private fun OverviewFooter(digest: OverviewDigest) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = buildString {
                append("由 ${digest.model} 生成于 ${formatClock(digest.generatedAt)}")
                if (digest.dataFetchedAt > 0) append(" · 数据截至 ${formatFetchedAt(digest.dataFetchedAt)}")
            },
            style = AppText.caption,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = buildString {
                append("基于 7 源当日归档")
                if (digest.totalTokens > 0) append(" · 消耗 token ${digest.totalTokens}")
                if (digest.missingSources.isNotEmpty()) {
                    append(" · 缺 ${digest.missingSources.joinToString("、") { SummaryRepository.titleOf(it) }}")
                }
            },
            style = AppText.caption,
            color = cs.onSurfaceVariant
        )
    }
}

/** 今天日期(系统时区),格式「M月d日 · 周x」,与摘要 tab 顶栏日期同规格。 */
private fun formatToday(): String =
    runCatching {
        SimpleDateFormat("M月d日 · E", Locale.CHINA).format(Date())
    }.getOrDefault("")

/** 生成时刻格式化为「HH:mm」。 */
private fun formatClock(ms: Long): String =
    runCatching { SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ms)) }.getOrDefault("")

/** 数据时刻格式化为「M月d日 HH:mm」(与摘要卡头同规格)。 */
private fun formatFetchedAt(ms: Long): String =
    runCatching { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ms)) }.getOrDefault("未知")
