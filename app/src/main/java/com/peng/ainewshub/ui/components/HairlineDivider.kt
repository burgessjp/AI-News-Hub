package com.peng.ainewshub.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 行间发丝线分隔(0.5dp outlineVariant)—— 收口各列表页逐字复制的 Spacer/HorizontalDivider 样板。
 *
 * 各列表页此前各自手写 `Spacer.fillMaxWidth().padding(...).height(0.5.dp)
 * .background(outlineVariant)` 或 `HorizontalDivider(thickness=0.5.dp,...)`,且左侧缩进
 * 魔数分散(54/60/72dp,各自对齐其图标列宽度)。统一为本组件 + 显式 [startIndent] 参数。
 *
 * @param startIndent 左侧缩进(对齐各行图标列右沿,避开图标);0 表示顶到屏幕边
 * @param endIndent 右侧缩进,默认 18dp(与各列表页既有值一致)
 */
@Composable
fun HairlineDivider(
    startIndent: Dp = 0.dp,
    endIndent: Dp = 18.dp,
    modifier: Modifier = Modifier
) {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = modifier.padding(start = startIndent, end = endIndent)
    )
}
