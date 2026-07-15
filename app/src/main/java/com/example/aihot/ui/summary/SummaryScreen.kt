package com.example.aihot.ui.summary

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aihot.data.SourceSummary
import com.example.aihot.data.SummaryRepository
import com.example.aihot.ui.SummaryViewModel
import com.example.aihot.ui.UiState
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.AppTopBarDefaults
import com.example.aihot.ui.components.BottomBarReservedHeight
import com.example.aihot.ui.components.ShimmerBox
import com.example.aihot.ui.theme.AppText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 摘要 Tab 根屏 —— 4 个归档源各占一页,[HorizontalPager] 左右滑动切换。
 *
 * 每页是一张全高卡片:顶部源标题(图标 + 名),中部摘要正文(可滚动),底部「查看完整列表 →」。
 * 顶部小圆点指示当前页 / 总页数。点底部按钮进对应源列表页。
 *
 * 数据来自 gitcode 每日归档(08:00 快照),AI 生成复用用户的翻译服务配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    onOpenHackerNews: () -> Unit,
    onOpenGitHubTrending: () -> Unit,
    onOpenHuggingFacePapers: () -> Unit,
    onOpenStormzhangAiNews: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: SummaryViewModel = viewModel()
) {
    val states by vm.states.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val configReady by vm.configReady.collectAsStateWithLifecycle()

    // 卡片配置:key → (标题 / 图标 / 进入列表的回调)
    val cards = listOf(
        SummaryCardSpec(SummaryRepository.SOURCE_KEYS[0], "HackerNews", Icons.Filled.Whatshot, onOpenHackerNews),
        SummaryCardSpec(SummaryRepository.SOURCE_KEYS[1], "GitHub Trending", Icons.Outlined.Apps, onOpenGitHubTrending),
        SummaryCardSpec(SummaryRepository.SOURCE_KEYS[2], "HuggingFace Papers", Icons.Filled.Science, onOpenHuggingFacePapers),
        SummaryCardSpec(SummaryRepository.SOURCE_KEYS[3], "stormzhang AI 资讯", Icons.Filled.Bolt, onOpenStormzhangAiNews)
    )

    val pagerState = rememberPagerState(pageCount = { cards.size })

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "AI 摘要 · ${formatToday()}",
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                actions = {
                    // 刷新按钮:配置就绪时可用,刷新中转圈
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        androidx.compose.material3.IconButton(onClick = { vm.refresh() }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "刷新",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 关键:应用 Scaffold 的 padding(含 topBar 高度),否则顶栏会遮挡内容
                .padding(padding)
                // 根 tab 底栏是悬浮 overlay(Scaffold padding 不含它),底部预留高度
                // 避免卡片被浮动药丸底栏遮挡
                .padding(bottom = BottomBarReservedHeight)
        ) {
            // 顶部:数据来源提示 + 页面指示点
            SummaryHeaderRow(
                configReady = configReady,
                onOpenSettings = onOpenSettings,
                currentPage = pagerState.currentPage,
                pageCount = cards.size
            )

            // 左右滑动的卡片页(每页填满剩余空间)。底部留白由外层 Column 的
            // bottom padding 负责(预留浮动底栏高度),这里只给水平边距。
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp),
                pageSpacing = 14.dp
            ) { pageIndex ->
                val spec = cards[pageIndex]
                SummaryCardPage(
                    spec = spec,
                    state = states[spec.source] ?: UiState.Loading,
                    onRetry = { vm.retry(spec.source) },
                    onOpenSettings = onOpenSettings
                )
            }
        }
    }
}

private data class SummaryCardSpec(
    val source: String,
    val title: String,
    val icon: ImageVector,
    val onOpen: () -> Unit
)

/**
 * 顶部行:左 = 数据来源 + 配置入口;右 = 页面指示圆点(当前页实心 accent,其余空心)。
 */
