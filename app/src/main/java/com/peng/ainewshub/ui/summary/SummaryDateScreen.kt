package com.peng.ainewshub.ui.summary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.data.repo.SummaryRepository
import com.peng.ainewshub.ui.SummaryArchiveViewModel
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import kotlinx.coroutines.launch

/**
 * 历史摘要 —— 指定日期的全源摘要卡页。
 *
 * 卡片实现与摘要 Tab 完全同构(共享 [SummaryCardPage] / [SummaryHeaderRow]),
 * 差异仅在:
 *  - 数据按日期经 history 索引寻址([SummaryArchiveViewModel.loadDate]);
 *  - 二级页语义:顶栏带返回、无刷新按钮、底部不预留浮动底栏高度;
 *  - 卡片无「查看完整列表」出口(列表页展示的是今日数据,从历史跳转语义不符);
 *    v2 条目 url 非空时仍可点,经 [onOpenUrl] 直达原文(WebView 与浏览历史语义与日期无关)。
 *
 * [pagerState] 由 MainActivity 按 Page 值上提持有(pagePagerStates),进 Web 页
 * 返回后保持所在卡片;VM 按日期隔离实例(`viewModel(key = "summary-date-$date")`)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryDateScreen(
    date: String,
    onBack: () -> Unit,
    onOpenUrl: (url: String, title: String, source: String) -> Unit,
    pagerState: PagerState,
    vm: SummaryArchiveViewModel = viewModel(key = "summary-date-$date")
) {
    val states by vm.dateStates.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(date) { vm.loadDate(date) }

    // 历史页用全集固定顺序(不跟随用户自定义顺序),无列表出口:onOpenFor 一律 null
    val cards = summaryCardSpecs(SummaryRepository.SOURCE_KEYS) { null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.summary_date_title, date),
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
            // 历史日期页回看的是往日内容,无「新内容未查看」语义(那是摘要 Tab
            // 对当下批次的信号)——复用同一头行组件,圆点恒不亮
            SummaryHeaderRow(
                currentPage = pagerState.currentPage,
                pages = cards.map { SummaryHeaderPage(it.title, false) },
                hint = stringResource(R.string.summary_date_hint),
                onSelect = { i -> scope.launch { pagerState.animateScrollToPage(i) } }
            )
            HorizontalPager(
                state = pagerState,
                // 二级页无悬浮底栏,pager 填满剩余高度(不用根 tab 的底部预留)
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 18.dp),
                pageSpacing = 14.dp
            ) { pageIndex ->
                val spec = cards[pageIndex]
                val context = LocalContext.current
                SummaryCardPage(
                    spec = spec,
                    state = states[spec.source] ?: UiState.Loading,
                    onRetry = { vm.retrySource(date, spec.source) },
                    onOpenItem = { item ->
                        onOpenUrl(item.url, item.title, SummaryRepository.titleOf(context, spec.source))
                    }
                )
            }
        }
    }
}
