package com.peng.ainewshub.ui.summary

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.ui.SummaryViewModel
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.BottomBarReservedHeight
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 摘要 Tab 根屏 —— 顶部源 chips 标签行 + 内容 [HorizontalPager],两者双向同步。
 *
 * 8 个归档源各占一页:点 chip 一键直达(animateScrollToPage),内容区左右滑切页
 * (滑动后 chips 选中态跟随,并自动滚动保证选中 chip 进入可视区);pagerState 由
 * MainActivity 上提持有。单页实现(紧凑扁头 / 条目正文 / 底部出口)收口在
 * [SummaryCard.kt],与「历史摘要」按日期页(SummaryDateScreen)共用。
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
    onOpenOpenAiAnthropicNews: () -> Unit,
    onOpenFeaturedHub: () -> Unit,
    // 页码状态由 MainActivity 上提持有:切 tab / 进二级页返回后保持所在源(见其内注释)
    pagerState: PagerState,
    reselectSignal: Int = 0,
    vm: SummaryViewModel = viewModel()
) {
    val states by vm.states.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val sourceKeys by vm.sourceKeys.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val chipsScrollState = rememberScrollState()

    // 卡片配置:key → (标题 / 图标 / 进入列表的回调)。顺序跟随 sourceKeys(用户在「信息源」
    // 页拖拽自定义的顺序)。标题 / 图标来自 sourceMeta 单点定义。
    val cards = summaryCardSpecs(sourceKeys) { source ->
        when (source) {
            "hackernews" -> onOpenHackerNews
            "github-trending" -> onOpenGitHubTrending
            "huggingface-papers" -> onOpenHuggingFacePapers
            "producthunt" -> onOpenProductHunt
            "rundown-ai" -> onOpenRundownAi
            "stormzhang-ai" -> onOpenStormzhangAiNews
            "openai-anthropic-news" -> onOpenOpenAiAnthropicNews
            "aihot-featured" -> onOpenFeaturedHub
            else -> null
        }
    }

    // 重击当前 tab(reselectSignal 递增):滑回第一个源并刷新全部源(chips 滚动由
    // SourceChipsRow 的选中跟随效应自动归位)。
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
            // 一级根 tab 规格(对齐总览/更多):titleHero 主标题 + 右侧日期,保留刷新。
            // horizontalPadding=18 让标题与下方 chips(18dp 边距)左对齐
            AppTopBar(
                title = "AI 摘要",
                horizontalPadding = 18.dp,
                actions = {
                    // 刷新按钮在左(刷新中转圈),日期文案在右;
                    // 转圈与按钮同占 32dp,保证与日期文案的间距两种状态下一致
                    if (isRefreshing) {
                        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        IconButton(onClick = { vm.refresh() }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "刷新",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 关键:应用 Scaffold 的 padding(含 topBar 高度),否则顶栏会遮挡内容
                .padding(padding)
                // 根 tab 底栏是悬浮 overlay(Scaffold padding 不含它),底部预留高度
                // 避免内容被浮动药丸底栏遮挡
                .padding(bottom = BottomBarReservedHeight)
        ) {
            // 源 chips 标签行:选中态跟随 Pager 当前页;点击 = 切页
            SourceChipsRow(
                specs = cards,
                selectedIndex = pagerState.currentPage,
                onSelectIndex = { i -> scope.launch { pagerState.animateScrollToPage(i) } },
                scrollState = chipsScrollState
            )

            // 内容 Pager:每页一个源的摘要(平铺无卡片),左右滑切源
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp),
                pageSpacing = 14.dp
            ) { pageIndex ->
                val spec = cards.getOrNull(pageIndex) ?: return@HorizontalPager
                SummaryCardPage(
                    spec = spec,
                    state = states[spec.source] ?: UiState.Loading,
                    onRetry = { vm.retry(spec.source) }
                )
            }
        }
    }
}

/**
 * 源 chips 标签行 —— 横向可滚单行,与内容 Pager 双向同步,跟随用户在「信息源」页
 * 自定义的顺序。
 *
 * 视觉复用 ItemsScreen 分类 chips 语言:药丸 FilterChip,选中 primary 实底 +
 * onPrimary 字,未选中 surface 底 + outlineVariant 描边;带源图标(选中/未选中
 * 分别 onPrimary/onSurfaceVariant,不做彩色,避免一排 chips 五颜六色)。
 *
 * 选中跟随:[selectedIndex] 变化(点 chip / 滑 Pager 皆可能)时自动横向滚动,
 * 让选中 chip 停靠在可视区左缘(18dp 处);各 chip 的 x 偏移由 onGloballyPositioned 记录。
 */
@Composable
private fun SourceChipsRow(
    specs: List<SummaryCardSpec>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    scrollState: ScrollState
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    // 各 chip 在 Row 内容里的 x 偏移(内容坐标系,与滚动量无关)
    val chipOffsets = remember { mutableStateMapOf<Int, Int>() }

    LaunchedEffect(selectedIndex, specs.size) {
        // 等 onGloballyPositioned 回填偏移后再滚:首跑时 chipOffsets 尚为空,
        // 直接返回会导致切 tab 返回后选中 chip 停在可视区外
        val x = snapshotFlow { chipOffsets[selectedIndex] }.filterNotNull().first()
        val padPx = with(density) { 18.dp.roundToPx() }
        scrollState.animateScrollTo((x - padPx).coerceAtLeast(0))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        specs.forEachIndexed { index, spec ->
            val selected = index == selectedIndex
            FilterChip(
                selected = selected,
                onClick = { onSelectIndex(index) },
                label = { Text(spec.title) },
                leadingIcon = {
                    Icon(
                        spec.icon,
                        contentDescription = null,
                        tint = if (selected) cs.onPrimary else cs.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                },
                // 完全圆角(药丸),对齐设计系统的 pill 形标签
                shape = CircleShape,
                border = BorderStroke(
                    1.dp,
                    if (selected) Color.Transparent else cs.outlineVariant
                ),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = cs.primary,
                    selectedLabelColor = cs.onPrimary,
                    containerColor = cs.surface
                ),
                modifier = Modifier.onGloballyPositioned { chipOffsets[index] = it.boundsInParent().left.toInt() }
            )
        }
    }
}

/** 今天日期(系统时区),格式「M月d日 · 周x」,与精选 tab 顶栏日期同规格。 */
private fun formatToday(): String =
    runCatching {
        SimpleDateFormat("M月d日 · E", Locale.CHINA).format(Date())
    }.getOrDefault("")