@Composable
private fun SummaryHeaderRow(
    configReady: Boolean,
    onOpenSettings: () -> Unit,
    currentPage: Int,
    pageCount: Int
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "基于每日归档 · 左右滑动",
            style = AppText.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (!configReady) {
            TextButton(
                onClick = onOpenSettings,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    "配置 AI 服务",
                    style = AppText.caption,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        // 页面指示点
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(pageCount) { i ->
                val isCurrent = i == currentPage
                Box(
                    modifier = Modifier
                        .size(if (isCurrent) 7.dp else 5.dp)
                        .clip(CircleShape)
                        .background(
                            if (isCurrent) accent
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                )
            }
        }
    }
}

/**
 * 单张源摘要页 —— 一张全高卡片(描边圆角),内含:
 *  - 头部:图标 + 源名 + 数据时刻
 *  - 中部:摘要正文(可纵向滚动),按 state 分支
 *  - 底部:「查看完整列表 →」按钮(进对应源列表页)
 *
 * 按钮用 TextButton 吸收自己的点击,不依赖卡片整体点击(pager 页面整页可滑,按钮区单独可点)。
 */
@Composable
private fun SummaryCardPage(
    spec: SummaryCardSpec,
    state: UiState<SourceSummary>,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val accent = cs.primary
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = cs.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 头部:图标 + 标题 + 数据时刻
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        spec.icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = spec.title,
                        style = AppText.titleItem,
                        fontWeight = FontWeight.Bold,
                        color = cs.onSurface
                    )
                    if (state is UiState.Success) {
                        Text(
                            text = "数据时刻：${formatFetchedAt(state.data.fetchedAtMs)}",
                            style = AppText.caption,
                            color = cs.outline
                        )
                    }
                }
            }

            // 中部:摘要正文(可滚动),按 state 分支
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                when (state) {
                    is UiState.Loading -> SummarySkeleton()
                    is UiState.Error -> SummaryError(
                        message = state.message,
                        isConfigMissing = state.message == SummaryRepository.CONFIG_MISSING,
                        onRetry = onRetry,
                        onOpenSettings = onOpenSettings
                    )
                    is UiState.Success -> SummaryBody(text = state.data.text)
                }
            }

            // 底部:查看完整列表按钮
            Surface(
                onClick = spec.onOpen,
                color = cs.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "查看完整列表",
                        style = AppText.body,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "查看完整列表",
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 摘要正文 —— 纵向滚动列表,按行渲染(prompt 输出以「• 」分条)。
 * 每行解析 **加粗** 标记成富文本(标题加粗 + 正文常规)。
 */
@Composable
private fun SummaryBody(text: String) {
    val lines = text.lines().filter { it.isNotBlank() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(lines) { line ->
            Text(
                text = renderRichLine(line),
                style = AppText.body,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * 把单行文本解析为 [AnnotatedString]:**...** 段落渲染为 SemiBold,其余 Normal。
 *
 * prompt 要求每条格式「• **标题**：简述」,加粗段即标题,视觉上与正文拉开层级。
 * 实现:正则切 ** 包裹的段,交替应用 Normal / Bold 样式。支持一行内多处加粗。
 */
private fun renderRichLine(line: String): AnnotatedString {
    // 去掉行首 bullet 与多余空白,统一缩进由排版负责
    val raw = line.trim().removePrefix("•").trimStart()
    if (raw.isBlank()) return AnnotatedString(line)
    val boldStyle = SpanStyle(fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        var idx = 0
        // 匹配 **...**(非贪婪,不允许内部换行)
        val regex = Regex("\\*\\*(.+?)\\*\\*")
        var lastEnd = 0
        for (m in regex.findAll(raw)) {
            if (m.range.first > lastEnd) append(raw.substring(lastEnd, m.range.first))
            withStyle(boldStyle) { append(m.groupValues[1]) }
            lastEnd = m.range.last + 1
            idx = lastEnd
        }
        if (idx < raw.length) append(raw.substring(idx))
    }
}

@Composable
private fun SummarySkeleton() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(6) { i ->
            val width = if (i % 3 == 2) 0.7f else if (i % 3 == 1) 0.9f else 1f
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ShimmerBox(modifier = Modifier.fillMaxWidth(width).height(14.dp), cornerRadius = 4.dp)
                ShimmerBox(modifier = Modifier.fillMaxWidth(0.95f).height(12.dp), cornerRadius = 4.dp)
            }
        }
    }
}

@Composable
private fun SummaryError(
    message: String,
    isConfigMissing: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val display = when {
        isConfigMissing -> "尚未配置 AI 服务,无法生成摘要"
        else -> message
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = display,
            style = AppText.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(8.dp))
        if (isConfigMissing) {
            TextButton(onClick = onOpenSettings) {
                Text("去配置", style = AppText.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        } else {
            TextButton(onClick = onRetry) {
                Text("重试", style = AppText.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 把归档 fetchedAtMs(北京时间每日 08:00 附近)格式化成「M月d日 HH:mm」。 */
private fun formatFetchedAt(ms: Long): String {
    if (ms <= 0L) return "未知"
    return runCatching {
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ms))
    }.getOrDefault("未知")
}

/** 今天日期(系统时区),格式「M月d日」,用于顶栏标题。 */
private fun formatToday(): String =
    runCatching {
        SimpleDateFormat("M月d日", Locale.CHINA).format(Date())
    }.getOrDefault("")
