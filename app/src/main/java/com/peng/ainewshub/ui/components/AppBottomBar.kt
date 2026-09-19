package com.peng.ainewshub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.theme.AppText

/**
 * 根 tab 集合(今天 / 热词 / 更多,entries 顺序即底栏顺序)。
 *
 * 「今天」是默认首页:一份垂直日报 —— 综述 Hero + Top10 + 分源摘要区块
 * (TodayScreen,合并原 总览/摘要 两个 tab,读完重点顺着读完分源)。
 * 「热词」合并原 关注/趋势:「我的关注」命中流在上,跨源热词榜 + 词云在下
 * (HotwordsScreen)。「更多」维持信息源/历史/收藏/设置等 hub 不变。
 * 原四个内容 tab(总览/摘要/关注/趋势)的内容页全部保留为二级页或页内区块;
 * 旧 tab 深链名(overview/summary/follows/trends)在 MainActivity.tabOf 永久映射。
 *
 * @param labelRes 显示文案的 string resource
 */
enum class AppTab(
    val labelRes: Int
) {
    Today(R.string.tab_today),
    Hotwords(R.string.tab_hotwords),
    More(R.string.tab_more)
}

/**
 * 页脚条占位高度 —— 列表/滚动容器底部 contentPadding 应预留此值,
 * 避免末项被悬浮底栏遮挡。
 *
 * 组成:发丝线 0.5dp + 项触控行 61.5dp(铅字块自身约 32dp,居中)。
 */
val BottomBarPillHeight = 62.dp

/**
 * 旧悬浮药丸时代的总预留高度(页脚条高 + 距底 margin + 呼吸 + 手势导航栏 inset)。
 * 保留供个别整体预留场景使用,常规列表用 [BottomBarPillHeight] + 16dp 呼吸空间。
 */
val BottomBarReservedHeight = 102.dp

/**
 * 根 tab 底栏 —— 纸墨日报「报纸页脚条」:全宽实底 + 顶部发丝线,不用悬浮药丸。
 *
 * 选中态 = 铅字块(inverseSurface 直角实底 + inverseOnSurface 反白字),
 * 像一枚铅字/印章盖在页脚,存在感靠墨块不靠线也不靠彩色(报纸红留给内容层;
 * 发丝线只承担悬浮 overlay 的滚动分界职能,不做装饰);未选中 = onSurfaceVariant
 * 裸文字。挂载方式不变:由 AiNewsHubApp 以 overlay
 * 对齐 BottomCenter 悬浮(内容可滚入其下,各列表 contentPadding 预留
 * [BottomBarPillHeight] + 呼吸空间),自身补 navigationBarsPadding。
 */
@Composable
fun AppBottomBar(
    current: AppTab,
    onSelect: (AppTab) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    // 报纸页脚条:全宽实底 + 顶部发丝线(悬浮 overlay 的滚动分界,非装饰)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surface)
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = cs.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(61.5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppTab.entries.forEach { tab ->
                NavFooterItem(
                    tab = tab,
                    selected = tab == current,
                    onClick = { onSelect(tab) }
                )
            }
        }
    }
}

/**
 * 页脚条单项 —— 铅字块式:纯文字,无图标。
 *
 *  - 选中:inverseSurface 直角实底块 + inverseOnSurface 反白 SemiBold 字
 *  - 未选中:onSurfaceVariant 裸文字,无任何修饰
 *  - 触控高 48dp 保底(外层 Box 撑足命中区,铅字块自身约 32dp 居中);
 *    重击当前 tab 给一次轻触感
 */
@Composable
private fun NavFooterItem(
    tab: AppTab,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            // selectable(非 clickable):向读屏声明 Tab 角色与选中状态
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = {
                    // 重击当前 tab(回根/刷新时刻)给一次轻触感;普通切 tab 不震
                    if (selected) haptics.tick()
                    onClick()
                }
            )
            // 触控高保底 48dp(铅字块约 32dp,靠本值撑足命中区)
            .heightIn(min = 48.dp)
    ) {
        Text(
            text = stringResource(tab.labelRes),
            style = AppText.caption,
            color = if (selected) cs.inverseOnSurface else cs.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                // 铅字块:background 在 padding 前 = padding 计入块内(块内边距)
                .then(
                    if (selected) Modifier.background(cs.inverseSurface) else Modifier
                )
                .padding(horizontal = 20.dp, vertical = 8.dp)
        )
    }
}
