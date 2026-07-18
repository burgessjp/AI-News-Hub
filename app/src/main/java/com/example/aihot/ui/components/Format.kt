package com.example.aihot.ui.components

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 列表 meta 通用格式化 —— 收口 Hub 各屏曾经各自私有的计数/时间格式化。
 *
 * 行为与各屏原私有实现逐字一致,只做去重,不改变任何输出:
 *  - [formatCount]:GitHub / LinuxDo / HuggingFace 三屏逐字相同,合一
 *  - [formatRelativeTime]:HackerNews 列表屏与评论屏逐字相同(Unix 秒),合一
 *  - [formatRelative]:LinuxDo 热榜(毫秒,7 天窗口),随 formatCount 一并收口
 *
 * 刻意不收口的(输入/输出均不同,非重复实现):
 *  - NewsCard.kt 的 absoluteTime/dayLabel/relativeTime:输入是 ISO 字符串(UTC),
 *    输出为时分 / 今天昨天 / 无空格「N分钟前」
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
fun formatRelativeTime(unixSeconds: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - unixSeconds * 1000L
    val minutes = diff / 60_000L
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} 天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(unixSeconds * 1000L))
    }
}

/** 相对时间(毫秒):「刚刚 / N分钟前 / N小时前 / N天前 / 超过 7 天显 MM-dd」。 */
fun formatRelative(tsMillis: Long): String {
    val diff = System.currentTimeMillis() - tsMillis
    val minutes = diff / 60_000L
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 60 * 24 -> "${minutes / 60}小时前"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}天前"
        else -> SimpleDateFormat("MM-dd", Locale.CHINA).format(Date(tsMillis))
    }
}
