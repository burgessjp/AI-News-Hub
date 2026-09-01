package com.peng.ainewshub.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.data.source.SourceFreshness
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 列表顶部居中的「数据时间」横幅 —— 各归档源列表页通用:
 * 显示「数据更新时间：YYYY-MM-DD HH:mm:ss」(绝对时间)。
 *
 * 归档数据是历史快照(每天两批),绝对时间让用户知道看的是哪一批的归档;
 * 时间取自 ViewModel 的 lastRefreshAt(快照顶层的 fetched_at_ms,归档
 * Repository 已透传)。
 *
 * 断供警示:快照时刻距今超过 [SourceFreshness.STALE_THRESHOLD_MS]
 * (该源连续多批抓取失败,index latest 指针未前进)时,追加一行错误色警示
 * 「数据已 N 天未更新,该源可能暂时断供」—— 单批失败/延迟不会触发,不制造噪音。
 *
 * 放在 LazyColumn 的第一个 item {} 里,随列表滚动,出现在顶部。
 *
 * @param fetchedAtMillis 数据时刻(毫秒);null(尚未成功刷新过)时不显示
 */
@Composable
fun ListUpdateTimeHeader(
    fetchedAtMillis: Long?
) {
    if (fetchedAtMillis == null) return
    val context = LocalContext.current
    val time = SimpleDateFormat(
        context.getString(R.string.list_update_date_fmt), Locale.getDefault()
    ).format(Date(fetchedAtMillis))
    val staleDays = SourceFreshness.staleDays(fetchedAtMillis)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = context.getString(R.string.list_update_archive_time, time),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (staleDays != null) {
            Text(
                text = stringResource(
                    R.string.source_stale_warning,
                    pluralStringResource(R.plurals.source_stale_days, staleDays, staleDays)
                ),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
