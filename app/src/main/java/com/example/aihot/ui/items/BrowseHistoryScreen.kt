package com.example.aihot.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aihot.data.BrowseHistoryEntity
import com.example.aihot.data.BrowseHistoryRepository
import com.example.aihot.ui.BrowseHistoryViewModel
import com.example.aihot.ui.EmptyState
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.AppTopBarDefaults
import com.example.aihot.ui.components.SettingsGroupHeader
import com.example.aihot.ui.theme.AppText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 浏览历史屏 —— 记录所有经 openUrl 打开过的网页。
 *
 * 数据来自 [BrowseHistoryRepository](Room),ViewModel 用搜索词 + 分组开关合并流。
 *
 * 交互:
 *  - 顶栏右上:按域名/按时间分组切换 / 清空全部
 *  - 列表项:字母占位块 + 标题 + host·来源·相对时间,点击重新打开(自动计数+1)
 *  - 左滑单条删除 + Snackbar 撤销
 *  - 按域名分组时,同 host 聚合,加 section header
 *  - 空态:复用 [EmptyState]
 *
 * @param repo 由 MainActivity 注入的进程级仓库单例
 * @param onBack 返回
 * @param onOpenUrl (url, title, source) —— 重打开时 source 透传实体上的旧值
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseHistoryScreen(
    repo: BrowseHistoryRepository,
    onBack: () -> Unit,
    onOpenUrl: (String, String, String?) -> Unit,
    vm: BrowseHistoryViewModel = viewModel(
        key = "browse_history",
        factory = BrowseHistoryViewModel.Factory(repo)
    )
) {
    val history by vm.history.collectAsStateWithLifecycle()
    val groupByHost by vm.groupByHost.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()

    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    // 暂存最近一次删除,供 Snackbar 撤销(单条撤销;连续删多条只撤销最后一条)
    var lastDeleted by remember { mutableStateOf<BrowseHistoryEntity?>(null) }

    // 删除后弹 Snackbar 提示撤销
    LaunchedEffect(lastDeleted) {
        lastDeleted?.let { entity ->
            val r = snackbarHostState.showSnackbar(
                message = "已删除",
                actionLabel = "撤销"
            )
            if (r == SnackbarResult.ActionPerformed) {
                // 撤销 = 原样恢复实体(保留原 visitCount/visitedAt,不新增访问)
                vm.restore(entity)
            }
            lastDeleted = null
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空浏览历史") },
            text = { Text("将删除全部 $count 条记录,此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAll()
                    showClearDialog = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = "浏览历史",
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 分组切换(有数据时才有意义)
                    if (history.isNotEmpty()) {
                        IconButton(onClick = vm::toggleGroupByHost) {
                            Text(
                                text = if (groupByHost) "时间" else "域名",
                                style = AppText.caption,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        // 清空
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                Icons.Filled.DeleteSweep,
                                contentDescription = "清空",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (history.isEmpty()) {
                EmptyState(
                    title = "还没有浏览记录",
                    subtitle = "打开过的网页会出现在这里",
                    icon = Icons.Outlined.History
                )
            } else {
                HistoryList(
                    items = history,
                    groupByHost = groupByHost,
                    hasMore = hasMore,
                    onClick = { e -> onOpenUrl(e.url, e.title, e.source) },
                    onSwipeDelete = { e ->
                        lastDeleted = e
                        vm.delete(e.url)
                    },
                    onLoadMore = vm::loadMore
                )
            }
        }
    }
}

/** 列表底部状态:还有更多时转圈加载,已到末页显示"没有更多了"。 */
@Composable
private fun LoadMoreFooter(hasMore: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (hasMore) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text(
                text = "没有更多了",
                style = AppText.caption,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun HistoryList(
    items: List<BrowseHistoryEntity>,
    groupByHost: Boolean,
    hasMore: Boolean,
    onClick: (BrowseHistoryEntity) -> Unit,
    onSwipeDelete: (BrowseHistoryEntity) -> Unit,
    onLoadMore: () -> Unit
) {
    val listState = rememberLazyListState()

    // 滚动接近底部时加载下一页:最后一个可见项索引 >= 总数 - 阈值,且还有更多
    val reachedBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - 3
        }
    }
    LaunchedEffect(reachedBottom, hasMore) {
        if (reachedBottom && hasMore) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        if (groupByHost) {
            // 按 host 分组,保留组内时间倒序(DB 已按 visitedAt DESC 返回)
            val grouped = items.groupBy { it.host }
            grouped.forEach { (host, group) ->
                item(key = "header_$host") {
                    SettingsGroupHeader(
                        host,
                        accentColor = MaterialTheme.colorScheme.primary
                    )
                }
                items(items = group, key = { e -> "g:${e.url}" }) { e ->
                    HistoryRow(
                        entity = e,
                        showHost = false, // 分组头已显示 host,行内不再重复
                        onClick = { onClick(e) },
                        onSwipeDelete = { onSwipeDelete(e) }
                    )
                }
            }
        } else {
            items(items = items, key = { e -> "t:${e.url}" }) { e ->
                HistoryRow(
                    entity = e,
                    showHost = true,
                    onClick = { onClick(e) },
                    onSwipeDelete = { onSwipeDelete(e) }
                )
            }
        }

        // 底部加载指示:还有更多时显示转圈,已到末页显示"没有更多了"
        if (items.isNotEmpty()) {
            item(key = "footer") {
                LoadMoreFooter(hasMore = hasMore)
            }
        }
    }
}

/**
 * 单条历史行 —— 可左滑删除。
 *
 * 视觉:
 *  - 左:48dp 圆角块,底色按 host 哈希取三档强调色之一(12% alpha),中央 host 首字母大写
 *  - 标题(titleMedium/SemiBold,2 行省略)
 *  - 副行:host(可选) · 来源 · 相对时间;末尾 visitCount>1 时 ×N 徽章
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryRow(
    entity: BrowseHistoryEntity,
    showHost: Boolean,
    onClick: () -> Unit,
    onSwipeDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                onSwipeDelete()
                true
            } else false
        },
        positionalThreshold = { distance -> distance * 0.5f }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(cs.errorContainer)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Filled.DeleteSweep,
                    contentDescription = "删除",
                    tint = cs.onErrorContainer
                )
            }
        },
        enableDismissFromStartToEnd = false
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(cs.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = androidx.compose.material3.ripple(),
                    onClick = onClick
                )
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 字母占位块:host 首字母大写 + 按 host 哈希选色
            val (tileBg, tileFg) = hostAccent(host = entity.host, cs = cs)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tileBg.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = entity.host.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = tileFg
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entity.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                // 副行:host · 来源 · 相对时间,弱色
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (showHost && entity.host.isNotBlank()) {
                        MetaText(entity.host)
                        MetaDot()
                    }
                    if (!entity.source.isNullOrBlank()) {
                        MetaText(entity.source)
                        MetaDot()
                    }
                    MetaText(formatRelativeAgo(entity.visitedAt))
                    if (entity.visitCount > 1) {
                        Spacer(Modifier.width(2.dp))
                        VisitBadge(count = entity.visitCount)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaText(text: String) {
    Text(
        text = text,
        style = AppText.caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun MetaDot() {
    Text(
        text = "·",
        style = AppText.caption,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/** 访问次数徽章:×N,强调色小字。 */
@Composable
private fun VisitBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text = "×$count",
            style = AppText.caption,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 按 host 字符串哈希选三档强调色之一(与 MoreScreen 的 IconTileRow 配色档一致)。
 * 同一站点始终同色,视觉上形成隐式分组。
 */
private fun hostAccent(
    host: String,
    cs: androidx.compose.material3.ColorScheme
): Pair<Color, Color> {
    val bucket = (host.hashCode().toInt() and 0x7fffffff) % 3
    return when (bucket) {
        0 -> cs.primary to cs.primary
        1 -> cs.secondary to cs.secondary
        else -> cs.tertiary to cs.tertiary
    }
}

/**
 * 相对时间格式化(与 GitHubTrendingScreen.formatRefreshAgo 同源,语义一致)。
 * <1 分「刚刚」;<60 分「N 分钟前」;<24h「N 小时前」;<7d「N 天前」;更早显示日期。
 */
private fun formatRelativeAgo(visitedAtMillis: Long): String {
    val diff = System.currentTimeMillis() - visitedAtMillis
    val minutes = diff / 60_000L
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)} 天前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(visitedAtMillis))
    }
}
