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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
 * 单条 story 行:序号徽章 + 标题 + 作者/时间 + 得票/评论/来源域名(HN 原生风)。
 *
 * 三层信息:
 *  1. 标题(最多两行)
 *  2. 作者 · 相对时间(作者主色强调)
 *  3. 得票 ▲ · 评论 💬 · 来源域名 🌐(三栏均匀分布,icon + 文字)
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
        verticalAlignment = Alignment.Top,
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
            // ① 标题 + 内联来源域名 (host) —— HN 原生风,域名弱色括注在末尾
            val host = storyHost(story)
            val titleAnnotated = remember(story.title, host, cs.onSurfaceVariant) {
                buildAnnotatedString {
                    append(story.title.ifBlank { "(无标题)" })
                    append("  ")
                    withStyle(SpanStyle(color = cs.onSurfaceVariant, fontSize = 12.sp)) {
                        append("($host)")
                    }
                }
            }
            Text(
                text = titleAnnotated,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )
            // ② 作者 · 相对时间 · 得票 · 评论(单行,作者主色强调,其余弱色)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (story.by.isNotBlank()) {
                    Text(
                        text = story.by,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.primary,
                        maxLines = 1
                    )
                    // 作者与时间之间留一点呼吸间隔(其余项间用 · 分隔)
                    Spacer(Modifier.width(8.dp))
                }
                val rest = buildString {
                    if (story.time > 0L) {
                        append(formatRelativeTime(story.time))
                    }
                    append(" · ${story.score} 赞")
                    if (story.descendants > 0) append(" · ${story.descendants} 评论")
                }
                if (rest.isNotEmpty()) {
                    Text(
                        text = rest,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 提取 story 的来源域名(去掉 www. 前缀)。
 * 无 url 的站内帖(Ask HN 等)显示 HN 自身域名。
 */
private fun storyHost(story: HackerNewsStory): String {
    val raw = if (story.url.isNotBlank()) story.url else story.discussionUrl
    return runCatching {
        android.net.Uri.parse(raw).host
            ?.removePrefix("www.")
            ?: "news.ycombinator.com"
    }.getOrDefault("news.ycombinator.com")
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
