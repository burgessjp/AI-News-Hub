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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.peng.ainewshub.data.LinuxDoTopic
import com.peng.ainewshub.data.source.SourceMode
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.LinuxDoHotViewModel
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.ListUpdateTimeHeader
import com.peng.ainewshub.ui.components.RankBadge
import com.peng.ainewshub.ui.components.RankRowSkeletonList
import com.peng.ainewshub.ui.components.StatBadge
import com.peng.ainewshub.ui.components.formatCount
import com.peng.ainewshub.ui.components.formatRelative
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

/**
 * LinuxDo 热榜全屏页面(「更多」tab 二级页)。
 *
 * 视觉对齐 [GitHubTrendingScreen]:
 *  - 顶栏:返回箭头 + 「LinuxDo 热榜」;列表顶部居中「上次刷新 N 分钟前」
 *    (ListUpdateTimeHeader,始终实时模式,与其余 4 源同一位置)
 *  - 列表:置顶帖图钉徽章 / 非置顶排名徽章([RankBadge] 统一分档)
 *    + 标题 + 摘要 + 作者 + meta(标签 chip · 浏览 · 回复 · 点赞 · 时间)
 *
 * 交互:
 *  - 点击单条 → 内置 WebView 打开话题([onOpenUrl])
 *  - 加载中:shimmer 骨架;失败:错误态 + 重试;空:空状态;下拉刷新走 forceRefresh
 *
 * @param onBack 返回回调
 * @param onOpenUrl 点击话题打开内置 WebView
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinuxDoHotScreen(
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
    // 滚动状态由 MainActivity 按 Page 持有:进 WebView 返回后保持位置
    listState: LazyListState,
    vm: LinuxDoHotViewModel = viewModel(key = "linuxdo_hot")
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lastRefreshAt by vm.lastRefreshAt.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "LinuxDo 热榜",
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is UiState.Loading -> RankRowSkeletonList(count = 8)
                is UiState.Error -> ErrorState(
                    message = s.message,
                    onRetry = { vm.forceRefresh() }
                )
                is UiState.Success -> {
                    val topics = s.data
                    if (topics.isEmpty()) {
                        // 数据缺失空态:给刷新恢复路径
                        EmptyState(
                            title = "暂无内容",
                            subtitle = "下拉或点下方按钮刷新看看",
                            icon = Icons.Outlined.Inventory2,
                            actionLabel = "刷新一下",
                            onAction = { vm.forceRefresh() }
                        )
                    } else {
                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = { vm.forceRefresh() },
                        ) {
                            LinuxDoList(
                                topics = topics,
                                listState = listState,
                                lastRefreshAt = lastRefreshAt,
                                onClick = { topic -> onOpenUrl(topic.url, topic.title) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinuxDoList(
    topics: List<LinuxDoTopic>,
    listState: LazyListState,
    lastRefreshAt: Long?,
    onClick: (LinuxDoTopic) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // 列表顶部居中显示「上次刷新 N 分钟前」—— 与其余 4 源同一位置
        // (LinuxDo 始终实时,用 LIVE 文案),顶栏右上角不再重复显示。
        item { ListUpdateTimeHeader(SourceMode.LIVE, lastRefreshAt) }
        itemsIndexed(
            items = topics,
            key = { _, topic -> topic.url }
        ) { index, topic ->
            LinuxDoRow(topic = topic, onClick = { onClick(topic) })
            if (index != topics.lastIndex) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 60.dp, end = 18.dp)
                        .height(0.5.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
    }
}

/**
 * 单条话题行:
 *  - 左侧徽章:置顶帖图钉(tertiaryContainer),非置顶排名编号([RankBadge] 统一分档)
 *  - 中间信息:标题(加粗)+ 摘要(2 行弱色,可空)+ 作者(头像 + 名字)
 *  - 底部 meta:标签 chip(可空) · 浏览 · 回复 · 点赞 · 相对时间
 */
@Composable
private fun LinuxDoRow(
    topic: LinuxDoTopic,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(),
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 左侧徽章:置顶图钉 / 排名(全 App 统一 RankBadge)
        if (topic.pinned) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(cs.tertiaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = "置顶",
                    tint = cs.onTertiaryContainer,
                    modifier = Modifier.size(14.dp)
                )
            }
        } else {
            RankBadge(rank = topic.rank)
        }

        Column(modifier = Modifier.weight(1f)) {
            // ① 标题
            Text(
                text = topic.title,
                style = AppText.titleCompact,
                fontWeight = FontWeight.SemiBold,
                color = if (topic.closed) cs.onSurfaceVariant else cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // ② 摘要(可空,最多两行弱色)
            if (topic.excerpt.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = topic.excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ③ 作者:头像 + 名字(头像 20dp 圆形,源识别记忆点;缺失时回退为空,不占位)
            if (topic.authorName.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (topic.avatarUrl.isNotBlank()) {
                        AsyncImage(
                            model = topic.avatarUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(cs.surfaceContainerHigh)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = topic.authorName,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // ④ meta:标签 chip(前 2 个) · 浏览 · 回复 · 点赞 · 时间
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 标签 chip(取前 2 个,每个限宽避免吃掉整行)
                topic.tags.take(2).forEach { tag ->
                    TagChip(tag = tag)
                }
                StatBadge(
                    icon = Icons.Filled.RemoveRedEye,
                    value = formatCount(topic.views)
                )
                StatBadge(
                    icon = Icons.AutoMirrored.Filled.Comment,
                    value = formatCount(topic.replyCount)
                )
                if (topic.likeCount > 0) {
                    StatBadge(
                        icon = Icons.Filled.ThumbUp,
                        value = formatCount(topic.likeCount)
                    )
                }
                if (topic.createdAtMs > 0) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = formatRelative(topic.createdAtMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** 标签 chip:浅底圆角小标签,限宽省略(避免长标签吃掉整行 meta)。 */
@Composable
private fun TagChip(tag: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(cs.secondaryContainer.copy(alpha = AppAlpha.chipOverlay))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
