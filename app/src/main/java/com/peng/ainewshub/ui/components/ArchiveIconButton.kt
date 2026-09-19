package com.peng.ainewshub.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.theme.AppText

/**
 * 归档/历史入口 —— 文字按钮(全 App 去图标改版):「历史」caption 灰字,
 * 触控高由 padding 撑足;语义与旧日历图标一致(进历史归档列表)。
 */
@Composable
fun ArchiveIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Text(
        text = stringResource(R.string.archive_history_daily),
        style = AppText.caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp)
    )
}
