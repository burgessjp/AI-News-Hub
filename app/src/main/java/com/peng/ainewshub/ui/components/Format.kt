package com.peng.ainewshub.ui.components

import android.content.Context
import com.peng.ainewshub.R
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/**
 * 列表 meta 通用格式化 —— 收口 Hub 各屏曾经各自私有的计数/时间格式化。
 *
 * 行为与各屏原私有实现逐字一致,只做去重,不改变任何输出:
 *  - [formatCount]:GitHub / HuggingFace 两屏逐字相同,合一
 *  - [formatRelativeTime]:HackerNews 列表屏与评论屏逐字相同(Unix 秒),合一
 *  - [formatRelative]:毫秒时间戳相对化(7 天窗口),随 formatCount 一并收口
 *
 * 国际化:相对时间/日期模式经 [context] 取词(values 中文全集 / values-en 英文),
 * 调用方传局部化 context(Composable 的 LocalContext 或 `context.localized()`);
 * `Locale.getDefault()` 已随应用内语言(见 ui/i18n/AppLocale),不再硬编码 Locale.CHINA。
 *
 * 刻意不收口的(输入/输出均不同,非重复实现):
 *  - NewsCard.kt 的 absoluteTime/dayLabel/relativeTime:输入是 ISO 字符串(UTC),
 *    输出为时分 / 今天昨天 / 无空格「N分钟前」(紧凑 plurals 变体)
 *  - BrowseHistoryScreen.formatRelativeAgo:毫秒输入但更早窗口显示「MM-dd HH:mm」
 */

/** 大数字缩写:< 1000 原样,1.2k / 12k / 1.2m(对齐 GitHub 列表展示习惯)。 */
fun formatCount(n: Int): String = when {
    n < 1000 -> n.toString()
    n < 1_000_000 -> {
        if (n < 10_000) "${"%.1f".format(n / 1000.0)}k" // 1.2k 形态
        else "${n / 1000}k"
    }
    else -> "${"%.1f".format(n / 1_000_000.0)}m"
}

/** 把 Unix 秒级时间戳转成相对时间(如 "3 小时前";超过 30 天显示 yyyy-MM-dd)。 */
fun formatRelativeTime(context: Context, unixSeconds: Long): String {
    val res = context.resources
    val now = System.currentTimeMillis()
    val diff = now - unixSeconds * 1000L
    val minutes = diff / 60_000L
    return when {
        minutes < 1 -> context.getString(R.string.time_just_now)
        minutes < 60 -> minutes.toInt().let { res.getQuantityString(R.plurals.time_minutes_ago, it, it) }
        minutes < 60 * 24 -> (minutes / 60).toInt().let { res.getQuantityString(R.plurals.time_hours_ago, it, it) }
        minutes < 60 * 24 * 30 -> (minutes / (60 * 24)).toInt().let { res.getQuantityString(R.plurals.time_days_ago, it, it) }
        else -> SimpleDateFormat(context.getString(R.string.date_fmt_full), Locale.getDefault()).format(Date(unixSeconds * 1000L))
    }
}

/** 相对时间(毫秒):「刚刚 / N分钟前 / N小时前 / N天前 / 超过 7 天显 MM-dd」。 */
fun formatRelative(context: Context, tsMillis: Long): String {
    val res = context.resources
    val diff = System.currentTimeMillis() - tsMillis
    val minutes = diff / 60_000L
    return when {
        minutes < 1 -> context.getString(R.string.time_just_now)
        minutes < 60 -> minutes.toInt().let { res.getQuantityString(R.plurals.time_minutes_ago_compact, it, it) }
        minutes < 60 * 24 -> (minutes / 60).toInt().let { res.getQuantityString(R.plurals.time_hours_ago_compact, it, it) }
        minutes < 60 * 24 * 7 -> (minutes / (60 * 24)).toInt().let { res.getQuantityString(R.plurals.time_days_ago_compact, it, it) }
        else -> SimpleDateFormat(context.getString(R.string.date_fmt_month_day_dash), Locale.getDefault()).format(Date(tsMillis))
    }
}

/**
 * DayOfWeek(1=周一..7=周日)→ 本地化短星期名(「周二」/ "Tue")。
 * 手工表而非 SimpleDateFormat "E":中文 "E" 输出「星期二」,与 App 旧样式「周二」不符。
 */
fun weekdayLabel(context: Context, dayOfWeek: Int): String =
    context.resources.getStringArray(R.array.weekdays)[(dayOfWeek - 1).coerceIn(0, 6)]

/**
 * 归档日期(YYYY-MM-DD)→ 列表行日期标签:「今天/昨天/前天/M月d日 · 周X」。
 * 历史回顾 hub 各段日期列表行同规格(SummaryArchiveList / OverviewArchiveList / TrendsArchiveList 共用)。
 * 解析失败原样返回日期串。
 */
fun archiveDateLabel(context: Context, date: String): String {
    return runCatching {
        val d = LocalDate.parse(date)
        val today = LocalDate.now()
        val days = ChronoUnit.DAYS.between(d, today)
        val base = when {
            days == 0L -> context.getString(R.string.time_today)
            days == 1L -> context.getString(R.string.time_yesterday)
            days == 2L -> context.getString(R.string.time_day_before_yesterday)
            else -> d.format(
                DateTimeFormatter.ofPattern(
                    context.getString(R.string.date_fmt_month_day),
                    Locale.getDefault()
                )
            )
        }
        "$base · ${weekdayLabel(context, d.dayOfWeek.value)}"
    }.getOrDefault(date)
}
