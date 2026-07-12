package com.example.aihot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aihot.R
import com.example.aihot.ui.theme.AppText

/**
 * 4 个根 tab。
 *
 * 设计稿(参考 system_stream_editorial)用图标 FILL 区分选中态:
 *  - 选中:[selectedIcon] 实心(Filled)变体
 *  - 未选中:[icon] 描边(Outlined)变体
 *
 * @param labelRes 显示文案的 string resource
 * @param icon 未选中时的描边图标(FILL 0)
 * @param selectedIcon 选中时的实心图标(FILL 1)
 */
enum class AppTab(
    val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector
) {
    Featured(
        R.string.tab_featured,
        Icons.Outlined.Whatshot,
        Icons.Filled.Whatshot
    ),
    All(
        R.string.tab_all,
        Icons.AutoMirrored.Outlined.FormatListBulleted,
        Icons.AutoMirrored.Filled.FormatListBulleted
    ),
    Daily(
        R.string.tab_daily,
        Icons.AutoMirrored.Outlined.MenuBook,
        Icons.AutoMirrored.Filled.MenuBook
    ),
    More(
        R.string.tab_more,
        Icons.Outlined.GridView,
        Icons.Filled.GridView
    )
}

/**
 * 浮动药丸底栏占位高度 —— 列表/滚动容器底部 contentPadding 应预留此值,
 * 避免末项被悬浮底栏遮挡。
 *
 * 组成:药丸自身约 56dp + 距底 16dp margin + 16dp 呼吸空间
 *      + 手势导航栏 inset(约 24-48dp)。取 120dp 覆盖大多数设备的实际悬浮区域。
 */
val BottomBarReservedHeight = 120.dp

/**
 * 浮动药丸底栏 —— 对齐 "Synthetic Intelligence News" 设计系统
 * (参考 system_stream_editorial 原型底栏)。
 *
 * 与全宽 [androidx.compose.material3.NavigationBar] 的区别:
 *  - 浮在内容上(由调用方在 Box 内对齐 BottomCenter,不再用 Scaffold bottomBar 槽)
 *  - 90% 宽 + max 400dp,圆角 50dp(完全药丸)
 *  - 玻璃质感:surface-container 70% 透明 + 1px 白色半透明描边(玻璃边缘高光)
 *  - 容器内边距:horizontal 24dp / vertical 12dp(对齐设计稿 px-6 py-3)
 *  - 选中项:secondary-container 实心填充药丸 + on-secondary-container 文字/图标 +
 *    实心图标(FILL 1);未选中:透明 + on-surface-variant + 描边图标(FILL 0)
 *
 * 调用方负责:(1) 在 Box 内用 Modifier.align(BottomCenter) 定位;(2) 给内容区补底部
 * padding 避免列表末项被遮挡(见 [BottomBarReservedHeight])。
 *
 * @param current 当前选中 tab
 * @param onSelect 切 tab 回调
 */
@Composable
fun AppBottomBar(
    current: AppTab,
    onSelect: (AppTab) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .widthIn(max = 400.dp)
            // 药丸:完全圆角 + 玻璃质感底 + 白色半透明描边(模拟玻璃边缘高光)
            .clip(RoundedCornerShape(50))
            .background(cs.surfaceContainer.copy(alpha = 0.7f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.3f),
                shape = RoundedCornerShape(50)
            )
            // 容器内边距对齐设计稿 px-6 py-3
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppTab.entries.forEach { tab ->
            NavPillItem(
                tab = tab,
                selected = tab == current,
                onClick = { onSelect(tab) }
            )
        }
    }
}

/**
 * 药丸内单项 —— 严格对齐设计稿。
 *
 * 视觉:
 *  - 选中:secondary-container 实心填充的圆角药丸(px-5 py-2 ≈ 20dp/8dp),
 *    图标用 Filled 实心变体(FILL 1),图标/文字着 on-secondary-container
 *  - 未选中:透明底(p-2 ≈ 8dp),图标用 Outlined 描边变体(FILL 0),
 *    图标/文字着 on-surface-variant
 *  - 点击:无 ripple;状态靠填充色 + 图标 FILL 表达
 */
@Composable
private fun NavPillItem(
    tab: AppTab,
    selected: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    // 选中:实心图标(FILL 1);未选中:描边图标(FILL 0)
    val icon = if (selected) tab.selectedIcon else tab.icon
    // 选中:on-secondary-container;未选中:on-surface-variant
    val tint = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            // 选中:secondary-container 实心填充;未选中:透明
            .background(if (selected) cs.secondaryContainer else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            // 选中态 px-5 py-2(20dp/8dp);未选中 p-2(8dp)
            .padding(
                horizontal = if (selected) 20.dp else 8.dp,
                vertical = if (selected) 8.dp else 8.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = stringResource(tab.labelRes),
                style = AppText.caption,
                color = tint,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
