package com.peng.ainewshub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PersonAddAlt
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText

/**
 * 根 tab 集合(总览 / 摘要 / 关注 / 趋势 / 更多,entries 顺序即底栏顺序)。
 *
 * 设计稿(参考 system_stream_editorial)用图标 FILL 区分选中态:
 *  - 选中:[selectedIcon] 实心(Filled)变体
 *  - 未选中:[icon] 描边(Outlined)变体
 *
 * 「总览」是默认首页:端侧 AI 对全部归档源榜单的当日综合分析(OverviewScreen)。
 * 「趋势」是流水线纯统计的跨源热词榜(TrendsScreen,读归档 trends.json)。
 * 「关注」是关键词订阅的当日命中流(FollowsScreen),原为趋势页顶栏图标进入的
 * 二级页(Page.Follows),现升为根 tab,沿用入口原「人形+加号」图标语义。
 * 「AIHot 精选」原为独立根 tab,现改为从「更多」页进入的二级页(Page.FeaturedHub),
 * 精选 tab 的 Whatshot 图标语义迁移到 MoreScreen 浏览组入口。
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
    Overview(
        R.string.tab_overview,
        Icons.Outlined.Insights,
        Icons.Filled.Insights
    ),
    Summary(
        R.string.tab_summary,
        Icons.Outlined.AutoAwesome,
        Icons.Filled.AutoAwesome
    ),
    Follows(
        R.string.tab_follows,
        Icons.Outlined.PersonAddAlt,
        Icons.Filled.PersonAddAlt
    ),
    Trends(
        R.string.tab_trends,
        Icons.AutoMirrored.Outlined.TrendingUp,
        Icons.AutoMirrored.Filled.TrendingUp
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
 *      + 手势导航栏 inset(约 24-48dp)。取 96dp 覆盖大多数设备的实际悬浮区域。
 */
val BottomBarReservedHeight = 96.dp

/**
 * 药丸自身高度(不含距底 margin 与导航栏 inset):
 * icon 22dp + 图标/文字间距 2dp + 文字行高 ~16dp
 * + 项内 vertical padding 4dp×2 + 容器 vertical padding 4dp×2。
 *
 * 用途:列表允许滚入药丸之下、但要把可视区收在药丸底缘时(总览页),容器底部
 * padding 用 navigationBarsPadding + 16dp(与 MainActivity 底栏定位一致),
 * 列表 contentPadding 用本值 + 间距让末项能停到药丸之上。
 */
val BottomBarPillHeight = 56.dp

/**
 * 选中项水洗底的统一最小宽 —— 各 tab 选中态椭圆严格等宽的保证。
 *
 * 32dp 横向内边距 + 两字中文标签(内容列约 24dp)的自然宽刚好在本值附近,
 * 但自适应宽度会随标签字符数/系统字号漂移(英文 Overview 更是长出一截);
 * 以 min 宽拉齐后任意 tab 选中椭圆完全一致,且更长标签只放宽不被裁剪。
 */
val SelectedTabMinWidth = 96.dp

