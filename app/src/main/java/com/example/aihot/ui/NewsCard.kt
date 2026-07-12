package com.example.aihot.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aihot.data.NewsItem
import com.example.aihot.ui.theme.AppText
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
 *  - 左栏(固定窄宽):绝对时间 HH:mm(年月日由分组条承担,这里只显示时分)
 *  - 右栏(权重 1):
 *      标题行:标题(2 行,SemiBold) … 🔥 分数(右对齐,分档配色,带火焰图标)
 *      摘要(2 行,onSurfaceVariant)
 *      底部行:精选标记 + 来源 · 分类
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
        // 左栏:时分 HH:mm(年月日已由日期分组条承担)
        val time = absoluteTime(item.publishedAt)
        if (time.isNotEmpty()) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // 右栏:主内容
        Column(modifier = Modifier.weight(1f)) {
            // 标题行:标题(权重 1) + 热度分数(右对齐,自然宽度)
            //  - 分数提到标题行右侧,与标题首行对齐,避免与底部来源信息混在一起
            //  - 带火焰图标 + 分值,明示这是"热度",而非无意义的数字
            if (item.title.isNotBlank()) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (item.score > 0) {
                        ScoreBadge(score = item.score)
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
 * 精选 Hero 卡片 —— 精选 tab 列表顶部的强调卡片,对齐 "Synthetic Intelligence News"
 * 设计系统的 Featured Section(参考 system_stream_editorial 原型)。
 *
 * 视觉:
 *  - 白底(surfaceContainerLowest)+ 1px outlineVariant 描边 + 24dp 大圆角
 *  - 左侧 6dp primary 竖条贯穿,作为"精选/头条"的视觉锚点
 *  - 顶部:「精选」小徽章(error-container 底)+ 发布时间
 *  - 大标题(titleSection 20sp SemiBold,2 行,紧字距)
 *  - 摘要(bodySmall 2 行,onSurfaceVariant)
 *  - 底行:star + 来源 · 分类 + 火焰分数
 *
 * 与 [NewsCard] 的区别:Hero 是"卡片"形态(有描边/圆角/留白),NewsCard 是"扁平行"
 * (靠发丝线分隔);Hero 赋予头条新闻视觉重量。
 */
@Composable
fun FeaturedHeroCard(
    item: NewsItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = cs.surfaceContainerLowest,
        contentColor = cs.onSurface,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.6f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 左侧 primary 竖条 —— 头条/精选的视觉锚点
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(6.dp)
                    .height(112.dp)
                    .background(cs.primary)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 20.dp, top = 20.dp, bottom = 18.dp)
            ) {
                // 顶部:精选徽章 + 时间
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(cs.errorContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "精选",
                            style = AppText.caption,
                            color = cs.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    val time = absoluteTime(item.publishedAt)
                    if (time.isNotEmpty()) {
                        Text(
                            text = time,
                            style = AppText.caption,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 大标题
                if (item.title.isNotBlank()) {
                    Text(
                        text = item.title,
                        style = AppText.titleSection,
                        color = cs.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 摘要
                if (!item.summary.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = item.summary,
                        style = AppText.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 底行:来源/分类 + 分数
                Spacer(Modifier.height(14.dp))
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
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    val meta = buildString {
                        if (item.source.isNotBlank()) append(item.source)
                        if (!item.category.isNullOrBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(item.categoryLabel())
                        }
                    }
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = AppText.caption,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (item.score > 0) {
                        ScoreBadge(score = item.score)
                    }
                }
            }
        }
    }
}

/**
 * 列表项热度分数徽章 —— 与详情页 [com.example.aihot.ui.ScoreBadgeLarge] 同源配色,
 * 缩到列表项可用的尺寸。
 *
 * 视觉:🔥 + 分值,分档配色(≥80 红 / ≥60 橙黄 / ≥40 次要 / 其余灰),
 * 火焰图标明示语义是"热度",避免出现无单位的裸数字。
 */
@Composable
private fun ScoreBadge(score: Int) {
    val cs = MaterialTheme.colorScheme
    val color = when {
        score >= 80 -> cs.error
        score >= 60 -> cs.tertiary
        score >= 40 -> cs.secondary
        else -> cs.outline
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(start = 8.dp)
    ) {
        Icon(
            Icons.Filled.LocalFireDepartment,
            contentDescription = "热度",
            tint = color,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = score.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = color,
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
 * 日期分组条 —— 列表中按天分组的 sticky 标题。
 *
 * 视觉:浅色背景(surfaceContainerHigh) + cyan 加粗文字,横贯全宽。
 */
@Composable
fun DateGroupHeader(dayKey: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dayLabel(dayKey),
            style = MaterialTheme.typography.labelLarge,
            color = cs.primary,
            fontWeight = FontWeight.Bold,
            // 章节条专用字距:5 处 labelLarge 有 3 种字距(0.5/1.0/默认),此值不进 Type.kt 以免误伤
            letterSpacing = 0.5.sp
        )
    }
}
