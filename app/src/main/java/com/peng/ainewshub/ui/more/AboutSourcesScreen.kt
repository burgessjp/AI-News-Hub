package com.peng.ainewshub.ui.more

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.data.source.DEFAULT_SOURCE_ORDER

/**
 * 关于 · 数据来源二级页 —— 从关于页「资源」组进入。
 *
 * 列出 8 个源(与信息源页 / 摘要 Tab 同一套元数据,来自 [sourceMeta]),顺序固定用
 * [DEFAULT_SOURCE_ORDER](关于域是 App 静态说明,不跟随用户自定义顺序)。
 *
 * 与「信息源」页([SourcesScreen])的分工:信息源页是浏览入口,点击进入 App 内
 * 各源列表页;本页是说明性页面,点击行经内置 WebView 访问各源官网(走唯一入口
 * onOpenUrl,计入浏览历史)。
 *
 * 二级页惯例:顶栏带返回箭头、标题 secondaryTitleFontSize;列表滚动状态由导航层
 * 经 [listState] 下传(AnimatedContent 换页会销毁屏内 remember)。
 */
@Composable
fun AboutSourcesScreen(
    onBack: () -> Unit,
    /** 打开各源官网(url + 标题),走全局 openUrl 唯一入口。 */
    onOpenUrl: (String, String) -> Unit,
    listState: LazyListState
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.about_section_data_sources),
                subtitle = stringResource(R.string.about_sources_page_hint),
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(padding),
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            DEFAULT_SOURCE_ORDER.forEachIndexed { idx, key ->
                item(key = key) {
                    val meta = sourceMeta(key)
                    IconTileRow(
                        icon = meta.icon,
                        brand = meta.brand,
                        title = meta.title,
                        subtitle = meta.subtitle,
                        showDivider = idx != DEFAULT_SOURCE_ORDER.lastIndex,
                        onClick = { onOpenUrl(meta.url, meta.title) }
                    )
                }
            }
        }
    }
}
