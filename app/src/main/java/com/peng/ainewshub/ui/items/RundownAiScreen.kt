package com.peng.ainewshub.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.ExperimentalMaterial3Api
import coil.compose.AsyncImage
import androidx.compose.ui.res.stringResource
import com.peng.ainewshub.R
import com.peng.ainewshub.data.AiConfig
import com.peng.ainewshub.data.RundownAiArticle
import com.peng.ainewshub.data.source.SourceMode
import com.peng.ainewshub.ui.RundownAiViewModel
import com.peng.ainewshub.ui.TranslationState
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.RankBadge
import com.peng.ainewshub.ui.components.RowDividerIfNeeded
import com.peng.ainewshub.ui.components.SourceListScaffold
import com.peng.ainewshub.ui.components.TranslateConfigMissingEffect
import com.peng.ainewshub.ui.components.InlineTranslateButton
import com.peng.ainewshub.ui.components.TranslatedText
import com.peng.ainewshub.ui.components.updateTimeHeader
import com.peng.ainewshub.ui.theme.AppText

/**
 * The Rundown AI 近况 newsletter 全屏页面(「更多」tab 二级页)。
 *
 * 视觉对齐 [ProductHuntScreen] / [StormzhangAiNewsScreen]:
 *  - 顶栏:返回箭头 + 「The Rundown AI」+ 列表顶部「上次更新」时间头
 *  - 列表:排名徽章([RankBadge] 统一分档)+ 封面图(40dp 方形圆角)
 *    + 标题(主,英文加粗)+ 副标题(辅,弱色 PLUS 工具/技巧)+ 作者段
 *
 * 交互:
 *  - 点击单条 → 内置 WebView 打开 beehiiv 文章页([RundownAiArticle.url])
 *  - 加载中:shimmer 骨架;失败:错误态 + 重试;空:空状态;下拉刷新走 forceRefresh
 *  - 整体翻译:翻译开关开且配置就绪时,标题行出现「译」按钮,title+subtitle 合并一次翻译
 *
 * 双模式源:LIVE 走 [com.peng.ainewshub.data.RundownAiRepository](实时,带 4h 缓存),
 * ARCHIVE 走归档快照,由 ViewModel 按 SourceMode 切换。
 *
 * @param onBack 返回回调
 * @param onOpenUrl 点击文章打开内置 WebView
 * @param onOpenSettings 配置未就绪时点「译」引导跳设置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RundownAiScreen(
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    // 滚动状态由 MainActivity 按 Page 持有:进 WebView 返回后保持位置
    listState: LazyListState,
    vm: RundownAiViewModel = viewModel(key = "rundown_ai")
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
        title = stringResource(R.string.source_title_rundown),
        onBack = onBack,
        state = state,
        isRefreshing = isRefreshing,
        onForceRefresh = { vm.forceRefresh() },
        listState = listState,
        snackbarHostState = snackbarHostState
    ) {
        val articles = (state as UiState.Success).data
        updateTimeHeader(sourceMode, lastRefreshAt)
        itemsIndexed(items = articles, key = { _, article -> article.slug }) { index, article ->
            ArticleRow(
                item = article,
                translateEnabled = config.translateEnabled,
                translationState = translationStates[article.slug] ?: TranslationState.Idle,
                onClick = { onOpenUrl(article.url, article.title) },
                onTranslate = { vm.translateArticle(article) }
            )
            RowDividerIfNeeded(index, articles.size)
        }
    }
}

/**
 * 单条文章行:序号徽章 + 封面图(40dp 圆角)+ 标题(主)+ 副标题(辅)+ 作者段。
 *
 * 三层信息:
 *  1. 标题:加粗正文,最多两行;翻译开关开时行末带「译」按钮
 *  2. 副标题(PLUS):弱色小字,最多两行(次要工具/技巧);部分条目无此行
 *  2.5 整体译文:title+subtitle 合并翻译的中文译文块,显示在副标题之后
 *  3. meta:作者段(如 "Zach Mink, +4")
 *
 * @param item 文章数据(rank 即为列表排名)
 */
@Composable
private fun ArticleRow(
    item: RundownAiArticle,
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
        // 序号徽章:全 App 统一 RankBadge(同 ProductRow / HuggingFacePaperRow)
        RankBadge(rank = item.rank)

        // 文章封面图:40dp 方形圆角(对齐 ProductRow thumbnail 尺寸 + shapes.small),
        // 缺失时不渲染(对齐 ProductRow 写法:if 非空保护,不占位)。底色兜底加载中/失败态。
        if (item.coverUrl.isNotBlank()) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(cs.surfaceContainerHigh)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            // ① 标题(主):加粗英文,最多两行;翻译开关开时行末带「译」按钮
            var translationCollapsed by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = item.title,
                    style = AppText.titleCompact,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    maxLines = 2,
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

            // ② 副标题(辅,PLUS 工具/技巧):弱色小字,最多两行;部分条目无此行则跳过
            if (item.subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.subtitle,
                    style = AppText.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ②.5 整体译文(title+subtitle 合并翻译):显示在副标题之后、作者段之前。
            if (translateEnabled && translationState is TranslationState.Success && !translationCollapsed) {
                Spacer(Modifier.height(4.dp))
                TranslatedText(translated = translationState.translated)
            }

            // ③ 作者段:弱色小字 + Edit 图标,如 "Zach Mink, +4"
            if (item.authors.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = cs.onSurfaceVariant
                    )
                    Text(
                        text = item.authors,
                        style = AppText.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
