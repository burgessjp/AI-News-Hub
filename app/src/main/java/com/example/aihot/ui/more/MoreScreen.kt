package com.example.aihot.ui.more

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aihot.R
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.BottomBarReservedHeight
import com.example.aihot.ui.components.SectionHeader
import com.example.aihot.ui.theme.AppAlpha
import com.example.aihot.ui.theme.AppText

/**
 * 更多/Hub tab —— 聚合次要入口,对齐 "Synthetic Intelligence News" 设计系统的
 * user_hub_profile 原型。
 *
 * 结构(自顶向下,简洁直入):
 *  - 信息源入口:点开 [SourcesScreen] 二级页(Hub 浏览区,7 个第三方源 + AIHot 精选,
 *    原内嵌在更多页的「浏览」组,现独立成页)
 *  - 历史组(tertiary 强调):历史摘要 / 浏览历史 —— 彩色图标块行
 *  - 偏好组(secondary 强调):AI 服务 / 设置 / 关于 —— 浅灰图标块行
 *
 * 「AIHot 精选」原为首页独立根 tab,现收进 [SourcesScreen] 作为末位二级页(复用 FeaturedTab,
 * UI 完全不变:今日热点 + 最新精选列表 + 「全部 ›」入口)。
 * 日报及其历史归档入口已移至「全部动态」页(精选 → 全部 → 日报,日报页内含历史归档按钮)。
 * 搜索入口亦在「全部动态」页顶栏(全部动态是搜索主场景)。
 */
@Composable
fun MoreScreen(
    onOpenSources: () -> Unit,
    onOpenBrowseHistory: () -> Unit,
    onOpenSummaryArchive: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAiService: () -> Unit,
    onOpenAbout: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            // 与总览页一致:品牌 wordmark 图片标题(矢量,深/浅主题自适应)
            AppTopBar(
                title = "AI News Hub",
                titleContent = {
                    Image(
                        painter = painterResource(R.drawable.ic_wordmark),
                        contentDescription = "AI News Hub",
                        modifier = Modifier.height(36.dp)
                    )
                },
                horizontalPadding = 18.dp
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            // 底部预留浮动药丸底栏高度(MoreTab 是根 tab,底栏悬浮)
            contentPadding = PaddingValues(bottom = BottomBarReservedHeight)
        ) {
            // 信息源入口 —— 进入 Hub 浏览区独立页(SourcesScreen):7 个第三方源 + AIHot 精选。
            // 用 primary 强调色块(聚合入口非单一源,不挂品牌色),与下方「历史」组同档。
            item {
                IconTileRow(
                    icon = Icons.Filled.Hub,
                    iconColor = IconAccent.Primary,
                    title = "信息源",
                    subtitle = "7+1 个 AI 资讯源聚合",
                    showDivider = false,
                    onClick = onOpenSources
                )
            }

            // 历史组(tertiary 强调)—— 历史摘要(按日期看各源归档摘要)/ 浏览历史
            item { SectionHeader("历史", accent = MaterialTheme.colorScheme.tertiary) }
            item {
                IconTileRow(
                    icon = Icons.Filled.CalendarMonth,
                    iconColor = IconAccent.Primary,
                    title = "历史摘要",
                    subtitle = "按日期查看各源当日 AI 摘要",
                    onClick = onOpenSummaryArchive
                )
            }
            item {
                IconTileRow(
                    icon = Icons.Filled.History,
                    iconColor = IconAccent.Primary,
                    title = "浏览历史",
                    subtitle = "打开过的网页",
                    showDivider = false,
                    onClick = onOpenBrowseHistory
                )
            }

            // 偏好组(secondary 强调)—— 图标块用浅灰底 + 中性图标,
            // 与浏览组的彩色图标块拉开层次:内容入口彩色、设置项低调
            item { SectionHeader("偏好", accent = MaterialTheme.colorScheme.secondary) }
            item {
                IconTileRow(
                    icon = Icons.Filled.SmartToy,
                    iconColor = IconAccent.Neutral,
                    title = "AI 服务",
                    subtitle = "服务商、模型、翻译、用量",
                    onClick = onOpenAiService
                )
            }
            item {
                IconTileRow(
                    icon = Icons.Filled.Settings,
                    iconColor = IconAccent.Neutral,
                    title = "设置",
                    subtitle = "主题、显示偏好",
                    onClick = onOpenSettings
                )
            }
            item {
                IconTileRow(
                    icon = Icons.Filled.Info,
                    iconColor = IconAccent.Neutral,
                    title = "关于",
                    subtitle = "版本、数据源、开源依赖",
                    showDivider = false,
                    onClick = onOpenAbout
                )
            }
        }
    }
}

/** 图标块强调色档位 —— 历史/偏好组 IconTileRow 的图标底色与着色
 *  (浏览组 5 源已改固定品牌色,见 [SourceBrand])。
 *  - Primary:历史组的内容入口(浏览历史) / 更多页信息源聚合入口
 *  - Neutral:偏好组的设置/关于,用浅灰底 + 中性图标,与浏览组的品牌色块拉开层次
 */
internal enum class IconAccent { Primary, Neutral }

/**
 * 彩色图标块菜单行 —— 对齐 user_hub_profile 原型的 Browse/Preferences 项。
 *
 * 视觉:
 *  - 左侧 48dp 圆角块(rounded-xl):[brand] 非空时为品牌色实底 + 对比色图标(浏览组 5 源);
 *    否则底色 = 强调色低透明底,图标 = 强调色(历史/偏好组,见 [iconColor])
 *  - 标题(titleMedium/SemiBold)+ 副标题(caption/onSurfaceVariant)
 *  - 右侧 chevron
 *  - 行间 hairline 发丝线(左侧缩进对齐图标块右侧)
 */
@Composable
internal fun IconTileRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    iconColor: IconAccent = IconAccent.Neutral,
    brand: SourceBrandColors? = null,
    showDivider: Boolean = true,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val tileBg: Color
    val tileFg: Color
    if (brand != null) {
        // 品牌色块:实底品牌色 + 对比图标色,不走透明度档(品牌识别需饱和实色)
        tileBg = brand.container
        tileFg = brand.icon
    } else {
        // 每档给出 (底色, 图标色, 底色 alpha)。Neutral 用更高的 alpha(0.20)
        // 让浅灰块明显可见——彩色强调色饱和度高 0.12 即够,中性灰需要更实才不显寡淡。
        val (bg, fg, bgAlpha) = when (iconColor) {
            IconAccent.Primary -> Triple(cs.primary, cs.primary, AppAlpha.badgeOverlay)
            IconAccent.Neutral -> Triple(cs.outline, cs.onSurfaceVariant, AppAlpha.neutralOverlay)
        }
        tileBg = bg.copy(alpha = bgAlpha)
        tileFg = fg
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = androidx.compose.material3.ripple(),
                    onClick = onClick
                )
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 48dp 图标块(brand 档为品牌色实底,IconAccent 档为低透明强调色/浅灰)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(tileBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tileFg,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = AppText.caption,
                    color = cs.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = cs.outlineVariant
            )
        }
        if (showDivider) {
            // 左缩进对齐图标块右侧(18 + 48 + 16 gap)
            androidx.compose.material3.HorizontalDivider(
                thickness = 0.5.dp,
                color = cs.outlineVariant,
                modifier = Modifier.padding(start = 82.dp, end = 18.dp)
            )
        }
    }
}
