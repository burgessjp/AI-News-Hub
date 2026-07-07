package com.example.aihot.ui.tabs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.example.aihot.ui.items.ItemsScreen

/**
 * 全部 tab —— 顶栏 + ItemsScreen(mode=ALL)。
 *
 * ViewModel 用 `key="all"` 取独立实例。
 */
@Composable
fun AllTab(onItemClick: (NewsItem) -> Unit) {
    val vm: ItemsViewModel = viewModel(key = "all")
    LaunchedEffect(Unit) { vm.setMode(Mode.ALL) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { AppTopBar(title = "全部动态") }
    ) { padding ->
        ItemsScreen(
            onItemClick = onItemClick,
            vm = vm,
            modifier = Modifier.fillMaxSize().padding(padding)
        )
    }
}
