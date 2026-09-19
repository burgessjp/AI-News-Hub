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
 * 统计徽章 —— Hub 列表 meta 行的「单字前缀 + 数值」紧凑横排(报纸数据栏语言,
 * 全 App 去图标改版:图标 → 单字,如「星 24k」「赞 1.2k」「评 340」)。
 *
 * 取代 GitHubTrending / HuggingFacePapers 两屏各自的私有 CountBadge。
 * 默认弱色 onSurfaceVariant;热度主指标等场景经 [tint]/[fontWeight] 强调。
 */
@Composable
fun StatBadge(
    value: String,
    label: String = "",
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontWeight: FontWeight = FontWeight.Normal
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 前缀二选一:icon(GitHub star/fork 等产品符号,用户有图标肌肉记忆)
        // 或 label 单字(通用数据栏);都为空则纯数值
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(3.dp))
        } else if (label.isNotEmpty()) {
            Text(
                text = label,
                style = AppText.bodySmall,
                color = tint,
                maxLines = 1
            )
            Spacer(Modifier.width(3.dp))
        }
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
