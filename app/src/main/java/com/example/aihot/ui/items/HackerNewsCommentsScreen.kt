package com.example.aihot.ui.items

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import com.example.aihot.ui.anim.Motion
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aihot.data.HackerNewsStory
import com.example.aihot.ui.EmptyState
import com.example.aihot.ui.ErrorState
import com.example.aihot.ui.FlatComment
import com.example.aihot.ui.HackerNewsCommentsViewModel
import com.example.aihot.ui.Node
import com.example.aihot.ui.UiState
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.NewsCardSkeletonList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HackerNews 评论页 —— 点击某条 story 后进入。
 *
 * 布局(LazyColumn):
 *  1. header:story 标题 + meta(赞 · 评论数 · 相对时间)+ 「查看原文」链接行(url 非空时)
 *  2. 评论树(懒加载):默认仅展示一级评论;每条有子评论的评论显示展开箭头,
 *     点击展开时才按需拉取下一层([HackerNewsCommentsViewModel.toggle])。
 *     按 [FlatComment.depth] 缩进,每层一道细竖线引导层级。
 *
 * 评论正文为 HTML(`<p>`/`<a>`/`<i>` 等),用 [AnnotatedString.fromHtml] 渲染。
 *
 * @param onBack 返回回调
 * @param onOpenUrl (url, title) 回调,「查看原文」复用全局 openUrl 打开 WebView
 */
