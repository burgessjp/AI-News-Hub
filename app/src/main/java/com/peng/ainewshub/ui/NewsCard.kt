package com.peng.ainewshub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.data.NewsItem
import com.peng.ainewshub.ui.components.SectionHeader
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 新闻列表行 —— 扁平无卡片风格(参考新设计)。
 *
 * 布局(一行):
 *  - 左栏(固定窄宽):绝对时间 HH:mm(年月日由分组条承担,这里只显示时分),
 *    Medium 字重 + onSurface 85%,与标题首行基线对齐
 *  - 右栏(权重 1):
 *      标题行:标题(2 行,AppText.titleItem 16sp SemiBold) … 热度徽章(右对齐,火焰图标 + 分档配色)
 *      摘要(2 行,onSurfaceVariant)
 *      底部行:精选标记 + 来源 · 分类(onSurfaceVariant 降层级)
 *
 *  - 左右 18dp / 上下 12dp 留白,行间无卡片描边,依靠列表分隔线区分
 */
@Composable
fun NewsCard(
    item: NewsItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(),
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 左栏:时分 HH:mm(年月日已由日期分组条承担)。
        // alignByBaseline 与右栏首行(标题)基线对齐,取代旧 top padding 硬调
        val time = absoluteTime(item.publishedAt)
        if (time.isNotEmpty()) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurface.copy(alpha = AppAlpha.primaryEmphasis),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.alignByBaseline()
            )
        }

        // 右栏:主内容(基线取自首个有基线的子级 = 标题首行)
        Column(modifier = Modifier
            .weight(1f)
            .alignByBaseline()) {
            // 标题行:标题(权重 1) + 热度分数(右对齐,自然宽度)
            //  - 分数提到标题行右侧,与标题首行对齐,避免与底部来源信息混在一起
            //  - 带火焰图标 + 分值,明示这是"热度",而非无意义的数字
            if (item.title.isNotBlank()) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = item.title,
                        // titleItem 档位本身即 SemiBold,不再显式覆盖字重
                        style = AppText.titleItem,
                        color = cs.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (item.score > 0) {
                        HotBadge(score = item.score)
                    }
                }
            }

            // 摘要
            if (!item.summary.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.summary,
                    style = AppText.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 底部 meta 行:精选标记 + 来源 · 分类
            //  (分数已上移到标题行右侧)
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (item.selected) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier.size(12.dp)
                    )
                }
                if (item.source.isNotBlank()) {
                    Text(
                        text = item.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                if (!item.category.isNullOrBlank()) {
                    Text(
                        text = "· ${item.categoryLabel()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 列表项热度徽章 —— 火焰图标 + 分值,明示语义是「热度」,避免无单位裸数字。
 *
 * 分档配色全走 colorScheme(保留原 红/橙/紫/灰 四档语义):
 *  - ≥80:tertiary(最热)
 *  - ≥60:secondary
 *  - ≥40:primary
 *  - 其余:收进 surfaceContainerHigh 浅底 chip + onSurfaceVariant,压低低分视觉权重
 */
@Composable
private fun HotBadge(score: Int) {
    val cs = MaterialTheme.colorScheme
    val tint = when {
        score >= 80 -> cs.tertiary
        score >= 60 -> cs.secondary
        score >= 40 -> cs.primary
        else -> cs.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .then(
                if (score >= 40) Modifier
                else Modifier
                    .background(cs.surfaceContainerHigh)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
    ) {
        Icon(
            Icons.Filled.LocalFireDepartment,
            contentDescription = "热度",
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = score.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 绝对时分的本地时间:始终返回 HH:mm。
 *
 * 年月日不在此显示 —— 列表已按天分组,日期由 [DateGroupHeader] 承担,
 * item 只需展示当天的时分。
 *
 * 服务器时间是 UTC,这里转本地时区。
 */
fun absoluteTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        LocalDateTime.parse(iso, DateTimeFormatter.ISO_DATE_TIME)
            .atZone(ZoneOffset.UTC)
            .withZoneSameInstant(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrDefault("")
}

/**
 * 按天分组的 key:返回本地日期 YYYY-MM-DD(用于把 items 分桶)。
 */
fun dayKeyOf(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        LocalDateTime.parse(iso, DateTimeFormatter.ISO_DATE_TIME)
            .atZone(ZoneOffset.UTC)
            .withZoneSameInstant(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ISO_DATE)
    }.getOrDefault("")
}

/**
 * 把日期 key(YYYY-MM-DD)格式化为人类可读的中文标签:
 *  - 今天 / 昨天 / 前天
 *  - 否则:本周几 / M月d日
 */
fun dayLabel(dayKey: String): String {
    if (dayKey.isBlank()) return ""
    return runCatching {
        val date = LocalDate.parse(dayKey)
        val today = LocalDate.now()
        val days = ChronoUnit.DAYS.between(date, today)
        when {
            days == 0L -> "今天"
            days == 1L -> "昨天"
            days == 2L -> "前天"
            days in 3..6 -> "${date.dayOfWeek.toChinese()} · ${date.format(DateTimeFormatter.ofPattern("M月d日"))}"
            else -> date.format(DateTimeFormatter.ofPattern("M月d日"))
        }
    }.getOrDefault(dayKey)
}

private fun java.time.DayOfWeek.toChinese(): String = when (this) {
    java.time.DayOfWeek.MONDAY -> "周一"
    java.time.DayOfWeek.TUESDAY -> "周二"
    java.time.DayOfWeek.WEDNESDAY -> "周三"
    java.time.DayOfWeek.THURSDAY -> "周四"
    java.time.DayOfWeek.FRIDAY -> "周五"
    java.time.DayOfWeek.SATURDAY -> "周六"
    java.time.DayOfWeek.SUNDAY -> "周日"
}

/**
 * 相对时间(X 小时前)—— 详情页仍在用。
 */
fun relativeTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        val dt = LocalDateTime.parse(iso, DateTimeFormatter.ISO_DATE_TIME)
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val mins = dt.toInstant(ZoneOffset.UTC).until(now, ChronoUnit.MINUTES)
        when {
            mins < 0 -> ""
            mins < 60 -> "${mins}分钟前"
            mins < 60 * 24 -> "${mins / 60}小时前"
            mins < 60 * 24 * 30 -> "${mins / (60 * 24)}天前"
            else -> dt.format(DateTimeFormatter.ofPattern("MM-dd"))
        }
    }.getOrDefault("")
}

/**
 * 日期分组条 —— 列表中按天分组的标题。
 * 视觉统一收口到 [SectionHeader](透明底 + 小竖条强调),此处仅做日期文案的薄封装。
 */
@Composable
fun DateGroupHeader(dayKey: String, modifier: Modifier = Modifier) {
    SectionHeader(title = dayLabel(dayKey), modifier = modifier)
}
