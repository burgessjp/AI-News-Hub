package com.example.aihot.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 更多/设置/关于等「次级列表页」的统一基础组件。
 *
 * 全 App 列表页(Featured/All/Daily)已统一为「扁平行 + hairline 分隔线」,
 * 次级页此前还在用浮动 [AppCard] 逐行描边,视觉割裂。本文件提供与主列表页
 * 同构的两组件,消除这一不一致,并取代此前在 Settings/About 各复制一份的
 * `SectionLabel`。
 *
 * 规格与主列表页完全一致:
 *  - 内容横向 padding:18dp
 *  - 发丝分隔线:0.5dp outlineVariant,左侧缩进对齐文字列(避开图标列)
 *  - 章节条:surfaceContainerHigh 背景 + cyan 4dp×16dp 左竖条 + labelLarge/Bold
 */

/**
 * 章节标题条 —— 与 [com.example.aihot.ui.DateGroupHeader] 同构。
 *
 * 视觉:surfaceContainerHigh 背景 + 左竖条(1.5dp×24dp,默认 primary)+ 加粗文字。
 *
 * @param accentColor 竖条与文字的强调色。默认 primary(浏览组);
 *        Hub 页偏好组传 secondary(紫),与浏览组的蓝形成双色分组对照。
 */
@Composable
fun SettingsGroupHeader(
    text: String,
    modifier: Modifier = Modifier,
    accentColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified
) {
    val cs = MaterialTheme.colorScheme
    // 默认 unspecified → 用 primary;调用方可显式传 secondary 等。
    val barColor = if (accentColor == androidx.compose.ui.graphics.Color.Unspecified) cs.primary else accentColor
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左竖条 —— 章节强调锚点。
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 16.dp)
                .background(barColor)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = barColor,
            fontWeight = FontWeight.Bold,
            // 章节条专用字距:5 处 labelLarge 有 3 种字距(0.5/1.0/默认),此值不进 Type.kt 以免误伤
            letterSpacing = 0.5.sp
        )
    }
}

/**
 * 扁平行 —— 带可选图标、副标题、尾部内容与 chevron,行间用 hairline 分隔。
 *
 * 去掉逐行 [AppCard] 描边,改为与 NewsRow 同样的「行 + 发丝线」连续列表观感。
 *
 * @param icon 左侧图标(可选)。tint 默认 onSurfaceVariant,克制不喧宾夺主。
 * @param title 标题(titleMedium/SemiBold)。
 * @param subtitle 副标题(bodySmall/onSurfaceVariant),可选。
 * @param showDivider 是否在行底绘制 hairline 分隔线。组内除最后一行外都应传 true。
 * @param trailing 行尾自定义内容(如 RadioButton、版本号),与默认 chevron 二选一。
 * @param showChevron 是否显示默认的右箭头(可点击暗示)。有 trailing 时应传 false。
 * @param onClick 行点击回调。
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    showDivider: Boolean = true,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    showChevron: Boolean = trailing == null,
    onClick: () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { base -> if (onClick !== NO_OP) base.clickable { onClick() } else base }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            } else if (showChevron) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = cs.outlineVariant
                )
            }
        }
        if (showDivider) {
            // 左侧缩进对齐文字列(18 padding + 24 icon + 14 gap),无图标时仅留标题缩进。
            val inset = if (icon != null) 56.dp else 18.dp
            HorizontalDivider(
                thickness = 0.5.dp,
                color = cs.outlineVariant,
                modifier = Modifier.padding(start = inset, end = 18.dp)
            )
        }
    }
}

/** 默认无操作,用于区分「行不可点击」(不挂 clickable,无 ripple)。 */
private val NO_OP: () -> Unit = {}

/**
 * 横向多段选择器的一个选项(图标在上、文字在下,竖排)。
 *
 * 供 [SegmentedOptionRow] 使用 —— 外观(系统/亮/暗)、字体族等需要「并列对比」
 * 的设置项:每个选项是一张小卡片,选中态用 primaryContainer 填充 + primary 描边强调。
 *
 * @param icon 顶部图标。
 * @param label 图标下方文字。
 */
data class SegmentedOption(
    val icon: ImageVector,
    val label: String
)

/**
 * 横向多段选择器 —— 把若干 [SegmentedOption] 等宽并排,选中项高亮。
 *
 * 与图片设计一致:
 *  - 容器:逐项等宽(`weight(1f)`),整体随列表内容左右留 18dp。
 *  - 单项:竖排(图标 24dp + 文字 labelMedium),rounded medium(18dp)。
 *  - 未选中:surfaceContainerLow 填充 + 1dp outlineVariant 描边。
 *  - 选中:  primaryContainer 填充 + 1dp primary 描边 + 图标/文字着 onPrimaryContainer/primary。
 *  - 点击有 ripple,无按压缩放(沿用全 App 「描边分层、无阴影」风格)。
 *
 * @param options 候选项(通常 3 个)。
 * @param selectedIndex 当前选中索引。
 * @param onSelect 点击某项的回调,参数为该项索引。
 */
@Composable
fun SegmentedOptionRow(
    options: List<SegmentedOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    require(options.isNotEmpty()) { "options 不能为空" }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            SegmentedItem(
                option = option,
                selected = selected,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(index) }
            )
        }
    }
}

@Composable
private fun SegmentedItem(
    option: SegmentedOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    // 选中态「克制高对比」:不堆大色块,改用 描边 + 字色 表达选中。
    //  - 底色:统一 surfaceContainerLow(与未选中同),保持界面干净;
    //  - 描边:选中 1.5dp primary(更醒目),未选中 1dp outlineVariant;
    //  - 字/图标色:选中 primary(cyan,与全 App 强调色一致),未选中 onSurfaceVariant。
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = cs.surfaceContainerLow,
        contentColor = if (selected) cs.primary else cs.onSurface,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) cs.primary else cs.outlineVariant
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = option.icon,
                contentDescription = null,
                tint = if (selected) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = option.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) cs.primary else cs.onSurfaceVariant
            )
        }
    }
}
