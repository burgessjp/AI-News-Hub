package com.peng.ainewshub.ui.tabs

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.data.model.Mode
import com.peng.ainewshub.data.model.NewsItem
import com.peng.ainewshub.ui.ItemsViewModel
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.items.ItemsScreen

/**
 * 全部动态页 —— 原独立 tab,现改为从精选页「全部 ›」入口 push 进入的二级页。
 *
 * 顶栏:左返回箭头 + 标题「全部动态」+ 右上角「日报」入口与「搜索」按钮。
 * - [onOpenDaily]:跳转到 AI 日报页(二级页,不再是独立 tab)。
 * - [onOpenSearch]:跳转到搜索页。
 * ViewModel 用 `key="all"` 取独立实例,与精选列表互不串扰。
 */
@Composable
fun AllTab(
    onItemClick: (NewsItem) -> Unit,
    onBack: () -> Unit,
    onOpenDaily: () -> Unit,
    onOpenSearch: () -> Unit,
    // 滚动状态由 MainActivity 按 Page 持有:push 更深页返回后保持位置
    listState: LazyListState
) {
    val vm: ItemsViewModel = viewModel(key = "all")
    LaunchedEffect(Unit) { vm.setMode(Mode.ALL) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.all_tab_title),
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    // 「日报」入口 —— 原独立 tab,现收纳于此
                    IconButton(onClick = onOpenDaily) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                            contentDescription = stringResource(R.string.daily_title)
                        )
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(R.string.action_search)
                        )
                    }
                }
            )
        }
    ) { padding ->
        ItemsScreen(
            onItemClick = onItemClick,
            vm = vm,
            listState = listState,
            // 二级页:浮动底栏已隐藏,不再预留其高度
            reserveBottomBarSpace = false,
            modifier = Modifier.fillMaxSize().padding(padding)
        )
    }
}
