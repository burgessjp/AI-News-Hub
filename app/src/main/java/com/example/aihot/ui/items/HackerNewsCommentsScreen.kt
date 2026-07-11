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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.aihot.ui.TranslationState
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
    onOpenSettings: () -> Unit,
    vm: HackerNewsCommentsViewModel = viewModel(key = "hn-comments-${story.id}")
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val config by vm.configFlow.collectAsStateWithLifecycle(initialValue = com.example.aihot.data.TranslationConfig())
    val titleStates by vm.titleStates.collectAsStateWithLifecycle()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    // 进入页面时触发首次加载(仅一级评论)。
    LaunchedEffect(story.id) { vm.load(story) }

    // 配置未就绪提示:标题或任一评论翻译触发 CONFIG_MISSING 时,弹一次引导。
    // 用 remember 记住「已提示过」,避免评论列表重组时反复弹。
    var configMissingNotified by remember { mutableStateOf(false) }
    LaunchedEffect(titleStates, state) {
        if (configMissingNotified) return@LaunchedEffect
        val titleMissing = (titleStates[story.id] as? TranslationState.Error)?.message == TranslationState.CONFIG_MISSING
        val commentMissing = (state as? UiState.Success)?.data
            ?.any { (it.translationState as? TranslationState.Error)?.message == TranslationState.CONFIG_MISSING }
            ?: false
        if (titleMissing || commentMissing) {
            configMissingNotified = true
            val r = snackbarHostState.showSnackbar(
                message = "请先在 设置 → 翻译 中配置 API",
                actionLabel = "去设置"
            )
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) onOpenSettings()
        }
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
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
                    StoryHeader(
                        story = story,
                        translateEnabled = config.enabled,
                        translationState = titleStates[story.id] ?: TranslationState.Idle,
                        onTranslate = { vm.translateTitle(story) },
                        onOpenUrl = onOpenUrl
                    )
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
                            itemsIndexed(items = s.data, key = { _, it -> it.key }) { index, flat ->
                                // 一级评论之间加分割线:首条不加(避免与上方"评论"标题区
                                // 分隔线重复),其余每条评论顶部一道细线。
                                if (flat.depth == 0 && index > 0) {
                                    CommentDivider()
                                }
                                CommentRow(
                                    flat = flat,
                                    translateEnabled = config.enabled,
                                    onToggle = { vm.toggle(it) },
                                    onTranslate = { vm.translateComment(it) }
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
    translateEnabled: Boolean,
    translationState: TranslationState,
    onTranslate: () -> Unit,
    onOpenUrl: (String, String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var titleCollapsed by remember { mutableStateOf(false) }
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
        // meta:赞 · 评论数 · 相对时间(+ 翻译开关开时的「译」按钮内联在末尾)
        val meta = buildString {
            append("${story.score} 赞")
            if (story.descendants > 0) append(" · ${story.descendants} 评论")
            if (story.time > 0L) append(" · ${formatRelativeTime(story.time)}")
        }
        if (meta.isNotEmpty() || translateEnabled) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
                    )
                }
                if (translateEnabled && story.title.isNotBlank()) {
                    if (meta.isNotEmpty()) Spacer(Modifier.width(10.dp))
                    InlineTranslateButton(
                        state = translationState,
                        collapsed = titleCollapsed,
                        onToggleCollapse = { titleCollapsed = !titleCollapsed },
                        onTranslate = onTranslate
                    )
                }
            }
        }
        // 标题译文(已翻译且未折叠时):弱色小字,显示在 meta 下方
        if (translateEnabled && story.title.isNotBlank() &&
            translationState is TranslationState.Success && !titleCollapsed
        ) {
            Spacer(Modifier.height(4.dp))
            TranslatedText(translated = translationState.translated)
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
 * 单条评论行 —— 作者 + 时间 + HTML 正文 + 「查看 N 条回复」按钮。
 *
 * 交互(Reddit/HN 风格):正文区**不可点**,避免正文里的链接和展开操作打架;
 * 「查看回复」做成正文下方的独立按钮,点击它才展开/折叠子评论。按钮三态:
 *  - 默认:「▾ 查看 N 条回复」(已展开则「收起」)
 *  - 加载中:小转圈 + 「加载中」
 *  - 失败:「加载失败,点击重试」
 *
 * 按 [FlatComment.depth] 缩进,每层一道竖线引导层级。
 *
 * @param flat 铺平后的评论节点(含层级与展开态)
 * @param translateEnabled 翻译开关是否开(控制译块是否渲染)
 * @param onToggle 点击「查看回复」按钮回调
 * @param onTranslate 点击「译」按钮回调,翻译本条评论
 */
@Composable
private fun CommentRow(
    flat: FlatComment,
    translateEnabled: Boolean,
    onToggle: (Node) -> Unit,
    onTranslate: (Node) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val node = flat.node
    var commentCollapsed by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 正文区不挂 clickable —— 仅靠下方「查看回复」按钮触发展开,
            // 避免点正文链接时误触展开/折叠。
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
            // 作者行:作者(主色加粗) · 时间(弱色) · dead chip ·(可选)译按钮
            val hasText = node.comment.text.isNotBlank()
            CommentMeta(
                node = node,
                translateState = if (translateEnabled && hasText) flat.translationState else null,
                collapsed = commentCollapsed,
                onToggleCollapse = { commentCollapsed = !commentCollapsed },
                onTranslate = { onTranslate(node) }
            )
            // HTML 正文(dead 评论弱化)
            if (hasText) {
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
                // 译文(已翻译且未折叠时):纯文本追加在原文 HTML 下方,原文链接/格式完整保留
                if (translateEnabled && flat.translationState is TranslationState.Success && !commentCollapsed) {
                    Spacer(Modifier.height(4.dp))
                    TranslatedText(translated = flat.translationState.translated)
                }
            }
            // 「查看回复」按钮(有子评论时) —— 右对齐,紧贴正文下方,三态
            if (flat.hasKids) {
                RepliesToggleButton(
                    expanded = flat.expanded,
                    replyCount = node.comment.kids.size,
                    loading = flat.childrenLoading,
                    error = flat.childrenError,
                    onClick = { onToggle(node) }
                )
            }
        }
    }
}