@Composable
fun HackerNewsCommentsScreen(
    story: HackerNewsStory,
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
    vm: HackerNewsCommentsViewModel = viewModel(key = "hn-comments-${story.id}")
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // 进入页面时触发首次加载(仅一级评论)。
    LaunchedEffect(story.id) { vm.load(story) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "评论",
                titleFontSize = 20.sp,
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // ① header:story 标题 + meta + 查看原文链接
                item(key = "story-header") {
                    StoryHeader(story = story, onOpenUrl = onOpenUrl)
                }

                // ② 评论列表(状态驱动)
                when (val s = state) {
                    is UiState.Loading -> item(key = "loading") {
                        NewsCardSkeletonList(count = 6, modifier = Modifier.fillMaxSize())
                    }
                    is UiState.Error -> item(key = "error") {
                        ErrorState(message = s.message, onRetry = { vm.retry(story) })
                    }
                    is UiState.Success -> {
                        if (s.data.isEmpty()) {
                            item(key = "empty") {
                                EmptyState(
                                    title = "暂无评论",
                                    icon = Icons.AutoMirrored.Filled.Comment
                                )
                            }
                        } else {
                            items(items = s.data, key = { it.key }) { flat ->
                                CommentRow(
                                    flat = flat,
                                    onToggle = { vm.toggle(it) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * story 头部:标题 + meta 行 + 查看原文链接行(样式对齐 NewsDetailScreen)。
 */
@Composable
private fun StoryHeader(
    story: HackerNewsStory,
    onOpenUrl: (String, String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(16.dp))
        // 标题
        if (story.title.isNotBlank()) {
            Text(
                text = story.title,
                style = MaterialTheme.typography.headlineSmall,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                lineHeight = 26.sp
            )
        }
        // meta:赞 · 评论数 · 相对时间
        val meta = buildString {
            append("${story.score} 赞")
            if (story.descendants > 0) append(" · ${story.descendants} 评论")
            if (story.time > 0L) append(" · ${formatRelativeTime(story.time)}")
        }
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant
            )
        }
        // 查看原文链接行(url 为空时是 Ask HN 等站内帖,展示讨论页)
        Spacer(Modifier.height(14.dp))
        LinkRow(
            url = story.targetUrl,
            label = if (story.url.isNotBlank()) "查看原文" else "查看讨论",
            title = story.title.ifBlank { "加载中…" },
            onOpenUrl = onOpenUrl
        )
        Spacer(Modifier.height(8.dp))
        // 评论区分隔
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(cs.outlineVariant)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "评论",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurface
        )
    }
}

/** 「查看原文 / 查看讨论」链接行 —— 扁平,与 NewsDetailScreen.LinkRow 同构。 */
@Composable
private fun LinkRow(
    url: String,
    label: String,
    title: String,
    onOpenUrl: (String, String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = { onOpenUrl(url, title) }
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Article,
            contentDescription = null,
            tint = cs.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "打开",
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(16.dp).rotate(180f)
        )
    }
}

/**
 * 单条评论行:展开切换 + 按 depth 缩进(每层一道竖线)+ 作者 + 时间 + HTML 正文。
 *
 * - 有子评论([FlatComment.hasKids]):点击切换展开/折叠;展开时懒加载子层
 * - 加载中:右侧小转圈 + "加载中"
 * - 加载失败:显示错误文案,再次点击可重试(折叠后展开)
 *
 * @param flat 铺平后的评论节点(含层级与展开态)
 * @param onToggle 点击展开/折叠回调
 */
@Composable
private fun CommentRow(
    flat: FlatComment,
    onToggle: (Node) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val node = flat.node
    val clickable = flat.hasKids
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 仅"有子评论"的行整体可点(切换展开);普通评论行不可点(避免误触)
            .then(
                if (clickable) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onClick = { onToggle(node) }
                ) else Modifier
            )
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp)
    ) {
        // 层级竖线:每层一道细竖线 + 间隙,直观表达父子关系
        repeat(flat.depth) {
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .width(1.5.dp)
                    .height(20.dp)
                    .background(cs.outlineVariant)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            // 作者行:作者 · 时间 …… 展开箭头/加载状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                val header = buildString {
                    if (node.comment.by.isNotBlank()) append(node.comment.by)
                    if (node.comment.time > 0L) {
                        if (isNotEmpty()) append(" · ")
                        append(formatRelativeTime(node.comment.time))
                    }
                    if (node.comment.dead) {
                        if (isNotEmpty()) append(" · ")
                        append("[已折叠]")
                    }
                    // 子评论数提示(仅未展开时显示,展开后子评论已可见)
                    if (flat.hasKids && !flat.expanded) {
                        if (isNotEmpty()) append(" · ")
                        append("${node.comment.kids.size} 回复")
                    }
                }
                if (header.isNotEmpty()) {
                    Text(
                        text = header,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (node.comment.dead) cs.onSurfaceVariant else cs.primary,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                Spacer(Modifier.weight(1f))
                // 右侧:展开切换指示(有子评论时)
                if (flat.hasKids) {
                    if (flat.childrenLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = cs.onSurfaceVariant
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (flat.expanded) "收起" else "展开",
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(if (flat.expanded) 90f else 0f)
                        )
                    }
                }
            }
            // HTML 正文(dead 评论弱化)
            if (node.comment.text.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                val linkColor = cs.primary
                val annotated = remember(node.comment.text, linkColor) {
                    AnnotatedString.fromHtml(
                        htmlString = node.comment.text,
                        linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor, fontWeight = FontWeight.Medium))
                    )
                }
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (node.comment.dead) cs.onSurfaceVariant else cs.onSurface,
                    lineHeight = 21.sp
                )
            }
            // 子评论加载失败提示(展开后展示)
            AnimatedVisibility(
                visible = flat.expanded && flat.childrenError != null,
                enter = expandVertically(tween(Motion.SHORT, easing = Motion.EmphasizedDecel)) +
                    fadeIn(tween(Motion.SHORT, easing = Motion.EmphasizedDecel)),
                exit = shrinkVertically(tween(Motion.SHORT, easing = Motion.EmphasizedAccel)) +
                    fadeOut(tween(Motion.SHORT, easing = Motion.EmphasizedAccel))
            ) {
                Text(
                    text = "加载回复失败:${flat.childrenError}",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** 把 Unix 秒级时间戳转成相对时间(如 "3 小时前")。与 HackerNewsScreen 同实现。 */
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
