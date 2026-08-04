package com.peng.ainewshub.ui.summary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.ui.SummaryViewModel
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.AppTopBar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 摘要 Tab 根屏 —— 8 个归档源各占一页,[HorizontalPager] 左右滑动切换。
 *
 * 顶部为提示行 + 右上角圆点页指示器(可点跳页),与「历史摘要」按日期页
 * (SummaryDateScreen)共用同一 [SummaryHeaderRow];单页实现(紧凑扁头 / 条目正文)
 * 收口在 [SummaryCard.kt],两屏保持同构。卡片顺序跟随 sourceKeys(用户在「信息源」
 * 页自定义的顺序);pagerState 由 MainActivity 上提持有。
 *
 * 底部与总览页同一做法:内容可滚入浮动药丸 TAB 之下,可视区收在药丸底缘
 * (navigationBarsPadding + 距底 16dp),末条停到药丸之上由页内列表 contentPadding
 * 负责(reserveBottomBarSpace,见 [SummaryCardPage])。
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

    // 重击当前 tab(reselectSignal 递增):滑回第一个源并刷新全部源。
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
            // horizontalPadding=18 让标题与下方内容(18dp 边距)左对齐
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
                // 与总览页同一做法:内容可滚入药丸 TAB 之下,但可视区不超出药丸底缘
                // (navigationBarsPadding + 距底 16dp,对齐 MainActivity 底栏定位),
                // 不再整体预留 BottomBarReservedHeight 造成底部大块空白
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            // 顶部:数据来源提示 + 页面指示器(右上角圆点,可点跳页)
            SummaryHeaderRow(
                currentPage = pagerState.currentPage,
                pageCount = cards.size,
                onDotClick = { i -> scope.launch { pagerState.animateScrollToPage(i) } }
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
                    onRetry = { vm.retry(spec.source) },
                    // 根 tab 底栏悬浮:页内列表底部补「药丸高 + 16dp」,末条可停到药丸之上
                    reserveBottomBarSpace = true
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
