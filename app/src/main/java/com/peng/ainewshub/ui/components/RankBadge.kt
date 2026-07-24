package com.peng.ainewshub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.ui.theme.AppText

/**
 * 排名徽章 —— 全 App 统一的「左 24dp 序号块」。
 *
 * 取代各屏私有实现(此前 1-3 名实心 primary、其余灰底,尺寸在 20dp/24dp 间漂移):
 * 今日热点 / HackerNews / GitHub Trending / LinuxDo / stormzhang / HuggingFace 共用。
 *
 * 规格:24×24dp,圆角走 MaterialTheme.shapes.extraSmall;数字 AppText.bodySmall 加粗居中。
 * (数字等宽 tnum 特性项目内无先例,保持简单不引入。)
 *
 * 配色全走 colorScheme,借 tertiary 的「热度」语义分档:
 *  - 第 1 名:tertiary 底 + onTertiary 字(最热,唯一实心强强调)
 *  - 第 2-3 名:tertiaryContainer 底 + onTertiaryContainer 字
 *  - 其余:surfaceContainerHigh 底 + onSurfaceVariant 字(低对比)
 */
@Composable
fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (container, content) = when {
        rank == 1 -> cs.tertiary to cs.onTertiary
        rank in 2..3 -> cs.tertiaryContainer to cs.onTertiaryContainer
        else -> cs.surfaceContainerHigh to cs.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = rank.toString(),
            style = AppText.bodySmall,
            fontWeight = FontWeight.Bold,
            color = content
        )
    }
}
