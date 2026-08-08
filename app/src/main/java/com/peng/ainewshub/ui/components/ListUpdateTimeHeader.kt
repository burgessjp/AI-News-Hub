package com.peng.ainewshub.ui.components

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.data.source.SourceMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 列表顶部居中的「数据时间」横幅 —— 4 个 Hub 列表页(HackerNews / GitHub Trending /
 * stormzhang AI / HuggingFace)通用,实时与归档两种模式复用同一位置,仅文案不同:
 *
 * - 实时模式([SourceMode.LIVE]):显示「上次刷新 N 分钟前」(相对时间),让用户知道
 *   列表数据有多旧,与 4 小时缓存策略配套。
 * - 归档模式([SourceMode.ARCHIVE]):显示「数据更新时间：YYYY-MM-DD HH:mm:ss」(绝对时间),
 *   归档数据是历史快照(每天 08:00 抓取),绝对时间让用户知道看的是哪天的归档。
 *
 * 时间取自 ViewModel 的 lastRefreshAt:
 *  - 实时模式 = 缓存写入或刚抓取的时刻
 *  - 归档模式 = 快照顶层的 fetched_at_ms(归档 Repository 已透传进 lastRefreshAt)
 *
 * 放在 LazyColumn 的第一个 item {} 里,随列表滚动,出现在顶部。
 *
 * @param sourceMode 当前数据源模式,决定文案格式
 * @param fetchedAtMillis 数据时刻(毫秒);null(尚未成功刷新过)时不显示
 */
@Composable
fun ListUpdateTimeHeader(
    sourceMode: SourceMode,
    fetchedAtMillis: Long?
) {
    if (fetchedAtMillis == null) return
    val context = LocalContext.current
    val text = if (sourceMode == SourceMode.ARCHIVE) {
        val time = SimpleDateFormat(
            context.getString(R.string.list_update_date_fmt), Locale.getDefault()
        ).format(Date(fetchedAtMillis))
        context.getString(R.string.list_update_archive_time, time)
    } else {
        context.getString(R.string.list_update_last_refresh, formatRefreshAgo(context, fetchedAtMillis))
    }
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 把「上次刷新时刻」(毫秒)转成相对时间:刚刚 / N 分钟前 / N 小时前 / N 天前,
 * 超过 7 天直接显示日期,避免「30 天前」这种无意义长串。
 *
 * 原本各 Hub Screen 各有一份私有拷贝,现集中到此(ListUpdateTimeHeader 实时模式复用)。
 * 仍保留为 public 供顶栏等处按需调用。
 */
private fun formatRefreshAgo(context: Context, fetchedAtMillis: Long): String {
    val diff = System.currentTimeMillis() - fetchedAtMillis
    val minutes = diff / 60_000L
    val res = context.resources
    return when {
        minutes < 1 -> context.getString(R.string.time_just_now)
        minutes < 60 -> minutes.toInt().let { res.getQuantityString(R.plurals.time_minutes_ago, it, it) }
        minutes < 60 * 24 -> (minutes / 60).toInt().let { res.getQuantityString(R.plurals.time_hours_ago, it, it) }
        minutes < 60 * 24 * 7 -> (minutes / (60 * 24)).toInt().let { res.getQuantityString(R.plurals.time_days_ago, it, it) }
        else -> SimpleDateFormat(context.getString(R.string.date_fmt_month_day_time_dash), Locale.getDefault()).format(Date(fetchedAtMillis))
    }
}
