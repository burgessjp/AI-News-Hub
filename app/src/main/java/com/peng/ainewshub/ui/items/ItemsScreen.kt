package com.peng.ainewshub.ui.items
import androidx.compose.ui.res.stringResource
import com.peng.ainewshub.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.peng.ainewshub.ui.anim.Motion
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.debounce
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.data.model.NewsCategory
import com.peng.ainewshub.data.model.NewsItem
import com.peng.ainewshub.ui.DateGroupHeader
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.ItemsViewModel
import com.peng.ainewshub.ui.NewsCard
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.dayKeyOf
import com.peng.ainewshub.ui.components.BottomBarReservedHeight
import com.peng.ainewshub.ui.components.HairlineDivider
import com.peng.ainewshub.ui.components.NewsCardSkeletonList
import com.peng.ainewshub.ui.components.rememberHaptics
import com.peng.ainewshub.ui.components.rememberReadUrls
import kotlinx.coroutines.launch

/** 一天的分组(日期 key + 该天的条目)。空 key 表示不分天(搜索模式)。 */
private data class GroupedDay(val dayKey: String, val items: List<NewsItem>)

/**
 * 动态列表屏幕。
 *
 * 交互改进:
 *  - 加载时显示 shimmer 骨架(4 个),不显示裸 CircularProgressIndicator
 *  - 列表项用 Modifier.animateItem() — 切分类时自动淡入/移位(LazyItemScope 成员)
 *  - 滚下超过 1 屏时浮现"返回顶部"FAB(slideInVertically/slideOutVertically)
 *  - 下拉刷新(PullToRefreshBox);[onRefreshExtra] 用于联动刷新页面自带的
 *    额外模块(如精选 tab 的「今日热点」)
 *  - [reselectSignal] 递增(重击当前 tab)时:滚回顶部并刷新
 *
 * @param reserveBottomBarSpace 底部是否预留浮动药丸底栏高度。根 tab(底栏悬浮)
 *        传 true;二级页(底栏已隐藏,如「全部动态」)传 false,避免底部多余留白。
 */
