package com.peng.ainewshub.ui.trends

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.SectionNotice
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.RankRowSkeletonList

/**
 * 「热词」二级页 —— 近 N 天热词榜 + 词云入口(原「热词」根 tab 下段拆出,
 * 上段关注命中流独立成「关注」根 tab)。
 *
 * 内容复用 [TrendsContent](与「历史热词」日期页共享:caption + 榜单 + 页脚),
 * 展开区动作(「+ 关注」/「查看全部命中」)完整保留;词云经 caption 行进入。
 * 入口:今天页报头「热词」、关注 tab 空态引导、ainewshub://tab/hotwords 深链。
 *
 * 二级页语义:顶栏带返回、无下拉刷新、底部不预留浮动底栏高度;
 * [listState] 由 AiNewsHubApp 按 Page 值上提持有(pageListStates)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotwordsScreen(
    onBack: () -> Unit,
    onOpenUrl: (url: String, title: String, source: String) -> Unit,
    // 词云二级页入口(caption 行「词云 ›」)
    onOpenCloud: () -> Unit,
    // 展开区「查看全部命中」带词进本地搜索(查设备内索引的全部命中)
    onOpenLocalSearch: (String) -> Unit,
    listState: LazyListState,
    trendsVm: TrendsViewModel = viewModel()
) {
    val trendsState by trendsVm.state.collectAsStateWithLifecycle()
    // 已关注词集合(小写):热词行展开区「+ 关注」按钮的已关注态判定
    val followedKeywords by trendsVm.followedKeywords.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                // tab_hotwords 词条转作本页标题(tab 位已让给 tab_follows)
                title = stringResource(R.string.tab_hotwords),
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
            when (val s = trendsState) {
                is TrendsState.Loading -> RankRowSkeletonList(count = 5)
                is TrendsState.NoData -> SectionNotice(
                    title = stringResource(R.string.trends_no_data_title),
                    message = stringResource(R.string.trends_no_data_subtitle),
                    actionLabel = stringResource(R.string.common_retry),
                    onAction = { trendsVm.load() }
                )
                is TrendsState.Error -> SectionNotice(
                    title = stringResource(R.string.trends_load_failed),
                    message = s.message,
                    actionLabel = stringResource(R.string.common_retry),
                    onAction = { trendsVm.load() }
                )
                is TrendsState.Success -> TrendsContent(
                    digest = s.digest,
                    listState = listState,
                    onOpenUrl = onOpenUrl,
                    onOpenCloud = onOpenCloud,
                    onFollowKeyword = { trendsVm.followKeyword(it) },
                    onSearchTerm = onOpenLocalSearch,
                    followedKeywords = followedKeywords,
                    bottomReserve = false
                )
            }
        }
    }
}
