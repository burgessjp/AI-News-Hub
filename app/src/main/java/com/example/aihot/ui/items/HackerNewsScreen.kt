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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aihot.data.HackerNewsStory
import com.example.aihot.ui.EmptyState
import com.example.aihot.ui.ErrorState
import com.example.aihot.ui.HackerNewsViewModel
import com.example.aihot.ui.UiState
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.NewsCardSkeletonList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HackerNews 全屏页面(「更多」tab 二级页)。
 *
 * 视觉(对齐设计稿):
 *  - 顶栏:返回箭头 + 「HackerNews」标题
 *  - 列表:排名徽章(1-3 用 primary 强调,其余低对比)+ 标题 + 得票/评论数
 *
 * 交互:
 *  - 点击单条 → 打开该 story 的评论树页面
 *  - 加载中:shimmer 骨架;失败:错误态 + 重试;空:空状态
 *
 * @param onBack 返回回调
 * @param onOpenComments 点击 story 打开其评论树页面
 */
@Composable
fun HackerNewsScreen(
    onBack: () -> Unit,
    onOpenComments: (HackerNewsStory) -> Unit,
    vm: HackerNewsViewModel = viewModel(key = "hackernews")
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "HackerNews",
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
                    onRetry = { vm.refresh() }
                )
                is UiState.Success -> {
                    val stories = s.data
                    if (stories.isEmpty()) {
                        EmptyState(title = "暂无内容")
                    } else {
                        HackerNewsList(
                            stories = stories,
                            onClick = onOpenComments
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HackerNewsList(
    stories: List<HackerNewsStory>,
    onClick: (HackerNewsStory) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        itemsIndexed(
            items = stories,
            key = { _, story -> story.id }
        ) { index, story ->
            HackerNewsRow(
                rank = index + 1,
                story = story,
                onClick = { onClick(story) }
            )
            if (index != stories.lastIndex) {
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
 * 单条 story 行:序号徽章 + 标题 + 得票/评论数。
 *
 * @param rank 1 起的序号;1-3 用 primary 强调,其余低对比。
 */
@Composable
private fun HackerNewsRow(
    rank: Int,
    story: HackerNewsStory,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val topRank = rank <= 3
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(),
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 序号徽章:1-3 实心 primary,其余描边低对比
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (topRank) cs.primary else cs.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = rank.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (topRank) cs.onPrimary else cs.onSurfaceVariant
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            // 标题
            Text(
                text = story.title.ifBlank { "(无标题)" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )
            // 得票 · 评论数 · 发布时间
            val meta = buildString {
                append("${story.score} 赞")
                if (story.descendants > 0) append(" · ${story.descendants} 评论")
                if (story.time > 0L) {
                    append(" · ${formatRelativeTime(story.time)}")
                }
            }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 把 Unix 秒级时间戳转成相对时间(如 "3 小时前")。 */
private fun formatRelativeTime(unixSeconds: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - unixSeconds * 1000L
    val minutes = diff / 60_000L
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} 天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(unixSeconds * 1000L))
    }
}