@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ItemsScreen(
    onItemClick: (NewsItem) -> Unit,
    // 滚动状态由调用方上提持有(MainActivity):push 二级页返回后保持位置
    listState: LazyListState,
    modifier: Modifier = Modifier,
    vm: ItemsViewModel = viewModel(),
    header: (@Composable () -> Unit)? = null,
    reserveBottomBarSpace: Boolean = true,
    onRefreshExtra: (() -> Unit)? = null,
    reselectSignal: Int = 0
) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val isLoadingMore by vm.isLoadingMore.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    // 已读判定与「只看未读」过滤(屏幕级状态,不持久化):过滤在展示层做,
    // 不改 ViewModel 取数 —— 分页/刷新逻辑不受影响,翻到底仍会加载更多
    val readUrls = rememberReadUrls()
    var unreadOnly by rememberSaveable { mutableStateOf(false) }

    val showBackToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex >= 2 }
    }

    // 切换筛选(分类/模式/搜索词)时,把列表滚回顶部。
    // 数据会由 ViewModel 重新拉取,但 LazyListState 自身不会复位 —— 若不手动复位,
    // 新列表会停在旧分类滑到的位置,甚至可能直接命中"接近底部"误触发翻页。
    // lastFilter 记录已消费的筛选:从二级页返回重新进入组合时 filter 未变 → 跳过,
    // 保住上提持有后恢复的滚动位置(listState 由 MainActivity 持有)。
    var lastFilter by remember { mutableStateOf(filter) }
    LaunchedEffect(filter) {
        if (filter != lastFilter) {
            lastFilter = filter
            listState.scrollToItem(0)
        }
    }

    // 重击当前 tab(reselectSignal 递增):滚回顶部并刷新。
    // lastHandled 记录已消费的 tick:切 tab 回来/从二级页返回时本屏重新进入组合,
    // LaunchedEffect 会以旧的 reselectSignal 再跑一遍,与 lastHandled 相等即跳过,
    // 避免「页面重新可见就自动刷新」。
    var lastHandledReselect by remember { mutableIntStateOf(reselectSignal) }
    LaunchedEffect(reselectSignal) {
        if (reselectSignal != lastHandledReselect) {
            lastHandledReselect = reselectSignal
            listState.animateScrollToItem(0)
            vm.refresh()
            onRefreshExtra?.invoke()
        }
    }

    // 滚动接近底部时自动加载下一页。
    // 关键点:
    //  1) snapshotFlow 只 emit Boolean(是否接近底部),配合 distinctUntilChanged ——
    //     一次"接近底部"状态只会触发一次,不会因滚动每帧重复触发。
    //  2) debounce(400ms):快速甩动列表时,等滚动稳定后再判断,避免短时间内连发请求
    //     导致列表瞬间膨胀、主线程测量/布局跟不上而 ANR。
    //  3) hasMore / isLoadingMore 进 LaunchedEffect 的 key:加载完成后重启收集,
    //     保证连续翻多页时下一次接近底部能再次触发。
    LaunchedEffect(listState, hasMore, isLoadingMore) {
        snapshotFlow {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            total > 0 && lastVisible >= 0 && total - lastVisible <= 5
        }
            .distinctUntilChanged()
            .filter { it }
            .debounce(400)
            .collect { if (hasMore && !isLoadingMore) vm.loadMore() }
    }

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (val s = state) {
                    is UiState.Loading -> NewsCardSkeletonList(count = 4)
                    is UiState.Error -> ErrorState(
                        message = s.message,
                        onRetry = { vm.refresh() }
                    )
                    is UiState.Success -> {
                        // 注意:数据源用 vm.items(随 loadMore 持续追加),而不是 s.data。
                        // s.data 是 state flow 在 filter 变化时拍的快照,只含当时那一页;
                        // loadMore 后 _items 已更新但 state 不会重新 emit,若用 s.data
                        // 列表会永远停在第一页(即便后续页已加载)。
                        val data = items
                        // 「只看未读」过滤(已读 = 详情页两个打开 URL 之一命中浏览历史)
                        val shown = if (unreadOnly) {
                            data.filter { it.permalink !in readUrls && it.url !in readUrls }
                        } else data
                        when {
                            data.isEmpty() -> {
                                // 空态场景化:搜索无结果给「换关键词」恢复路径;非搜索空态给刷新动作
                                EmptyState(
                                    title = stringResource(if (filter.isSearching) R.string.common_no_result else R.string.common_empty),
                                    subtitle = stringResource(if (filter.isSearching) R.string.items_try_other_keyword else R.string.items_refresh_hint_button),
                                    icon = if (filter.isSearching) Icons.Outlined.SearchOff else Icons.Outlined.Inbox,
                                    actionLabel = if (filter.isSearching) null else stringResource(R.string.common_refresh_once),
                                    onAction = if (filter.isSearching) null else ({ vm.refresh() })
                                )
                            }
                            // 过滤后为空:全部已读(数据本身非空,不给刷新动作)
                            shown.isEmpty() -> EmptyState(
                                title = stringResource(R.string.items_no_unread),
                                subtitle = stringResource(R.string.items_no_unread_subtitle),
                                icon = Icons.Outlined.Inbox
                            )
                            else -> {
                                // 按天分组(本地时区),保持原列表顺序。
                                // 仅当未在搜索状态时分组 —— 搜索结果跨天聚合意义不大,且更紧凑。
                                val grouped = remember(shown, filter.isSearching) {
                                    if (filter.isSearching) {
                                        listOf(GroupedDay("", shown))
                                    } else {
                                        shown.groupBy { dayKeyOf(it.publishedAt) }
                                            .map { (k, items) -> GroupedDay(k, items) }
                                    }
                                }
                                PullToRefreshBox(
                                    isRefreshing = isRefreshing,
                                    onRefresh = {
                                        haptics.tick()
                                        vm.refresh()
                                        // 联动刷新页面自带的额外模块(如精选的「今日热点」)
                                        onRefreshExtra?.invoke()
                                    }
                                ) {
                                    LazyColumn(
                                        state = listState,
                                        // 根 tab 底部预留浮动药丸底栏的高度(reserveBottomBarSpace),
                                        // 避免末项被遮挡;二级页底栏已隐藏,只留常规间距。
                                        // 顶部留 4dp 与顶栏发丝线拉开间距。
                                        contentPadding = PaddingValues(
                                            top = 4.dp,
                                            bottom = if (reserveBottomBarSpace) BottomBarReservedHeight else 16.dp
                                        ),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        // 顶部装饰区(如「今日热点」卡片 + 区块标题)。仅非搜索时
                                        // 显示 —— 搜索态应聚焦结果,不宜插入热点等装饰模块。
                                        if (header != null && !filter.isSearching) {
                                            item(key = "screen-header") { header() }
                                        }
                                        // 分类 chips(搜索态隐藏)。随列表滚动,不再钉在顶部。
                                        if (!filter.isSearching) {
                                            item(key = "category-chips") {
                                                CategoryChips(
                                                    selected = filter.category,
                                                    onSelect = { vm.setCategory(it) },
                                                    unreadOnly = unreadOnly,
                                                    onToggleUnread = { unreadOnly = !unreadOnly }
                                                )
                                            }
                                        }
                                        grouped.forEach { group ->
                                            // 日期分组条(搜索模式不显示)
                                            if (!filter.isSearching && group.dayKey.isNotEmpty()) {
                                                item(key = "header-${group.dayKey}") {
                                                    DateGroupHeader(dayKey = group.dayKey)
                                                }
                                            }
                                            // 组内条目(每条之间用 hairline 分隔,缩进对齐右栏)
                                            // 注意:不使用 animateItem —— 分页加载会一次性追加数十条,
                                            // 每条都纳入重排动画会让主线程测量/布局成本爆炸,在
                                            // 列表累积到几百上千条时引发 ANR。静默追加更稳。
                                            items(items = group.items, key = { it.id }) { item ->
                                                NewsCard(
                                                    item = item,
                                                    onClick = { onItemClick(item) },
                                                    // 已读 = 详情页两个打开 URL(permalink/原文)之一命中
                                                    isRead = item.permalink in readUrls || item.url in readUrls
                                                )
                                                if (item.id != group.items.last().id) {
                                                    HairlineDivider(startIndent = 72.dp)
                                                }
                                            }
                                        }
                                        // 底部加载状态 footer:
                                        //  - 正在加载下一页:小转圈 + "加载中…"
                                        //  - 没有更多了(后端返回 hasNext=false):"已加载全部"
                                        item(key = "footer") {
                                            ListFooter(
                                                loading = isLoadingMore,
                                                done = !hasMore
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 返回顶部 FAB(悬浮右下):从底部滑入/滑出 + 淡入淡出。无缩放,风格统一。
        AnimatedVisibility(
            visible = showBackToTop,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(Motion.SHORT, easing = Motion.EmphasizedDecel)
            ) + fadeIn(tween(Motion.SHORT, easing = Motion.EmphasizedDecel)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(Motion.SHORT, easing = Motion.EmphasizedAccel)
            ) + fadeOut(tween(Motion.SHORT, easing = Motion.EmphasizedAccel)),
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            FloatingActionButton(
                onClick = {
                    scope.launch { listState.animateScrollToItem(0) }
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                // 根 tab 底部留白避开浮动药丸底栏(BottomBarReservedHeight 含药丸高度 +
                // 距底 margin + 手势栏 inset);二级页底栏已隐藏,只留常规间距。
                // 左右 18dp 维持与列表内容对齐。
                modifier = Modifier.padding(
                    end = 18.dp,
                    bottom = if (reserveBottomBarSpace) BottomBarReservedHeight else 24.dp
                )
            ) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.common_back_to_top))
            }
        }
    }
}

/**
 * 列表底部状态 footer。
 *  - loading = true:加载下一页中(小转圈 + 文案)
 *  - loading = false 且 done = true:已加载全部
 *  - 其余(首屏或还有更多但未触发加载):占位空白,不显示文案
 */
@Composable
private fun ListFooter(loading: Boolean, done: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.width(16.dp).height(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.common_loading),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            done -> Text(
                stringResource(R.string.common_all_loaded),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CategoryChips(
    selected: NewsCategory?,
    onSelect: (NewsCategory?) -> Unit,
    // 「只看未读」过滤(展示层过滤,见 ItemsScreen);回调为 null 时不显示该 chip
    unreadOnly: Boolean = false,
    onToggleUnread: (() -> Unit)? = null
) {
    val scrollState = rememberScrollState()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.items_category_all)) },
                // 完全圆角(药丸),对齐设计系统的 pill 形标签
                shape = CircleShape,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected == null) androidx.compose.ui.graphics.Color.Transparent
                    else MaterialTheme.colorScheme.outlineVariant
                ),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
            NewsCategory.entries.forEach { cat ->
                FilterChip(
                    selected = selected == cat,
                    onClick = { onSelect(cat) },
                    label = { Text(stringResource(cat.labelRes)) },
                    shape = CircleShape,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (selected == cat) androidx.compose.ui.graphics.Color.Transparent
                        else MaterialTheme.colorScheme.outlineVariant
                    ),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
            // 行尾「只看未读」:与分类正交,选中态走 tertiary 区分于分类的 primary
            if (onToggleUnread != null) {
                FilterChip(
                    selected = unreadOnly,
                    onClick = onToggleUnread,
                    label = { Text(stringResource(R.string.items_unread_only)) },
                    shape = CircleShape,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (unreadOnly) androidx.compose.ui.graphics.Color.Transparent
                        else MaterialTheme.colorScheme.outlineVariant
                    ),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.tertiary,
                        selectedLabelColor = MaterialTheme.colorScheme.onTertiary,
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    }
}
