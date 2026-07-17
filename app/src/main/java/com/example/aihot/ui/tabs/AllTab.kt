package com.example.aihot.ui.tabs

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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aihot.data.Mode
import com.example.aihot.data.NewsItem
import com.example.aihot.ui.ItemsViewModel
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.AppTopBarDefaults
import com.example.aihot.ui.items.ItemsScreen

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
    onOpenSearch: () -> Unit
) {
    val vm: ItemsViewModel = viewModel(key = "all")
    LaunchedEffect(Unit) { vm.setMode(Mode.ALL) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "全部动态",
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    // 「日报」入口 —— 原独立 tab,现收纳于此
                    IconButton(onClick = onOpenDaily) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                            contentDescription = "AI 日报"
                        )
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "搜索"
                        )
                    }
                }
            )
        }
    ) { padding ->
        ItemsScreen(
            onItemClick = onItemClick,
            vm = vm,
            // 二级页:浮动底栏已隐藏,不再预留其高度
            reserveBottomBarSpace = false,
            modifier = Modifier.fillMaxSize().padding(padding)
        )
    }
}
