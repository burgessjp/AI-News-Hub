package com.example.aihot.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.aihot.R

/**
 * 4 个根 tab。
 *
 * @param labelRes 显示文案的 string resource
 * @param icon Material 图标(已引入 icons-extended)
 */
enum class AppTab(val labelRes: Int, val icon: ImageVector) {
    Featured(R.string.tab_featured, Icons.Filled.Whatshot),
    All(R.string.tab_all, Icons.AutoMirrored.Filled.FormatListBulleted),
    Daily(R.string.tab_daily, Icons.AutoMirrored.Filled.MenuBook),
    More(R.string.tab_more, Icons.Filled.GridView)
}

/**
 * 底部导航栏 —— Material3 NavigationBar。
 *
 * 配色:
 *  - 容器 surface(与屏幕背景一致,纯扁平)
 *  - 选中 primary(Dark 下是亮青 #4ADADA,对比强烈)
 *  - 未选中 onSurfaceVariant
 *  - 默认带顶部 hairline 分隔(由 NavigationBar 自带 elevation 提供)
 */
@Composable
fun AppBottomBar(
    current: AppTab,
    onSelect: (AppTab) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        AppTab.entries.forEach { tab ->
            val selected = tab == current
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            )
        }
    }
}
