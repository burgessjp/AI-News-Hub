package com.peng.ainewshub.ui.items

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Context
import com.peng.ainewshub.R
import com.peng.ainewshub.data.db.FavoriteEntity
import com.peng.ainewshub.data.repo.FavoritesRepository
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.FavoritesViewModel
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.rememberHaptics
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 收藏(稍后读)屏 —— 用户在 WebView 顶栏点星收藏的文章。
 *
 * 数据来自 [FavoritesRepository](Room),按收藏时间倒序分页展示。
 *
 * 交互:
 *  - 顶栏右上:清空全部(确认弹窗)
 *  - 列表项:字母占位块 + 标题 + host·来源·相对时间,点击重新打开
 *  - 左滑单条删除 + Snackbar 撤销
 *  - 空态:复用 [EmptyState]
 *
 * @param repo 由 MainActivity 注入的进程级仓库单例
 * @param onBack 返回
 * @param onOpenUrl (url, title, source) —— 重打开时 source 透传实体上的旧值
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    repo: FavoritesRepository,
    onBack: () -> Unit,
    onOpenUrl: (String, String, String?) -> Unit,
    // 滚动状态由 MainActivity 按 Page 持有:进 WebView 返回后保持位置
    listState: LazyListState,
    vm: FavoritesViewModel = viewModel(
        key = "favorites",
        factory = FavoritesViewModel.Factory(repo)
    )
) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()

    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // 暂存最近一次删除,供 Snackbar 撤销(单条撤销;连续删多条只撤销最后一条)
    var lastDeleted by remember { mutableStateOf<FavoriteEntity?>(null) }

    // 删除后弹 Snackbar 提示撤销
    LaunchedEffect(lastDeleted) {
        lastDeleted?.let { entity ->
            val r = snackbarHostState.showSnackbar(
                message = context.getString(R.string.history_deleted),
                actionLabel = context.getString(R.string.history_undo)
            )
            if (r == SnackbarResult.ActionPerformed) {
                // 撤销 = 原样恢复实体(保留原 savedAt,不刷新收藏时间)
                vm.restore(entity)
            }
            lastDeleted = null
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.favorites_clear_dialog_title)) },
            text = { Text(pluralStringResource(R.plurals.favorites_clear_dialog_message, count, count)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAll()
                    showClearDialog = false
                }) { Text(stringResource(R.string.items_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = stringResource(R.string.favorites_title),
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // 清空(有数据时才有意义)
                    if (favorites.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                Icons.Filled.DeleteSweep,
                                contentDescription = stringResource(R.string.items_clear),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (favorites.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.favorites_empty_title),
                    subtitle = stringResource(R.string.favorites_empty_subtitle),
                    icon = Icons.Outlined.StarBorder
                )
            } else {
                FavoritesList(
                    items = favorites,
                    hasMore = hasMore,
                    listState = listState,
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
                text = stringResource(R.string.items_no_more),
                style = AppText.caption,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun FavoritesList(
    items: List<FavoriteEntity>,
    hasMore: Boolean,
    listState: LazyListState,
    onClick: (FavoriteEntity) -> Unit,
    onSwipeDelete: (FavoriteEntity) -> Unit,
    onLoadMore: () -> Unit
) {
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
        items(items = items, key = { e -> e.url }) { e ->
            FavoriteRow(
                entity = e,
                onClick = { onClick(e) },
                onSwipeDelete = { onSwipeDelete(e) }
            )
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
 * 单条收藏行 —— 可左滑删除。
 *
 * 视觉与浏览历史行一致:
 *  - 左:40dp 圆角块,底色按 host 哈希取三档强调色之一(12% alpha),中央 host 首字母大写
 *  - 标题(titleSmall/SemiBold,2 行省略)
 *  - 副行:host · 来源 · 相对时间,弱色
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoriteRow(
    entity: FavoriteEntity,
    onClick: () -> Unit,
    onSwipeDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                // 删除真正提交的那一刻给确认触感(半程回弹不震)
                haptics.confirm()
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
                    contentDescription = stringResource(R.string.history_delete),
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
                    .clip(MaterialTheme.shapes.small)
                    .background(tileBg.copy(alpha = AppAlpha.badgeOverlay)),
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
                    if (entity.host.isNotBlank()) {
                        MetaText(entity.host)
                        MetaDot()
                    }
                    if (!entity.source.isNullOrBlank()) {
                        MetaText(entity.source)
                        MetaDot()
                    }
                    MetaText(formatRelativeAgo(context, entity.savedAt))
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

/**
 * 按 host 字符串哈希选三档强调色之一(与浏览历史行配色档一致)。
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
 * 相对时间格式化(与浏览历史行同源,语义一致)。
 * <1 分「刚刚」;<60 分「N 分钟前」;<24h「N 小时前」;<7d「N 天前」;更早显示日期。
 */
private fun formatRelativeAgo(context: Context, savedAtMillis: Long): String {
    val res = context.resources
    val diff = System.currentTimeMillis() - savedAtMillis
    val minutes = diff / 60_000L
    return when {
        minutes < 1 -> context.getString(R.string.time_just_now)
        minutes < 60 -> minutes.toInt().let { res.getQuantityString(R.plurals.time_minutes_ago, it, it) }
        minutes < 60 * 24 -> (minutes / 60).toInt().let { res.getQuantityString(R.plurals.time_hours_ago, it, it) }
        minutes < 60 * 24 * 7 -> (minutes / (60 * 24)).toInt().let { res.getQuantityString(R.plurals.time_days_ago, it, it) }
        else -> SimpleDateFormat(context.getString(R.string.date_fmt_month_day_time_dash), Locale.getDefault()).format(Date(savedAtMillis))
    }
}
