package com.example.aihot.ui.tabs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aihot.data.Mode
import com.example.aihot.data.NewsItem
import com.example.aihot.ui.ItemsViewModel
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.items.ItemsScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 精选 tab —— 顶栏 [logo + 最新精选] 行末尾显示实时日期(如「7月7日 · 周二」)。
 *
 * ViewModel 用 `key="featured"` 取独立实例,与「全部」「搜索」互不串扰。
 */
@Composable
fun FeaturedTab(
    onItemClick: (NewsItem) -> Unit
) {
    val vm: ItemsViewModel = viewModel(key = "featured")
    // 进入精选 tab 时强制 mode=SELECTED(防止从其它 tab 切来时 mode 残留)
    LaunchedEffect(Unit) { vm.setMode(Mode.SELECTED) }

    // 实时日期「月日 · 周几」(中文区域格式)
    val dateText = remember {
        SimpleDateFormat("M月d日 · E", Locale.CHINA).format(Date())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "最新精选",
                navigationIcon = {
                    Icon(
                        imageVector = Icons.Filled.Whatshot,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                },
                actions = {
                    Text(
                        text = dateText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
    ) { padding ->
        ItemsScreen(
            onItemClick = onItemClick,
            vm = vm,
            modifier = Modifier.fillMaxSize().padding(padding)
        )
    }
}
