package com.example.aihot.ui.summary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aihot.ui.SummaryViewModel
import com.example.aihot.ui.UiState
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.BottomBarReservedHeight
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 摘要 Tab 根屏 —— 7 个归档源各占一页,[HorizontalPager] 左右滑动切换。
 *
 * 卡片实现(spec / 顶部提示行 / 单张源摘要页)收口在 [SummaryCard.kt],
 * 与「历史摘要」按日期页(SummaryDateScreen)共用;本屏只负责顶栏、刷新与
 * reselect 消费。7 张卡保持同构(同一产品语言),差异化只靠卡头图标与序号强调色。
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
    onOpenFeaturedHub: () -> Unit,
    // 页码状态由 MainActivity 上提持有:进二级页返回后保持所在卡片(见其内注释)
    pagerState: PagerState,
    reselectSignal: Int = 0,
    vm: SummaryViewModel = viewModel()
) {
    val states by vm.states.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 卡片配置:key → (标题 / 图标 / 进入列表的回调)。顺序对齐 SOURCE_KEYS 与 More 页浏览组。
    val cards = summaryCardSpecs { source ->
        when (source) {
            "hackernews" -> onOpenHackerNews
            "github-trending" -> onOpenGitHubTrending
            "huggingface-papers" -> onOpenHuggingFacePapers
            "producthunt" -> onOpenProductHunt
            "rundown-ai" -> onOpenRundownAi
            "stormzhang-ai" -> onOpenStormzhangAiNews
            "aihot-featured" -> onOpenFeaturedHub
            else -> null
        }
    }

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

/** 今天日期(系统时区),格式「M月d日 · 周x」,与精选 tab 顶栏日期同规格。 */
private fun formatToday(): String =
    runCatching {
        SimpleDateFormat("M月d日 · E", Locale.CHINA).format(Date())
    }.getOrDefault("")
