package com.peng.ainewshub.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.UiState

/**
 * 源列表页的统一脚手架 —— 收口 7 个 items Screen 此前逐字复制的 Scaffold + when(state) 样板。
 *
 * 统一处理:
 *  - Scaffold(surface 底 + 可选 snackbarHost + AppTopBar 返回箭头 + 标题 + 可选 actions)
 *  - when(state):Loading→骨架 / Error→错误态+重试 / Success→空判→EmptyState/PullToRefreshBox
 *
 * 调用方提供:
 *  - [title]:顶栏标题
 *  - [onBack]:返回回调
 *  - [state]:当前 UiState
 *  - [isRefreshing] / [onForceRefresh]:下拉刷新
 *  - [snackbarHostState]:可选(接翻译的页传,不接翻译的页传 null)
 *  - [topBarActions]:顶栏右侧额外内容(如 stormzhang 的 pageDate);默认空
 *  - [listState]:列表滚动状态(MainActivity 按 Page 持有)
 *  - [successContent]:成功且非空时,PullToRefreshBox 内的列表内容(LazyListScope 扩展,
 *    通常先 [updateTimeHeader] 再 itemsIndexed + [rowDividerIfNeeded])
 *
 * @param T 列表元素类型
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SourceListScaffold(
    title: String,
    onBack: () -> Unit,
    state: UiState<List<T>>,
    isRefreshing: Boolean,
    onForceRefresh: () -> Unit,
    listState: LazyListState,
    snackbarHostState: SnackbarHostState? = null,
    topBarActions: @Composable RowScope.() -> Unit = {},
    successContent: LazyListScope.() -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = {
            snackbarHostState?.let { SnackbarHost(it) }
        },
        topBar = {
            AppTopBar(
                title = title,
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = topBarActions
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is UiState.Loading -> RankRowSkeletonList(count = 8)
                is UiState.Error -> ErrorState(
                    message = s.message,
                    onRetry = onForceRefresh
                )
                is UiState.Success -> {
                    val items = s.data
                    if (items.isEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.common_empty),
                            subtitle = stringResource(R.string.common_refresh_hint),
                            icon = Icons.Outlined.Inventory2,
                            actionLabel = stringResource(R.string.common_refresh_once),
                            onAction = onForceRefresh
                        )
                    } else {
                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = onForceRefresh,
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                successContent()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 列表顶部「数据更新时间」头 item —— 各源列表 LazyColumn 的第一个 item。
 * 收口此前 7 处 `item { ListUpdateTimeHeader(fetchedAtMillis) }` 复制。
 */
fun LazyListScope.updateTimeHeader(fetchedAtMillis: Long?) {
    item { ListUpdateTimeHeader(fetchedAtMillis) }
}

/**
 * 列表行间发丝线分隔(非最后一项时)—— 收口此前 7 处
 * `if (index != list.lastIndex) { HairlineDivider(startIndent = 60.dp) }` 复制。
 *
 * 在 itemsIndexed 的 item 渲染 lambda 末尾调用(同 item 内渲染行 + 分隔线)。
 */
@Composable
fun RowDividerIfNeeded(index: Int, listSize: Int) {
    if (index != listSize - 1) {
        HairlineDivider(startIndent = 60.dp)
    }
}
