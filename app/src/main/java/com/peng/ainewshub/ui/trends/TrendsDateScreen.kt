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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
 * 历史热词 —— 指定日期的热词榜页。
 *
 * 内容渲染与趋势 Tab 完全同构(共享 [TrendsContent]:caption + 热词榜平铺 +
 * 页脚,榜单带当日的 rankChange / isNewEntry 标记),差异仅在:
 *  - 数据按日期经 trends_history 索引寻址([TrendsArchiveViewModel.loadDigest]);
 *  - 二级页语义:顶栏带返回、无下拉刷新、底部不预留浮动底栏高度。
 *
 * [listState] 由 AiNewsHubApp 按 Page 值上提持有(pageListStates),进 Web 页
 * 返回后保持滚动位置;VM 按日期隔离实例(`viewModel(key = "trends-date-$date")`)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsDateScreen(
    date: String,
    onBack: () -> Unit,
    onOpenUrl: (url: String, title: String, source: String) -> Unit,
    listState: LazyListState,
    vm: TrendsArchiveViewModel = viewModel(key = "trends-date-$date")
) {
    val state by vm.digest.collectAsStateWithLifecycle()

    LaunchedEffect(date) { vm.loadDigest(date) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.trends_date_title, date),
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
                    title = stringResource(R.string.trends_archive_load_failed),
                    onRetry = { vm.retryDigest(date) }
                )
                is UiState.Success -> TrendsContent(
                    digest = s.data,
                    listState = listState,
                    onOpenUrl = onOpenUrl,
                    bottomReserve = false
                )
            }
        }
    }
}
