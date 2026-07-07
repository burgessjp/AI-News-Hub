package com.example.aihot.ui.tabs

import androidx.compose.runtime.Composable
import com.example.aihot.data.NewsItem
import com.example.aihot.ui.daily.DailyScreen

/**
 * 日报 tab —— 直接复用 DailyScreen(自带顶栏 + 历史归档入口)。
 *
 * DailyScreen 内部用 DailyViewModel.latest 驱动最新日报内容。
 */
@Composable
fun DailyTab(
    onItemClick: (NewsItem) -> Unit,
    onOpenArchive: () -> Unit,
    onOpenUrl: (String, String) -> Unit
) {
    DailyScreen(
        onItemClick = onItemClick,
        onOpenArchive = onOpenArchive,
        onOpenUrl = onOpenUrl
    )
}
