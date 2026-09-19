package com.peng.ainewshub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.ui.theme.AppText
import com.peng.ainewshub.ui.theme.TrackingSection

/**
 * 章节条 —— 全 App 统一的分节标题,取代三套不一致的私有实现
 * (NewsCard.DateGroupHeader / DailyScreen.SectionHeader / SettingsRow.SettingsGroupHeader)。
 *
 * 视觉:从「灰底横贯」改为「留白 + 小竖条强调」的编辑感,去 admin 灰底。
 *  - 透明底
 *  - 3dp 宽 × 12dp 高 accent 竖条(圆角走 shapes.small:3dp 宽条上半径被钳到 1.5dp,
 *    即胶囊端点,与 token 体系不冲突);[showAccent] = false 时隐藏(弹层等紧凑场景)
 *  - 8dp 间距 + 标题(labelLarge/Bold + [TrackingSection] 字距 + onSurface)
 *  - 可选 [trailing](weight 撑开后右对齐)
 *
 * padding:默认水平 18dp 跟随各列表既有内容缩进;垂直上 12dp / 下 6dp,组间留白、组内收紧。
 * 调用方内容层已有统一水平边距时(如 ModalBottomSheet 内)传 [contentPadding] 清零
 * 水平缩进,保证与同层内容左对齐。
 *
 * @param title 章节标题
 * @param accent 竖条强调色,默认 primary;分组对照场景可传 secondary/tertiary
 * @param showAccent 是否显示左竖条(默认 true);弹层紧凑场景传 false 只留标题
 * @param large 日报大节头模式:「今日重点 / 我的关注」等版面栏目标题 ——
 *        竖条 3×15dp、衬线 [AppText.sectionHead](17sp SemiBold)、上方留白加大;
 *        默认 false 维持紧凑小节样式(设置分组/弹层等)
 * @param contentPadding 章节条内边距,默认见上;已自带水平边距的场景可清零水平缩进
 * @param trailing 右侧可选内容
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    showAccent: Boolean = true,
    large: Boolean = false,
    contentPadding: PaddingValues? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null
) {
    val padding = contentPadding ?: if (large) {
        PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 6.dp)
    } else {
        PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 6.dp)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showAccent) {
            // 左竖条 —— 章节强调锚点(大节头 15dp 高,小节 12dp)
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = if (large) 15.dp else 12.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(accent)
            )
            Spacer(Modifier.width(8.dp))
        }
        if (large) {
            // 日报大节头:衬线 + sectionHead 档,分隔线收敛后大节层级靠它承担
            Text(
                text = title,
                style = AppText.sectionHead,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                letterSpacing = TrackingSection
            )
        }
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}