/**
 * 浮动药丸底栏 —— 对齐 "Synthetic Intelligence News" 设计系统
 * (参考 system_stream_editorial 原型底栏)。
 *
 * 与全宽 [androidx.compose.material3.NavigationBar] 的区别:
 *  - 浮在内容上(由调用方在 Box 内对齐 BottomCenter,不再用 Scaffold bottomBar 槽)
 *  - 90% 宽 + max 400dp,圆角 50dp(完全药丸)
 *  - 近实底:surface-container × AppAlpha.bottomBarSurface(0.94——Compose 无真模糊,
 *    半透明叠滚动内容显脏,近实底遮透出)+ 3dp 浮起阴影(无边框,靠阴影分层)
 *  - 容器内边距:horizontal 16dp(tab 增至 5 个后从 24dp 收紧)/ vertical 4dp
 *    (紧凑化:原 12dp 偏高,药丸整体高度压缩约 1/3,只收内间距,图标/字号不动)
 *  - 选中项:onSurface 半透明水洗药丸(AppAlpha.selectedTabWash,浅色压深/深色
 *    提亮,与底栏底色反向保证对比) + secondary 文字/图标 +
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
    // 药丸 Surface:近实底(遮内容透出)+ 3dp 浮起阴影
    Surface(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .widthIn(max = 400.dp),
        shape = CircleShape,
        color = cs.surfaceContainer.copy(alpha = AppAlpha.bottomBarSurface),
        // 浮动层的合理浮起(卡片零阴影惯例的例外,仅悬浮底栏)
        shadowElevation = 3.dp
    ) {
        Row(
            // 容器内边距:横向 16dp(原 24dp,tab 增至 5 个后收紧防英文长词溢出),
            // 纵向 4dp(原 12dp,压缩药丸高度)
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
}

/**
 * 药丸内单项。
 *
 * 视觉:
 * 视觉:
 *  - 选中:onSurface 半透明水洗长椭圆(horizontal 32dp / vertical 4dp,
 *    AppAlpha.selectedTabWash,浅色压深/深色提亮,与底栏底色反向保证对比),
 *    图标用 Filled 实心变体(FILL 1),图标/文字着 secondary(深浅两套色板均为
 *    可辨紫,见 Color.kt 的 fixed-dim 设计;secondaryContainer 深色值过暗,
 *    不可用作内容色)
 *  - 未选中:透明底(horizontal 8dp / vertical 4dp),图标用 Outlined 描边变体(FILL 0),
 *    图标/文字着 on-surface-variant
 *  - 点击:无 ripple;状态靠水洗底 + 图标 FILL 表达
 *
 * @param tab 对应根 tab(取 label 与图标变体)
 * @param selected 是否选中态
 * @param modifier 外部布局约束
 * @param onClick 点击回调
 */
@Composable
private fun NavPillItem(
    tab: AppTab,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    // 选中:实心图标(FILL 1);未选中:描边图标(FILL 0)
    val icon = if (selected) tab.selectedIcon else tab.icon
    // 选中:secondary 着色到图标/文字(深浅色板均为可辨紫,无需模式判断;
    // secondaryContainer 深色值过暗不可用);未选中:on-surface-variant
    val tint = if (selected) cs.secondary else cs.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(CircleShape)
            // 选中:onSurface 半透明水洗底(浅色压深/深色提亮,与底栏底色反向);
            // 未选中:透明
            .background(
                if (selected) cs.onSurface.copy(alpha = AppAlpha.selectedTabWash)
                else Color.Transparent
            )
            // selectable(非 clickable):向读屏声明 Tab 角色与选中状态,
            // TalkBack 会播报「已选中」;视觉仍靠水洗底 + 图标 FILL 表达
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            // 选中:统一椭圆最小宽(各 tab 等宽,见 [SelectedTabMinWidth]);
            // 未选中:触控宽保底 48dp(8×2+22=38dp 不达标,补足)。
            // 注意 widthIn 必须排在 padding 之前 —— 约束的是含内边距的水洗底整体,
            // 排在后面会把内边距加在 min 之外导致椭圆过宽
            .widthIn(min = if (selected) SelectedTabMinWidth else 48.dp)
            // 选中态横向展开(32dp)未选中收紧(8dp);纵向统一 4dp(原 8dp,压缩药丸高度)。
            // 选中 32dp 把 wash 拉成横向长椭圆(20dp 时宽高比 ~1.3 趋圆);
            // 不要用 weight 等份代替自适应:等份钳宽会把选中项连 padding 一起钳小,
            // 导致图标文字被裁(已踩坑回退);超宽设备的竖排防线是文字 maxLines=1
            .padding(
                horizontal = if (selected) 32.dp else 8.dp,
                vertical = 4.dp
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
                fontWeight = FontWeight.SemiBold,
                // 大字体/窄屏设备预算受钳时禁止折行 —— 竖排堆叠比轻微截断更破相
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
