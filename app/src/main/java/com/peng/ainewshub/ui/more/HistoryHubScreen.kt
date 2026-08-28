package com.peng.ainewshub.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.SummaryArchiveViewModel
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.SegmentedOptionRow
import com.peng.ainewshub.ui.overview.OverviewArchiveContent
import com.peng.ainewshub.ui.overview.OverviewArchiveViewModel
import com.peng.ainewshub.ui.summary.SummaryArchiveContent
import com.peng.ainewshub.ui.trends.TrendsArchiveContent
import com.peng.ainewshub.ui.trends.TrendsArchiveViewModel

/**
 * 「历史回顾」hub —— 总览/摘要/热词三段合一的按日期回看二级页(更多页「历史」组
 * 单一入口),替代原三个独立历史入口:同一「按日期回看」心智收拢到一页。
 *
 * 结构:顶栏返回 + 标题下 [SegmentedOptionRow] 三段切换(设置页同款分段控件)
 * + 当前段日期列表(内容 composable 抽自原三个 Archive 屏,VM/滚动状态由本页持有)。
 *
 * 状态语义:
 *  - 段位为瞬态偏好(rememberSaveable):进程死亡恢复回落第一段,属可接受降级
 *    (各段 VM 的 loadDates 幂等,重进即秒回);
 *  - 段懒加载:进段才触发对应索引拉取(trends_history 是独立文件,不进热词段
 *    不下载),错误态段内重试同样走 onRetry;
 *  - 单个上提 listState 由当前段独占复用:切段即换列表,滚回顶部是自然语义,
 *    同时完全满足「列表状态上提」导航约定(见 docs/agents/navigation.md)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryHubScreen(
    onSelectOverviewDate: (String) -> Unit,
    onSelectSummaryDate: (String) -> Unit,
    onSelectTrendsDate: (String) -> Unit,
    onBack: () -> Unit,
    listState: LazyListState
) {
    var segment by rememberSaveable { mutableIntStateOf(HistorySegment.OVERVIEW.ordinal) }
    val overviewVm: OverviewArchiveViewModel = viewModel()
    val summaryVm: SummaryArchiveViewModel = viewModel()
    val trendsVm: TrendsArchiveViewModel = viewModel()

    // 进段才拉对应索引(loadDates 幂等,Success 短路);切段滚回顶(换列表)
    LaunchedEffect(segment) {
        when (HistorySegment.entries[segment]) {
            HistorySegment.OVERVIEW -> overviewVm.loadDates()
            HistorySegment.SUMMARY -> summaryVm.loadDates()
            HistorySegment.TRENDS -> trendsVm.loadDates()
        }
        listState.scrollToItem(0)
    }

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
            SegmentedOptionRow(
                options = listOf(
                    stringResource(R.string.history_hub_seg_overview),
                    stringResource(R.string.history_hub_seg_summary),
                    stringResource(R.string.history_hub_seg_trends)
                ),
                selectedIndex = segment,
                onSelect = { segment = it },
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
            )
            val overviewState by overviewVm.dates.collectAsStateWithLifecycle()
            val summaryState by summaryVm.dates.collectAsStateWithLifecycle()
            val trendsState by trendsVm.dates.collectAsStateWithLifecycle()
            when (HistorySegment.entries[segment]) {
                HistorySegment.OVERVIEW -> OverviewArchiveContent(
                    state = overviewState,
                    listState = listState,
                    onSelectDate = onSelectOverviewDate,
                    onRetry = { overviewVm.loadDates() }
                )
                HistorySegment.SUMMARY -> SummaryArchiveContent(
                    state = summaryState,
                    listState = listState,
                    onSelectDate = onSelectSummaryDate,
                    onRetry = { summaryVm.loadDates() }
                )
                HistorySegment.TRENDS -> TrendsArchiveContent(
                    state = trendsState,
                    listState = listState,
                    onSelectDate = onSelectTrendsDate,
                    onRetry = { trendsVm.loadDates() }
                )
            }
        }
    }
}

/** hub 三段:顺序即分段控件顺序与默认进入段(总览)。 */
private enum class HistorySegment { OVERVIEW, SUMMARY, TRENDS }
