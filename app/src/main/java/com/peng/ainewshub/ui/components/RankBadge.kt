package com.peng.ainewshub.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 排名数字 —— 全 App 统一的「左 24dp 序号槽」,纸墨日报的裸数字。
 *
 * 报纸语言:去底衬去描边,大号数字(字体随全局纸墨宋体);1-2 名报纸红(primary)
 * 强调,其余纸灰(onSurfaceVariant)。今日重点 Top10 / 热词榜 / 各源列表共用;
 * 小组件有独立的迷你版本。
 */
@Composable
fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val color = if (rank in 1..2) cs.primary else cs.onSurfaceVariant
    Box(
        modifier = modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = rank.toString(),
            fontSize = 18.sp,
            lineHeight = 22.sp,
            color = color
        )
    }
}
