package com.peng.ainewshub.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peng.ainewshub.R
import com.peng.ainewshub.data.source.SourceFreshness
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.theme.AppText
import kotlinx.coroutines.launch
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import com.peng.ainewshub.data.prefs.SettingsStore
import org.burnoutcrew.reorderable.reorderable

/**
 * 信息源(Sources)二级页 —— Hub 浏览区的独立出口,从「更多」页 push 进入。
 *
 * 列出 8 个源(HackerNews / GitHub Trending / OpenAI×Anthropic / HuggingFace Papers /
 * Product Hunt / The Rundown AI / AIHot 精选 / stormzhang AI),元数据来自 [sourceMeta]。
 *
 * **可拖拽自定义顺序**:长按某行进入拖拽,松手即落位并持久化(存 [SettingsStore.sourceOrderFlow])。
 * 顺序变化后摘要 Tab 跟随,关于页固定默认顺序。
 *
 * 二级页惯例:顶栏带返回箭头、标题用 secondaryTitleFontSize,列表不预留浮动底栏
 * (二级页底栏不悬浮)。无章节条(顶栏标题即「信息源」,再加章节条重复)。
 */
@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    /** 点击源行回调,key 来自 [com.peng.ainewshub.data.source.SourceKeys](如 "hackernews")。 */
    onOpen: (String) -> Unit
) {
    val context = LocalContext.current
    val settingsStore = remember(context) { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    // 数据未加载完前用空列表占位(不渲染任何 item),DataStore 读完后直接用真实用户顺序渲染 ——
    // 避免先用默认顺序渲染再切到用户顺序,被 ReorderableItem 的 animateItemPlacement 播成
    // 每次进入页面的位移动画。空 → 8 项是「新增 item」(无源位置可比),不会播位移动画。
    val storedOrder by settingsStore.sourceOrderFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // 本地可编辑副本:拖拽时即时更新 onMove,松手 onDragEnd 后持久化。
    // 首次数据到达时初始化;此后仅由拖拽驱动(不再因 storedOrder 重置而抖动 ——
    // 因为本页是 storedOrder 的唯一写入方,数据加载完成后值不会变)。
    var localOrder by remember { mutableStateOf(storedOrder) }
    LaunchedEffect(storedOrder) { localOrder = storedOrder }

    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            localOrder = localOrder.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
        },
        onDragEnd = { _, _ ->
            // 拖拽结束持久化;延迟捕获 localOrder 最新值(此时 onMove 已更新完毕)
            scope.launch { settingsStore.updateSourceOrder(localOrder) }
        }
    )

    // 源健康度:断供(>24h 未成功抓取,latest 指针未前进)的源在行尾显示警示徽标,
    // 正常源保持干净。数据来自 index latest 指针(共享 2 分钟缓存,通常零额外网络);
    // 拉取失败静默不显示 —— 健康度是增强信息,不值得为它单独弹错误态。
    var staleDays by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        staleDays = SourceFreshness.lastSuccessTimes()
            .mapNotNull { (key, ms) -> SourceFreshness.staleDays(ms, now)?.let { key to it } }
            .toMap()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.sources_title),
                subtitle = stringResource(R.string.sources_drag_hint),
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
            state = reorderState.listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .reorderable(reorderState)
                .detectReorderAfterLongPress(reorderState),
            // 二级页底栏不悬浮,无需预留 BottomBarReservedHeight
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            itemsIndexed(localOrder, key = { _, key -> key }) { idx, key ->
                val meta = sourceMeta(key)
                ReorderableItem(reorderState, key = key) { isDragging ->
                    val stale = staleDays[key]
                    IconTileRow(
                        icon = meta.icon,
                        brand = meta.brand,
                        title = meta.title,
                        subtitle = meta.subtitle,
                        showDivider = idx != localOrder.lastIndex,
                        // let 内不是可组合上下文,须在 composable 作用域现建 lambda
                        trailing = if (stale == null) null else ({ StaleBadge(stale) }),
                        onClick = { onOpen(key) }
                    )
                }
            }
        }
    }
}

/**
 * 断供警示徽标 —— 小字 + 错误色,提示该源已连续多批抓取失败(latest 指针 >24h 未前进)。
 * 仅断供源渲染,正常源不占位。
 */
@Composable
private fun StaleBadge(days: Int) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            Icons.Outlined.Warning,
            contentDescription = null,
            tint = cs.error,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = stringResource(
                R.string.source_stale_badge,
                pluralStringResource(R.plurals.source_stale_days, days, days)
            ),
            style = AppText.caption,
            color = cs.error
        )
    }
}
