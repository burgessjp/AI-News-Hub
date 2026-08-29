package com.peng.ainewshub.ui.overview

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.NewsCardSkeletonList

/**
 * 历史总览 —— 指定日期的总览页。
 *
 * 内容渲染与总览 Tab 完全同构(共享 [OverviewContent]:digest Hero + Top10 平铺
 * + 页脚),差异仅在:
 *  - 数据按日期经 overview_history 索引寻址([OverviewArchiveViewModel.loadDigest]);
 *  - 二级页语义:顶栏带返回、无下拉刷新、底部不预留浮动底栏高度。
 *
 * [listState] 由 AiNewsHubApp 按 Page 值上提持有(pageListStates),进 Web 页
 * 返回后保持滚动位置;VM 按日期隔离实例(`viewModel(key = "overview-date-$date")`)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewDateScreen(
    date: String,
    onBack: () -> Unit,
    onOpenUrl: (url: String, title: String, source: String) -> Unit,
    listState: LazyListState,
    vm: OverviewArchiveViewModel = viewModel(key = "overview-date-$date")
) {
    val state by vm.digest.collectAsStateWithLifecycle()

    LaunchedEffect(date) { vm.loadDigest(date) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.overview_date_title, date),
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
            when (val s = state) {
                is UiState.Loading -> NewsCardSkeletonList(count = 6)
                is UiState.Error -> ErrorState(
                    message = s.message,
                    title = stringResource(R.string.overview_archive_load_failed),
                    onRetry = { vm.retryDigest(date) }
                )
                is UiState.Success -> OverviewContent(
                    digest = s.data,
                    listState = listState,
                    onOpenUrl = onOpenUrl,
                    bottomReserve = false
                )
            }
        }
    }
}
