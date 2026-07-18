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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.ThumbUp
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
import com.example.aihot.data.LinuxDoTopic
import com.example.aihot.data.source.SourceMode
import com.example.aihot.ui.EmptyState
import com.example.aihot.ui.ErrorState
import com.example.aihot.ui.LinuxDoHotViewModel
import com.example.aihot.ui.UiState
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.AppTopBarDefaults
import com.example.aihot.ui.components.ListUpdateTimeHeader
import com.example.aihot.ui.components.NewsCardSkeletonList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LinuxDo 热榜全屏页面(「更多」tab 二级页)。
 *
 * 视觉对齐 [GitHubTrendingScreen]:
 *  - 顶栏:返回箭头 + 「LinuxDo 热榜」;列表顶部居中「上次刷新 N 分钟前」
 *    (ListUpdateTimeHeader,始终实时模式,与其余 4 源同一位置)
 *  - 列表:置顶帖 📌 徽章 / 非置顶排名徽章(1-3 primary 强调)
 *    + 标题 + 摘要 + 作者 + meta(标签 chip · 👁浏览 · 💬回复 · ❤️点赞 · 时间)
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
                is UiState.Loading -> NewsCardSkeletonList(count = 8)
                is UiState.Error -> ErrorState(
                    message = s.message,
                    onRetry = { vm.forceRefresh() }
                )
                is UiState.Success -> {
                    val topics = s.data
                    if (topics.isEmpty()) {
                        EmptyState(title = "暂无内容")
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
 *  - 左侧徽章:置顶帖 📌(primary),非置顶排名编号(1-3 实心 primary,其余弱底)
 *  - 中间信息:标题(加粗)+ 摘要(2 行弱色,可空)+ 作者(头像 + 名字)
 *  - 底部 meta:标签 chip(可空) · 👁浏览 · 💬回复 · ❤️点赞 · 相对时间
 */
@Composable
private fun LinuxDoRow(
    topic: LinuxDoTopic,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val topRank = topic.rank in 1..3
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
        // 左侧徽章:置顶 📌 / 排名(1-3 实心 primary)
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(
                    when {
                        topic.pinned -> cs.tertiaryContainer
                        topRank -> cs.primary
                        else -> cs.surfaceContainerHigh
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (topic.pinned) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = "置顶",
                    tint = cs.onTertiaryContainer,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Text(
                    text = topic.rank.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (topRank) cs.onPrimary else cs.onSurfaceVariant
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            // ① 标题
            Text(
                text = topic.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
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

            // ③ 作者:头像 + 名字(头像缺失时回退为空,不占位)
            if (topic.authorName.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (topic.avatarUrl.isNotBlank()) {
                        AsyncImage(
                            model = topic.avatarUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(16.dp)
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

            // ④ meta:标签 chip(前 2 个) · 👁浏览 · 💬回复 · ❤️点赞 · 时间
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 标签 chip(取前 2 个,每个限宽避免吃掉整行)
                topic.tags.take(2).forEach { tag ->
                    TagChip(tag = tag)
                }
                CountBadge(
                    icon = Icons.Filled.RemoveRedEye,
                    text = formatCount(topic.views)
                )
                CountBadge(
                    icon = Icons.AutoMirrored.Filled.Comment,
                    text = formatCount(topic.replyCount)
                )
                if (topic.likeCount > 0) {
                    CountBadge(
                        icon = Icons.Filled.ThumbUp,
                        text = formatCount(topic.likeCount)
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
            .clip(RoundedCornerShape(50))
            .background(cs.secondaryContainer.copy(alpha = 0.6f))
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

/** 计数徽章:icon + 文本,弱色。 */
@Composable
private fun CountBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** 大数字缩写:< 1000 原样,1.2k / 12k / 1.2m。 */
private fun formatCount(n: Int): String = when {
    n < 1000 -> n.toString()
    n < 1_000_000 -> {
        if (n < 10_000) "${"%.1f".format(n / 1000.0)}k"
        else "${n / 1000}k"
    }
    else -> "${"%.1f".format(n / 1_000_000.0)}m"
}

/** 相对时间:「刚刚 / N 分钟前 / N 小时前 / N 天前 / 超过 7 天显日期」。 */
private fun formatRelative(tsMillis: Long): String {
    val diff = System.currentTimeMillis() - tsMillis
    val minutes = diff / 60_000L
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 60 * 24 -> "${minutes / 60}小时前"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}天前"
        else -> SimpleDateFormat("MM-dd", Locale.CHINA).format(Date(tsMillis))
    }
}
