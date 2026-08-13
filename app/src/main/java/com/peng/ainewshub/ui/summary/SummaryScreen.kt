package com.peng.ainewshub.ui.summary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.data.SourceKeys
import com.peng.ainewshub.data.SummaryRepository
import com.peng.ainewshub.ui.SummaryViewModel
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.AppTopBar
import kotlinx.coroutines.launch

/**
 * AI 摘要 Tab 根屏 —— 8 个归档源各占一页,[HorizontalPager] 左右滑动切换。
 *
 * 顶部为提示行 + 源名 chips 导航(可点跳页),与「历史摘要」按日期页
 * (SummaryDateScreen)共用同一 [SummaryHeaderRow];单页实现(紧凑扁头 / 条目正文)
 * 收口在 [SummaryCard.kt],两屏保持同构。卡片顺序跟随 sourceKeys(用户在「信息源」
 * 页自定义的顺序);pagerState 由 MainActivity 上提持有。
 *
 * v2 结构化条目 url 非空时整行可点,经 [onOpenUrl] 直达内置 WebView。
 *
 * 底部与总览页同一做法:内容可滚入浮动药丸 TAB 之下,可视区收在药丸底缘
 * (navigationBarsPadding + 距底 16dp),末条停到药丸之上由页内列表 contentPadding
 * 负责(reserveBottomBarSpace,见 [SummaryCardPage])。
 *
 * 数据来自 gitcode 每日归档快照顶层的 `ai_summary_v2` 字段(由数据流水线预生成),App 端直接读取,
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
    // 条目点击:经 MainActivity openUrl 单点入口打开内置 WebView(统一记浏览历史)
    onOpenUrl: (url: String, title: String, source: String) -> Unit,
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
            SourceKeys.HACKERNEWS -> onOpenHackerNews
            SourceKeys.GITHUB_TRENDING -> onOpenGitHubTrending
            SourceKeys.HUGGINGFACE_PAPERS -> onOpenHuggingFacePapers
            SourceKeys.PRODUCTHUNT -> onOpenProductHunt
            SourceKeys.RUNDOWN_AI -> onOpenRundownAi
            SourceKeys.STORMZHANG_AI -> onOpenStormzhangAiNews
            SourceKeys.OPENAI_ANTHROPIC_NEWS -> onOpenOpenAiAnthropicNews
            SourceKeys.AIHOT_FEATURED -> onOpenFeaturedHub
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            // 一级根 tab 规格:titleHero 主标题;刷新收口到下拉手势,日期仅总览 tab 保留,
            // 顶栏不再有 actions。horizontalPadding=18 让标题与下方内容(18dp 边距)左对齐
            AppTopBar(
                title = stringResource(R.string.summary_title),
                horizontalPadding = 18.dp
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
            // 顶部:数据来源提示 + 源名 chips 导航(可点跳页,当前页高亮)
            SummaryHeaderRow(
                currentPage = pagerState.currentPage,
                pageTitles = cards.map { it.title },
                onSelect = { i -> scope.launch { pagerState.animateScrollToPage(i) } }
            )

            // 内容 Pager:每页一个源的摘要(平铺无卡片),左右滑切源;
            // 下拉手势经 PullToRefreshBox 触发 vm.refresh()(纵向手势不与横向 Pager 冲突)
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize()
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    pageSpacing = 14.dp
                ) { pageIndex ->
                    val spec = cards.getOrNull(pageIndex) ?: return@HorizontalPager
                    val context = LocalContext.current
                    SummaryCardPage(
                        spec = spec,
                        state = states[spec.source] ?: UiState.Loading,
                        onRetry = { vm.retry(spec.source) },
                        // 根 tab 底栏悬浮:页内列表底部补「药丸高 + 16dp」,末条可停到药丸之上
                        reserveBottomBarSpace = true,
                        // v2 条目(url 非空)整行可点直达原文;标题取摘要标题,来源标签取源名
                        onOpenItem = { item ->
                            onOpenUrl(item.url, item.title, SummaryRepository.titleOf(context, spec.source))
                        }
                    )
                }
            }
        }
    }
}
