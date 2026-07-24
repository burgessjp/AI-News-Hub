package com.peng.ainewshub.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 历史日报入口 —— 全 App 各 tab 顶栏共用的右上角图标按钮。
 *
 * 统一用 CalendarMonth(日历)图标 + "历史日报" contentDescription,
 * 各 tab 顶栏 actions 内直接调用即可,避免每处重复声明图标。
 */
@Composable
fun ArchiveIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.CalendarMonth,
            contentDescription = "历史日报"
        )
    }
}
