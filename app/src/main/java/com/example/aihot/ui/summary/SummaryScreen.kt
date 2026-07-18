package com.example.aihot.ui.summary

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.example.aihot.ui.anim.Motion
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.BottomBarReservedHeight
import com.example.aihot.ui.components.ShimmerBox
import com.example.aihot.ui.theme.AppAlpha
import com.example.aihot.ui.theme.AppText
import com.example.aihot.ui.theme.BrandGradient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 摘要 Tab 根屏 —— 4 个归档源各占一页,[HorizontalPager] 左右滑动切换。
 *
 * 每页一张全高卡片:
 *  - 品牌渐变卡头([BrandGradient],AI 特性专用):onPrimary 圆形底衬源图标
 *    (tint 用源强调色)+ 源名 + 数据时刻,右侧 AutoAwesome 小图标强化 AI 语义
 *  - 摘要正文(可滚动)条目化:两位序号(源强调色,Bold)+ 富文本行,序号与首行基线对齐
 *  - 底部「查看完整列表 →」(primary 加粗)
 * 顶部提示行右侧是页面指示器:当前页横向胶囊 / 未选中圆点,tween 过渡,可点跳页。
 * 4 张卡保持同构(同一产品语言),差异化只靠卡头图标与序号强调色。
 *
 * 数据来自 gitcode 每日归档快照顶层的 `ai_summary` 字段(由数据流水线预生成),App 端直接读取,
 * 不再运行时调用 AI API。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    onOpenHackerNews: () -> Unit,
    onOpenGitHubTrending: () -> Unit,
    onOpenHuggingFacePapers: () -> Unit,
    onOpenStormzhangAiNews: () -> Unit,
    onOpenProductHunt: () -> Unit,
    onOpenRundownAi: () -> Unit,
    // 页码状态由 MainActivity 上提持有:进二级页返回后保持所在卡片(见其内注释)
    pagerState: PagerState,
    reselectSignal: Int = 0,
    vm: SummaryViewModel = viewModel()
) {
    val states by vm.states.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 卡片配置:key → (标题 / 图标 / 进入列表的回调)
    val cards = listOf(
        SummaryCardSpec(SummaryRepository.SOURCE_KEYS[0], "HackerNews", Icons.Filled.Whatshot, onOpenHackerNews),
        SummaryCardSpec(SummaryRepository.SOURCE_KEYS[1], "GitHub Trending", Icons.Outlined.Apps, onOpenGitHubTrending),
        SummaryCardSpec(SummaryRepository.SOURCE_KEYS[2], "HuggingFace Papers", Icons.Filled.Science, onOpenHuggingFacePapers),
        SummaryCardSpec(SummaryRepository.SOURCE_KEYS[3], "stormzhang AI 资讯", Icons.Filled.Bolt, onOpenStormzhangAiNews),
        SummaryCardSpec(SummaryRepository.SOURCE_KEYS[4], "Product Hunt", Icons.Filled.RocketLaunch, onOpenProductHunt),
        SummaryCardSpec(SummaryRepository.SOURCE_KEYS[5], "The Rundown AI", Icons.AutoMirrored.Filled.Article, onOpenRundownAi)
    )

    // 重击当前 tab(reselectSignal 递增):滑回第一张卡并刷新全部源。
    // lastHandled 记录已消费的 tick:切 tab 回来/从二级页返回时本屏重新进入组合,
    // LaunchedEffect 会以旧的 reselectSignal 再跑一遍,与 lastHandled 相等即跳过,
    // 避免「页面重新可见就自动刷新」。
    var lastHandledReselect by remember { mutableIntStateOf(reselectSignal) }
    LaunchedEffect(reselectSignal) {
        if (reselectSignal != lastHandledReselect) {
            lastHandledReselect = reselectSignal
            pagerState.animateScrollToPage(0)
            vm.refresh()
        }
    }

    // 顶栏日期(与精选 tab 同规格「M月d日 · 周x」):组合期算一次即可
    val dateText = remember { formatToday() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            // 一级根 tab 规格(对齐精选/更多):titleHero 主标题 + 右侧日期,保留刷新。
            // horizontalPadding=18 让标题与下方卡片(18dp 边距)左对齐
            AppTopBar(
                title = "AI 摘要",
                horizontalPadding = 18.dp,
                actions = {
                    Text(
                        text = dateText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 刷新按钮:刷新中转圈
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        IconButton(onClick = { vm.refresh() }) {
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
            // 顶部:数据来源提示 + 页面指示器(可点跳页)
            SummaryHeaderRow(
                currentPage = pagerState.currentPage,
                pageCount = cards.size,
                onDotClick = { i -> scope.launch { pagerState.animateScrollToPage(i) } }
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
                    onRetry = { vm.retry(spec.source) }
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
 * 顶部行:左 = 数据来源提示;右 = 页面指示器(当前页横向胶囊,未选中圆点)。
 * 指示器可点击直接跳页;宽度/颜色随切页 tween 过渡(Motion.SHORT)。
 */
@Composable
private fun SummaryHeaderRow(
    currentPage: Int,
    pageCount: Int,
    onDotClick: (Int) -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "基于每日归档 · 左右滑动",
            style = AppText.caption,
            color = cs.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.size(8.dp))
        // 页面指示器(可点击跳页):当前页 16×6 胶囊(primary),未选中 6dp 圆点
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(pageCount) { i ->
                val isCurrent = i == currentPage
                val dotWidth by animateDpAsState(
                    targetValue = if (isCurrent) 16.dp else 6.dp,
                    animationSpec = tween(Motion.SHORT, easing = Motion.EmphasizedDecel),
                    label = "pageIndicatorWidth"
                )
                val dotColor by animateColorAsState(
                    targetValue = if (isCurrent) cs.primary
                    else cs.onSurfaceVariant.copy(alpha = AppAlpha.hairlineOverlay),
                    animationSpec = tween(Motion.SHORT, easing = Motion.EmphasizedDecel),
                    label = "pageIndicatorColor"
                )
                Box(
                    modifier = Modifier
                        .size(width = dotWidth, height = 6.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                        .clickable { onDotClick(i) }
                )
            }
        }
    }
}

/**
 * 单张源摘要页 —— 一张全高卡片(描边圆角),内含:
 *  - 品牌渐变卡头([SummaryCardHeader]):源图标 + 源名 + 数据时刻
 *  - 中部:摘要正文(可纵向滚动),按 state 分支
 *  - 底部:「查看完整列表 →」按钮(进对应源列表页)
 *
 * 底部按钮区用独立 Surface 吸收自己的点击,不依赖卡片整体点击
 * (pager 页面整页可滑,按钮区单独可点)。
 */
@Composable
private fun SummaryCardPage(
    spec: SummaryCardSpec,
    state: UiState<SourceSummary>,
    onRetry: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val accent = sourceAccentOf(spec.source)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = cs.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, cs.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 品牌渐变卡头(Surface 按卡片 shapes.medium 裁切,上圆角自然贴合)
            SummaryCardHeader(spec = spec, state = state, accent = accent)

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
                        onRetry = onRetry
                    )
                    is UiState.Success -> SummaryBody(text = state.data.text, accent = accent)
                }
            }

            // 底部:查看完整列表按钮(primary 加粗;不随源强调色,保持出口一致)
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
                        fontWeight = FontWeight.Bold,
                        color = cs.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "查看完整列表",
                        tint = cs.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 品牌渐变卡头 —— 约 66-72dp 高的 [BrandGradient] 横带(上圆角随卡片 shapes.medium)。
 *
 * 卡头内:onPrimary 圆形底衬源图标(tint 用源强调色)+ 源名(onPrimary,titleItem
 * 自带 SemiBold)+ 数据时刻(onPrimary 85%);右侧 AutoAwesome 弱化白小图标强化 AI 语义。
 * 文字/底衬一律走 onPrimary 系:浅色模式是白字压深渐变,深色模式是深字压浅渐变,
 * 两种模式对比度都有保证(与今日热点渐变头同一处理)。
 */
@Composable
private fun SummaryCardHeader(
    spec: SummaryCardSpec,
    state: UiState<SourceSummary>,
    accent: Color
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandGradient)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(cs.onPrimary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                spec.icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = spec.title,
                style = AppText.titleItem,
                color = cs.onPrimary
            )
            if (state is UiState.Success) {
                Text(
                    text = "数据时刻：${formatFetchedAt(state.data.fetchedAtMs)}",
                    style = AppText.caption,
                    color = cs.onPrimary.copy(alpha = AppAlpha.primaryEmphasis)
                )
            }
        }
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = cs.onPrimary.copy(alpha = AppAlpha.primaryEmphasis),
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 源强调色 —— 卡头图标 tint 与条目序号的差异化锚点。
 * 4 张卡同构(同一产品语言),仅靠强调色与图标区分源。
 */
@Composable
private fun sourceAccentOf(source: String): Color {
    val cs = MaterialTheme.colorScheme
    return when (source) {
        "hackernews" -> cs.tertiary            // 暖橙,呼应 HN 品牌与热度语义
        "github-trending" -> cs.primary
        "huggingface-papers" -> cs.primary
        "stormzhang-ai" -> cs.secondary        // 品牌紫,贴「AI 资讯」语义
        "producthunt" -> cs.primary            // PH 品牌橙红由 SourceBrand 承载,卡片用 primary
        "rundown-ai" -> cs.secondary           // 品牌紫,贴「AI newsletter」语义(与 stormzhang 同系)
        else -> cs.primary
    }
}

/**
 * 摘要正文 —— 条目化排版:每行一条,两位序号(01、02……源强调色 Bold)
 * 与正文首行基线对齐,条目间距 12dp。每行解析 **加粗** 标记成富文本
 * (标题加粗 + 正文常规),bullet 符号 trim 掉,由序号取代条目标记。
 */
@Composable
private fun SummaryBody(text: String, accent: Color) {
    val lines = text.lines().filter { it.isNotBlank() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        itemsIndexed(lines) { index, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "%02d".format(index + 1),
                    style = AppText.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier.alignByBaseline()
                )
                Text(
                    text = renderRichLine(line),
                    style = AppText.body,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.alignByBaseline()
                )
            }
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
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 卡片内嵌的紧凑错误态:CloudOff 小图标 + 口语化标题 + 底层错误详情
        Icon(
            Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = "摘要暂时没加载出来",
            style = AppText.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = message,
            style = AppText.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(8.dp))
        TextButton(onClick = onRetry) {
            Text("重试", style = AppText.bodySmall, fontWeight = FontWeight.SemiBold)
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

/** 今天日期(系统时区),格式「M月d日 · 周x」,与精选 tab 顶栏日期同规格。 */
private fun formatToday(): String =
    runCatching {
        SimpleDateFormat("M月d日 · E", Locale.CHINA).format(Date())
    }.getOrDefault("")
