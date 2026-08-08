package com.peng.ainewshub.ui.items
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.peng.ainewshub.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.peng.ainewshub.data.source.SourceMode
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.data.HackerNewsStory
import com.peng.ainewshub.data.AiConfig
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.HackerNewsViewModel
import com.peng.ainewshub.ui.TranslationState
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.ListUpdateTimeHeader
import com.peng.ainewshub.ui.components.RankBadge
import com.peng.ainewshub.ui.components.RankRowSkeletonList
import com.peng.ainewshub.ui.components.formatRelativeTime
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

/**
 * HackerNews 全屏页面(「更多」tab 二级页)。
 *
 * 视觉(对齐设计稿):
 *  - 顶栏:返回箭头 + 「HackerNews」标题
 *  - 列表:排名徽章([RankBadge] 统一分档)+ 标题 + 得票/评论数
 *
 * 交互:
 *  - 点击单条 → 打开该 story 的评论树页面
 *  - 加载中:shimmer 骨架;失败:错误态 + 重试;空:空状态
 *  - 标题翻译:翻译开关开且配置就绪时,每行标题下出现「译」按钮(见 [TitleTranslationBlock])
 *
 * @param onBack 返回回调
 * @param onOpenComments 点击 story 打开其评论树页面
 * @param onOpenSettings 配置未就绪时点「译」引导跳设置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HackerNewsScreen(
    onBack: () -> Unit,
    onOpenComments: (HackerNewsStory) -> Unit,
    onOpenSettings: () -> Unit,
    // 滚动状态由 MainActivity 按 Page 持有:进评论页返回后保持位置
    listState: LazyListState,
    vm: HackerNewsViewModel = viewModel(key = "hackernews")
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lastRefreshAt by vm.lastRefreshAt.collectAsStateWithLifecycle()
    val sourceMode by vm.sourceMode.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val titleStates by vm.titleStates.collectAsStateWithLifecycle()
    val config by vm.configFlow.collectAsStateWithLifecycle(initialValue = AiConfig())
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 配置未就绪提示:点「译」后若 state 变成 CONFIG_MISSING,弹一次引导
    LaunchedEffect(titleStates) {
        titleStates.values.firstOrNull { it is TranslationState.Error && it.message == TranslationState.CONFIG_MISSING }
            ?.let {
                val r = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.common_translate_config_missing),
                    actionLabel = context.getString(R.string.common_go_settings)
                )
                if (r == SnackbarResult.ActionPerformed) onOpenSettings()
            }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = stringResource(R.string.source_title_hackernews),
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    // 「上次刷新」已移到列表顶部居中(见 ListUpdateTimeHeader),顶栏不再显示。
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
                    val stories = s.data
                    if (stories.isEmpty()) {
                        // 数据缺失空态(归档快照为空/实时无条目):给刷新恢复路径
                        EmptyState(
                            title = stringResource(R.string.common_empty),
                            subtitle = stringResource(R.string.common_refresh_hint),
                            icon = Icons.Outlined.Inventory2,
                            actionLabel = stringResource(R.string.common_refresh_once),
                            onAction = { vm.forceRefresh() }
                        )
                    } else {
                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = { vm.forceRefresh() },
                        ) {
                            HackerNewsList(
                                stories = stories,
                                listState = listState,
                                titleStates = titleStates,
                                translateEnabled = config.translateEnabled,
                                sourceMode = sourceMode,
                                fetchedAtMillis = lastRefreshAt,
                                onClick = onOpenComments,
                                onTranslate = { vm.translateTitle(it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HackerNewsList(
    stories: List<HackerNewsStory>,
    listState: LazyListState,
    titleStates: Map<Long, TranslationState>,
    translateEnabled: Boolean,
    sourceMode: SourceMode,
    fetchedAtMillis: Long?,
    onClick: (HackerNewsStory) -> Unit,
    onTranslate: (HackerNewsStory) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // 列表顶部居中显示数据时间(实时:上次刷新 N 分钟前;归档:数据更新时间绝对值)。
        // 顶栏右上角不再显示,统一到这里,两种模式同一位置。
        item { ListUpdateTimeHeader(sourceMode, fetchedAtMillis) }
        itemsIndexed(
            items = stories,
            key = { _, story -> story.id }
        ) { index, story ->
            HackerNewsRow(
                rank = index + 1,
                story = story,
                translateEnabled = translateEnabled,
                translationState = titleStates[story.id] ?: TranslationState.Idle,
                onClick = { onClick(story) },
                onTranslate = { onTranslate(story) }
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
 * 信息分层:
 *  1. 标题(最多两行,来源域名 tertiary 色括注末尾——源识别记忆点)+ 可选的「译」按钮与译文
 *  2. 作者 · 相对时间 · 得票 · 评论(单行,作者主色强调,其余弱色)
 *
 * @param rank 1 起的序号,徽章配色由 [RankBadge] 统一分档。
 */
