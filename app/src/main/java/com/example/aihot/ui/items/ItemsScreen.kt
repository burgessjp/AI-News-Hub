package com.example.aihot.ui.items

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.example.aihot.ui.anim.Motion
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.debounce
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aihot.data.NewsCategory
import com.example.aihot.data.NewsItem
import com.example.aihot.ui.DateGroupHeader
import com.example.aihot.ui.EmptyState
import com.example.aihot.ui.ErrorState
import com.example.aihot.ui.ItemsViewModel
import com.example.aihot.ui.NewsCard
import com.example.aihot.ui.UiState
import com.example.aihot.ui.dayKeyOf
import com.example.aihot.ui.components.BottomBarReservedHeight
import com.example.aihot.ui.components.NewsCardSkeletonList
import kotlinx.coroutines.launch

/** 一天的分组(日期 key + 该天的条目)。空 key 表示不分天(搜索模式)。 */
private data class GroupedDay(val dayKey: String, val items: List<NewsItem>)

/** 行间 hairline 分隔线 —— 左侧缩进对齐右栏(避开时间列)。 */
@Composable
private fun NewsRowDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = 72.dp, end = 18.dp)
    )
}

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
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val showBackToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex >= 2 }
    }

    // 切换筛选(分类/模式/搜索词)时,把列表滚回顶部。
    // 数据会由 ViewModel 重新拉取,但 LazyListState 自身不会复位 —— 若不手动复位,
    // 新列表会停在旧分类滑到的位置,甚至可能直接命中"接近底部"误触发翻页。
    LaunchedEffect(filter) {
        listState.scrollToItem(0)
    }

    // 重击当前 tab(reselectSignal 递增):滚回顶部并刷新。
    LaunchedEffect(reselectSignal) {
        if (reselectSignal > 0) {
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
                        if (data.isEmpty()) {
                            EmptyState(
                                title = if (filter.isSearching) "未找到相关内容" else "暂无内容",
                                subtitle = if (filter.isSearching) "试试换个关键词" else null
                            )
                        } else {
                            // 按天分组(本地时区),保持原列表顺序。
                            // 仅当未在搜索状态时分组 —— 搜索结果跨天聚合意义不大,且更紧凑。
                            val grouped = remember(data, filter.isSearching) {
                                if (filter.isSearching) {
                                    listOf(GroupedDay("", data))
                                } else {
                                    data.groupBy { dayKeyOf(it.publishedAt) }
                                        .map { (k, items) -> GroupedDay(k, items) }
                                }
                            }
                            PullToRefreshBox(
                                isRefreshing = isRefreshing,
                                onRefresh = {
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
                                            onSelect = { vm.setCategory(it) }
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
                                            onClick = { onItemClick(item) }
                                        )
                                        if (item.id != group.items.last().id) {
                                            NewsRowDivider()
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
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "返回顶部")
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
                    "加载中…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            done -> Text(
                "已加载全部",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CategoryChips(
    selected: NewsCategory?,
    onSelect: (NewsCategory?) -> Unit
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
                label = { Text("全部") },
                // 完全圆角(药丸),对齐设计系统的 pill 形标签
                shape = RoundedCornerShape(50),
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
                    label = { Text(cat.zh) },
                    shape = RoundedCornerShape(50),
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
        }
    }
}
