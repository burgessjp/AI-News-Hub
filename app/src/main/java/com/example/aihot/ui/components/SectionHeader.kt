package com.example.aihot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aihot.ui.theme.TrackingSection

/**
 * 章节条 —— 全 App 统一的分节标题,取代三套不一致的私有实现
 * (NewsCard.DateGroupHeader / DailyScreen.SectionHeader / SettingsRow.SettingsGroupHeader)。
 *
 * 视觉:从「灰底横贯」改为「留白 + 小竖条强调」的编辑感,去 admin 灰底。
 *  - 透明底
 *  - 3dp 宽 × 12dp 高 accent 竖条(圆角走 shapes.small:3dp 宽条上半径被钳到 1.5dp,
 *    即胶囊端点,与 token 体系不冲突)
 *  - 8dp 间距 + 标题(labelLarge/Bold + [TrackingSection] 字距 + onSurface)
 *  - 可选 [trailing](weight 撑开后右对齐)
 *
 * padding:水平 18dp 跟随各列表既有内容缩进;垂直上 12dp / 下 6dp,组间留白、组内收紧。
 *
 * @param title 章节标题
 * @param accent 竖条强调色,默认 primary;分组对照场景可传 secondary/tertiary
 * @param trailing 右侧可选内容
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    trailing: @Composable (RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左竖条 —— 章节强调锚点
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 12.dp)
                .clip(MaterialTheme.shapes.small)
                .background(accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            letterSpacing = TrackingSection
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}
