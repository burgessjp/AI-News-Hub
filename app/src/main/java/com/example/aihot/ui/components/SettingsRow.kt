package com.example.aihot.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aihot.ui.theme.AppAlpha

/**
 * 更多/设置/关于等「次级列表页」的统一基础组件。
 *
 * 全 App 列表页(Featured/All/Daily)已统一为「扁平行 + hairline 分隔线」,
 * 次级页此前还在用浮动 [AppCard] 逐行描边,视觉割裂。本文件提供与主列表页
 * 同构的扁平行组件,消除这一不一致。
 *
 * 规格与主列表页完全一致:
 *  - 内容横向 padding:18dp
 *  - 发丝分隔线:0.5dp outlineVariant,左侧缩进对齐文字列(避开图标列)
 *
 * 章节条已收口到 [SectionHeader](透明底 + 小竖条强调),本文件不再私有实现。
 */

/**
 * 扁平行 —— 带可选图标、副标题、尾部内容与 chevron,行间用 hairline 分隔。
 *
 * 去掉逐行 [AppCard] 描边,改为与 NewsRow 同样的「行 + 发丝线」连续列表观感。
 *
 * @param icon 左侧图标(可选)。tint 默认 onSurfaceVariant,克制不喧宾夺主。
 * @param iconAccent 非空时图标升级为 36dp 圆角色块(强调色 12% 底 + 强调色图标),
 *        与「更多」页 IconTileRow 同一语言;为 null 时保持裸图标。
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
    iconAccent: Color? = null,
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
            if (icon != null && iconAccent != null) {
                // 36dp 圆角图标块:强调色 12% 底 + 强调色图标(对齐 MoreScreen 的 IconTileRow)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(iconAccent.copy(alpha = AppAlpha.badgeOverlay)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
            } else if (icon != null) {
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
            // 左侧缩进对齐文字列:图标块 18+36+14=68;裸图标 18+24+14=56;无图标仅留标题缩进。
            val inset = if (icon == null) 18.dp else if (iconAccent != null) 68.dp else 56.dp
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
 * 横向多段选择器 —— 官方 MD3 [SegmentedButton](单选)。
 *
 * 遵循 MD3 规范,全部视觉由官方组件保证:
 *  - 外框 1dp outline + 全圆角(拐角 only,段间以描边分隔)
 *  - 选中段:secondaryContainer 填充 + onSecondaryContainer 文字 + 勾选图标
 *  - 未选中:透明底 + onSurface 文字
 *  - 外边距由调用方经 [modifier] 控制
 *
 * @param options 候选文字(通常 2-3 个)。
 * @param selectedIndex 当前选中索引。
 * @param onSelect 点击某项的回调,参数为该项索引。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedOptionRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    require(options.isNotEmpty()) { "options 不能为空" }
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            SegmentedButton(
                selected = selected,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = { SegmentedButtonDefaults.Icon(active = selected) },
                label = {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}
