package com.peng.ainewshub.ui.items
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.peng.ainewshub.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.data.HackerNewsStory
import com.peng.ainewshub.data.AiConfig
import com.peng.ainewshub.ui.HackerNewsViewModel
import com.peng.ainewshub.ui.TranslationState
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.InlineTranslateButton
import com.peng.ainewshub.ui.components.TranslatedText
import com.peng.ainewshub.ui.components.TranslateConfigMissingEffect
import com.peng.ainewshub.ui.components.RankBadge
import com.peng.ainewshub.ui.components.RowDividerIfNeeded
import com.peng.ainewshub.ui.components.SourceListScaffold
import com.peng.ainewshub.ui.components.updateTimeHeader
import com.peng.ainewshub.ui.components.formatRelativeTime
import com.peng.ainewshub.ui.components.rememberReadUrls
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText
import androidx.compose.material3.ExperimentalMaterial3Api

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

    // 已读判定(打开 URL 命中浏览历史):驱动行内标题弱化
    val readUrls = rememberReadUrls()

    // 配置未就绪提示:点「译」后若 state 变成 CONFIG_MISSING,弹一次引导
    TranslateConfigMissingEffect(titleStates, snackbarHostState, onOpenSettings)

    SourceListScaffold(
        title = stringResource(R.string.source_title_hackernews),
        onBack = onBack,
        state = state,
        isRefreshing = isRefreshing,
        onForceRefresh = { vm.forceRefresh() },
        listState = listState,
        snackbarHostState = snackbarHostState
    ) {
        val stories = (state as UiState.Success).data
        updateTimeHeader(sourceMode, lastRefreshAt)
        itemsIndexed(items = stories, key = { _, story -> story.id }) { index, story ->
            HackerNewsRow(
                rank = index + 1,
                story = story,
                translateEnabled = config.translateEnabled,
                translationState = titleStates[story.id.toString()] ?: TranslationState.Idle,
                onClick = { onOpenComments(story) },
                onTranslate = { vm.translateTitle(story) },
                // 已读 = 点开过评论页(discussionUrl,进评论页时记录)或打开过原文
                isRead = story.discussionUrl in readUrls || story.targetUrl in readUrls
            )
            RowDividerIfNeeded(index, stories.size)
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
 * @param isRead 已读(打开 URL 命中浏览历史)时标题降透明弱化
 */
@Composable
private fun HackerNewsRow(
    rank: Int,
    story: HackerNewsStory,
    translateEnabled: Boolean,
    translationState: TranslationState,
    onClick: () -> Unit,
    onTranslate: () -> Unit,
    isRead: Boolean = false
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
                    color = if (isRead) cs.onSurface.copy(alpha = AppAlpha.readDim) else cs.onSurface,
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
