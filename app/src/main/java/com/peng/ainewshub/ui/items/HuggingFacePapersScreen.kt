package com.peng.ainewshub.ui.items

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ThumbUp
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import com.peng.ainewshub.R
import com.peng.ainewshub.data.HuggingFacePaper
import com.peng.ainewshub.data.AiConfig
import com.peng.ainewshub.ui.HuggingFacePapersViewModel
import com.peng.ainewshub.ui.TranslationState
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.InlineTranslateButton
import com.peng.ainewshub.ui.components.TranslatedText
import com.peng.ainewshub.ui.components.TranslateConfigMissingEffect
import com.peng.ainewshub.ui.components.RankBadge
import com.peng.ainewshub.ui.components.RowDividerIfNeeded
import com.peng.ainewshub.ui.components.SourceListScaffold
import com.peng.ainewshub.ui.components.StatBadge
import com.peng.ainewshub.ui.components.formatCount
import com.peng.ainewshub.ui.components.updateTimeHeader
import com.peng.ainewshub.ui.theme.AppText
import androidx.compose.material3.ExperimentalMaterial3Api

/**
 * HuggingFace Trending Papers 全屏页面(「更多」tab 二级页)。
 *
 * 视觉对齐 [GitHubTrendingScreen] / [StormzhangAiNewsScreen]:
 *  - 顶栏:返回箭头 + 「HuggingFace Paper Trending」+ 右上「上次刷新 N 分钟前」
 *  - 列表:排名徽章([RankBadge] 统一分档)+ 论文标题(主)+ 摘要(辅,弱色)
 *    + upvotes(热度主指标,primary 强调)· 发布日期 · 作者
 *
 * 交互:
 *  - 点击单条 → 内置 WebView 打开论文页([onOpenUrl])
 *  - 加载中:shimmer 骨架;失败:错误态 + 重试;空:空状态;下拉刷新走 forceRefresh
 *  - 整体翻译:翻译开关开且配置就绪时,标题行出现「译」按钮(见 [InlineTranslateButton]),
 *    标题+摘要合并一次翻译,译文块整体显示在摘要之后
 *
 * @param onBack 返回回调
 * @param onOpenUrl 点击论文打开内置 WebView
 * @param onOpenSettings 配置未就绪时点「译」引导跳设置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuggingFacePapersScreen(
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    // 滚动状态由 MainActivity 按 Page 持有:进 WebView 返回后保持位置
    listState: LazyListState,
    vm: HuggingFacePapersViewModel = viewModel(key = "huggingface_papers")
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lastRefreshAt by vm.lastRefreshAt.collectAsStateWithLifecycle()
    val sourceMode by vm.sourceMode.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val translationStates by vm.translationStates.collectAsStateWithLifecycle()
    val config by vm.configFlow.collectAsStateWithLifecycle(initialValue = AiConfig())
    val snackbarHostState = remember { SnackbarHostState() }

    // 配置未就绪提示:点「译」后若 state 变成 CONFIG_MISSING,弹一次引导
    TranslateConfigMissingEffect(translationStates, snackbarHostState, onOpenSettings)

    SourceListScaffold(
        title = stringResource(R.string.source_title_huggingface),
        onBack = onBack,
        state = state,
        isRefreshing = isRefreshing,
        onForceRefresh = { vm.forceRefresh() },
        listState = listState,
        snackbarHostState = snackbarHostState
    ) {
        val papers = (state as UiState.Success).data
        updateTimeHeader(sourceMode, lastRefreshAt)
        itemsIndexed(items = papers, key = { _, paper -> paper.id }) { index, paper ->
            PaperRow(
                item = paper,
                translateEnabled = config.translateEnabled,
                translationState = translationStates[paper.id] ?: TranslationState.Idle,
                onClick = { onOpenUrl(paper.url, paper.title) },
                onTranslate = { vm.translatePaper(paper) }
            )
            RowDividerIfNeeded(index, papers.size)
        }
    }
}

/**
 * 单条论文行:序号徽章 + 标题(主)+ 摘要(辅)+ upvotes · 发布日期 · 作者。
 *
 * 三层信息:
 *  1. 标题:加粗正文,最多三行(论文标题是本条主信息);翻译开关开时行末带「译」按钮
 *  2. 摘要:弱色小字,最多两行(一句话概述,辅助参考);部分条目无此行
 *  2.5 整体译文:标题+摘要合并翻译的中文译文块,显示在摘要之后(翻译开关开且已翻译时)
 *  3. meta:upvotes(primary 强调,热度主指标)+ 发布日期 + 作者
 *
 * @param item 论文数据(rank 即为页面排名)
 */
@Composable
private fun PaperRow(
    item: HuggingFacePaper,
    translateEnabled: Boolean,
    translationState: TranslationState,
    onClick: () -> Unit,
    onTranslate: () -> Unit
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
        // 序号徽章:全 App 统一 RankBadge(同 GitHubTrendingRow)
        RankBadge(rank = item.rank)

        Column(modifier = Modifier.weight(1f)) {
            // ① 标题(主):加粗,最多三行;翻译开关开时行末带「译」按钮
            var translationCollapsed by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = item.title,
                    style = AppText.titleCompact,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (translateEnabled) {
                    Spacer(Modifier.width(6.dp))
                    InlineTranslateButton(
                        state = translationState,
                        collapsed = translationCollapsed,
                        onToggleCollapse = { translationCollapsed = !translationCollapsed },
                        onTranslate = onTranslate
                    )
                }
            }

            // ② 摘要(辅):弱色小字,最多两行;部分条目无此行则跳过
            if (item.summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.summary,
                    style = AppText.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ②.5 整体译文(标题+摘要合并翻译):显示在摘要之后、meta 之前。
            //   仅翻译开关开且已翻译且未折叠时渲染。译文为一段连续中文,弱色小字。
            if (translateEnabled && translationState is TranslationState.Success && !translationCollapsed) {
                Spacer(Modifier.height(4.dp))
                TranslatedText(translated = translationState.translated)
            }

            // ③ meta:upvotes(primary 强调)· 发布日期 · 作者
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // upvotes 是热度主指标,用 primary 强调,让用户一眼看到「为什么上趋势」
                StatBadge(
                    icon = Icons.Filled.ThumbUp,
                    value = formatCount(item.upvotes),
                    tint = cs.primary,
                    fontWeight = FontWeight.SemiBold
                )
                if (item.published.isNotBlank()) {
                    StatBadge(
                        icon = Icons.Filled.CalendarMonth,
                        value = item.published
                    )
                }
                if (item.authors.isNotBlank()) {
                    StatBadge(
                        icon = Icons.Filled.Groups,
                        value = item.authors
                    )
                }
            }
        }
    }
}