/**
 * 作者元信息行:作者名(主色 SemiBold) · 相对时间(弱色) · dead chip ·(可选)译按钮。
 * 分行承载,避免 buildString 把信息层级糊在一起。「译」按钮内联在时间后,
 * 与列表页/评论页标题的「译」按钮位置风格统一。
 *
 * @param translateState 翻译状态;null 表示不渲染译按钮(翻译开关关)
 * @param collapsed 译文折叠态(Success 时切「收起/显示译文」)
 * @param onToggleCollapse / [onTranslate] 按钮回调
 */
@Composable
private fun CommentMeta(
    node: Node,
    translateState: TranslationState? = null,
    collapsed: Boolean = false,
    onToggleCollapse: () -> Unit = {},
    onTranslate: () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (node.comment.by.isNotBlank()) {
            Text(
                text = node.comment.by,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = cs.primary
            )
        }
        if (node.comment.time > 0L) {
            if (node.comment.by.isNotBlank()) {
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant
                )
            }
            Text(
                text = formatRelativeTime(node.comment.time),
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant
            )
        }
        // dead/折叠评论:独立 chip,不再混进作者字符串
        if (node.comment.dead) {
            Spacer(Modifier.width(6.dp))
            AssistChipPreview(text = "已折叠")
        }
        // 「译」按钮内联在时间后(翻译开关开时)
        if (translateState != null) {
            Spacer(Modifier.width(10.dp))
            InlineTranslateButton(
                state = translateState,
                collapsed = collapsed,
                onToggleCollapse = onToggleCollapse,
                onTranslate = onTranslate
            )
        }
    }
}

/**
 * 「查看 N 条回复」按钮 —— 右对齐,紧贴正文下方,承载展开/加载/失败三态。
 *
 * 用 [TextButton] 而非裸 clickable:有明确的点击区与水波纹反馈,
 * 且不占用正文区,避免点正文链接时误触。
 *
 * @param expanded   当前是否展开(展开后文案变「收起」)
 * @param replyCount 直接子评论数
 * @param loading    子评论加载中
 * @param error      子评论加载失败信息(非 null 时显示「重试」)
 * @param onClick    点击回调
 */
@Composable
private fun ColumnScope.RepliesToggleButton(
    expanded: Boolean,
    replyCount: Int,
    loading: Boolean,
    error: String?,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        // 右对齐(贴近正文右侧)+ 收紧高度,减少与正文间的视觉空白
        modifier = Modifier
            .align(Alignment.End)
            .height(28.dp)
    ) {
        // 加载中:小转圈(功能性状态反馈,保留);其余态仅靠文字「查看/收起」区分
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = cs.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = when {
                loading -> "加载中"
                error != null -> "加载失败,点击重试"
                expanded -> "收起"
                else -> "查看 $replyCount 条回复"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (error != null) cs.error else cs.onSurfaceVariant
        )
    }
}

/** 不可点的小标签(用于 dead 评论「已折叠」等只读标记)。 */
@Composable
private fun AssistChipPreview(text: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(cs.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant
        )
    }
}

/**
 * 一级评论之间的分割条(Reddit 风格)。
 *
 * 关键:不要用「同色 surface 底 + 一根线」—— 线会融化在背景里看不见。
 * 改成一整条**有色色带**([surfaceContainer]),它自身就和内容区([surface]
 * 底)形成对比,一眼可辨。色带内不再画线,避免噪音。
 *
 * 高度按 Reddit 节奏取上下留白(约 9dp 量级),保证「这是一条新的一级评论」
 * 的呼吸感。
 */
@Composable
private fun CommentDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    )
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
