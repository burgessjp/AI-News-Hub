package com.example.aihot.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 统一顶栏 —— 全 App 标题字号/字重/分隔线一致。
 *
 * 精修点:
 *  - 标题统一 titleLarge(默认 24sp SemiBold),详情/Web 页通过 titleFontSize 调小
 *  - 底部 hairline 分隔线(outlineVariant),解决"顶栏飘"的问题
 *  - 容器色与屏幕背景一致(surface),纯扁平
 *
 * @param applyTopInset 是否消费状态栏 inset。嵌入到已有顶部 Tab/顶栏的容器
 *        (如 HomeScreen 的 TabRow)时传 false,避免状态栏空白堆叠。
 * @param titleFontSize 标题字号,默认沿用 titleLarge(24sp)。详情/Web 页传 20sp 更克制。
 * @param horizontalPadding 顶栏内容(标题/导航图标/操作)的左右边距。
 *        默认 4dp 沿用 MD3 TopAppBar 内置留白;需要与列表内容对齐时(如精选 tab
 *        下方卡片用 18dp 边距)传入对应值,使 Logo / 日期与卡片左右边对齐。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable (() -> Unit)? = null,
    applyTopInset: Boolean = true,
    titleFontSize: TextUnit = TextUnit.Unspecified,
    horizontalPadding: Dp = 4.dp,
    actions: @Composable RowScope.() -> Unit = {}
) {
    // MD3 TopAppBar 自带 4dp 水平留白(navigationIcon 起点 / actions 终点距屏幕边 4dp)。
    // 这里在此基础上补足到 horizontalPadding:内容最终距屏幕边 = horizontalPadding。
    // 默认 4dp 时 extra=0,完全保持原行为,不影响其它页面。
    val extra = (horizontalPadding - 4.dp).coerceAtLeast(0.dp)
    Column {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = titleFontSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = if (navigationIcon == null) extra else 0.dp)
                )
            },
            navigationIcon = {
                if (navigationIcon != null) {
                    Box(modifier = Modifier.padding(start = extra)) {
                        navigationIcon()
                    }
                }
            },
            actions = {
                // 用 Row 包一层以提供 RowScope 并统一施加 end 边距,使操作区
                // (如日期文字)与下方内容右边对齐。
                Row(
                    modifier = Modifier.padding(end = extra),
                    horizontalArrangement = Arrangement.End
                ) {
                    actions()
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            windowInsets = if (applyTopInset) TopAppBarDefaults.windowInsets else WindowInsets(0),
            modifier = modifier
        )
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}
