package com.peng.ainewshub.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.ui.theme.AppText

/**
 * 统计徽章 —— Hub 列表 meta 行的「图标 + 数值」紧凑横排。
 *
 * 取代 GitHubTrending / HuggingFacePapers 两屏各自的私有 CountBadge
 * (两份实现逐字相同,仅默认值微调)。图标 14dp + AppText.bodySmall,
 * 默认弱色 onSurfaceVariant;热度主指标等场景经 [tint]/[fontWeight] 强调。
 */
@Composable
fun StatBadge(
    icon: ImageVector,
    value: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontWeight: FontWeight = FontWeight.Normal
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = value,
            style = AppText.bodySmall,
            color = tint,
            fontWeight = fontWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