@Composable
private fun HackerNewsRow(
    rank: Int,
    story: HackerNewsStory,
    translateEnabled: Boolean,
    translationState: TranslationState,
    onClick: () -> Unit,
    onTranslate: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var titleCollapsed by remember { mutableStateOf(false) }
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
        // 序号徽章:全 App 统一 RankBadge(1 名 tertiary / 2-3 tertiaryContainer / 其余低对比)
        RankBadge(rank = rank)

        Column(modifier = Modifier.weight(1f)) {
            // ① 标题 + 内联来源域名 (host) —— HN 原生风,域名 tertiary 色括注在末尾(源识别记忆点)
            val host = storyHost(story)
            val noTitle = stringResource(R.string.hn_no_title)
            val titleAnnotated = remember(story.title, host, cs.tertiary, noTitle) {
                buildAnnotatedString {
                    append(story.title.ifBlank { noTitle })
                    append("  ")
                    // 域名括注局部 SpanStyle:刻意比 titleCompact(14sp)小一档做弱化,局部样式不抽 token
                    withStyle(SpanStyle(color = cs.tertiary, fontSize = 12.sp)) {
                        append("($host)")
                    }
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = titleAnnotated,
                    style = AppText.titleCompact,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 「译」按钮内联在标题行末(域名后),翻译开关开时显示
                if (translateEnabled) {
                    Spacer(Modifier.width(6.dp))
                    InlineTranslateButton(
                        state = translationState,
                        collapsed = titleCollapsed,
                        onToggleCollapse = { titleCollapsed = !titleCollapsed },
                        onTranslate = onTranslate
                    )
                }
            }

            // ①.5 标题译文(仅翻译开关开且已翻译且未折叠时):弱色小字,显示在标题下方
            if (translateEnabled && translationState is TranslationState.Success && !titleCollapsed) {
                Spacer(Modifier.height(4.dp))
                TranslatedText(translated = translationState.translated)
            }

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
                val pointsLabel = pluralStringResource(R.plurals.hn_points, story.score, story.score)
                val commentsLabel = pluralStringResource(R.plurals.hn_comments, story.descendants, story.descendants)
                val rest = buildString {
                    if (story.time > 0L) {
                        append(formatRelativeTime(LocalContext.current, story.time))
                    }
                    append(" · $pointsLabel")
                    if (story.descendants > 0) append(" · $commentsLabel")
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
 * 内联「译」按钮 —— 跟在标题行末/meta 信息后,不占独立行。
 *
 * 文案随 [state] 变化:Idle→「译」;Loading→「翻译中…」;Success→「收起/显示译文」
 * (由 [collapsed] 决定);Error→「内容过短 / 翻译失败,重试」。CONFIG_MISSING 不渲染
 * (由调用方 snackbar 引导)。折叠态由调用方持有,使译文 Text 与按钮文案共享。
 *
 * @param collapsed 当前译文是否已折叠(Success 态用)
 * @param onToggleCollapse 切换折叠(Success 态点按钮)
 * @param onTranslate 触发翻译(Idle / Error 重试)
 */
@Composable
internal fun InlineTranslateButton(
    state: TranslationState,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onTranslate: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val text = when (state) {
        TranslationState.Idle -> stringResource(R.string.translate_label)
        TranslationState.Loading -> stringResource(R.string.items_translating)
        is TranslationState.Success -> if (collapsed) stringResource(R.string.items_show_translation) else stringResource(R.string.items_hide_translation)
        is TranslationState.Error -> when (state.message) {
            TranslationState.TOO_SHORT -> stringResource(R.string.error_too_short)
            TranslationState.CONFIG_MISSING -> return // 由 snackbar 引导,不渲染按钮
            else -> stringResource(R.string.items_translate_failed_retry)
        }
    }
    val enabled = state !is TranslationState.Loading
    val onClick: () -> Unit = when (state) {
        is TranslationState.Success -> onToggleCollapse
        else -> onTranslate
    }
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        modifier = Modifier.height(20.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) cs.primary.copy(alpha = AppAlpha.primaryEmphasis) else cs.onSurfaceVariant
        )
    }
}

/** 译文纯文本(弱色小字),供标题/评论译文区复用。 */
@Composable
internal fun TranslatedText(translated: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = translated,
        style = AppText.bodySmall,
        color = cs.onSurfaceVariant,
        modifier = modifier.fillMaxWidth()
    )
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
