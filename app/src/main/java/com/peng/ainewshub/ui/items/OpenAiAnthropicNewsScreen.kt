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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.peng.ainewshub.data.AiConfig
import com.peng.ainewshub.data.OpenAiAnthropicNews
import com.peng.ainewshub.data.source.SourceMode
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.OpenAiAnthropicNewsViewModel
import com.peng.ainewshub.ui.TranslationState
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.ListUpdateTimeHeader
import com.peng.ainewshub.ui.components.RankBadge
import com.peng.ainewshub.ui.components.RankRowSkeletonList
import com.peng.ainewshub.ui.theme.AppText

/**
 * OpenAI x Anthropic 厂商动态全屏页面(「更多」tab 二级页)。
 *
 * 视觉对齐 [RundownAiScreen] / [ProductHuntScreen]:
 *  - 顶栏:返回箭头 + 「OpenAI x Anthropic」+ 列表顶部「上次更新」时间头
 *  - 列表:排名徽章([RankBadge] 统一分档)+ 厂商徽章 + 标题(英文加粗)
 *    + 英文摘要 + 分类/发布时间 meta 行
 *
 * 交互:
 *  - 点击单条 → 内置 WebView 打开官方博客页([OpenAiAnthropicNews.url])
 *  - 加载中:shimmer 骨架;失败:错误态 + 重试;空:空状态;下拉刷新走 forceRefresh
 *  - 整体翻译:翻译开关开且配置就绪时,标题行出现「译」按钮,title+summary 合并一次翻译
 *
 * 纯归档源:LIVE 与 ARCHIVE 都走归档快照(两家无稳定公开 API),由 ViewModel 按
 * SourceMode 切换(实际都走归档,sourceMode 仅影响顶栏角标)。
 *
 * @param onBack 返回回调
 * @param onOpenUrl 点击文章打开内置 WebView
 * @param onOpenSettings 配置未就绪时点「译」引导跳设置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenAiAnthropicNewsScreen(
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    // 滚动状态由 MainActivity 按 Page 持有:进 WebView 返回后保持位置
    listState: LazyListState,
    vm: OpenAiAnthropicNewsViewModel = viewModel(key = "openai_anthropic_news")
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lastRefreshAt by vm.lastRefreshAt.collectAsStateWithLifecycle()
    val sourceMode by vm.sourceMode.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val translationStates by vm.translationStates.collectAsStateWithLifecycle()
    val config by vm.configFlow.collectAsStateWithLifecycle(initialValue = AiConfig())
    val snackbarHostState = remember { SnackbarHostState() }

    // 配置未就绪提示:点「译」后若 state 变成 CONFIG_MISSING,弹一次引导
    LaunchedEffect(translationStates) {
        translationStates.values.firstOrNull { it is TranslationState.Error && it.message == TranslationState.CONFIG_MISSING }
            ?.let {
                val r = snackbarHostState.showSnackbar(
                    message = "请先在 设置 → 翻译 中配置翻译服务",
                    actionLabel = "去设置"
                )
                if (r == SnackbarResult.ActionPerformed) onOpenSettings()
            }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = "OpenAI x Anthropic",
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
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
                    val articles = s.data
                    if (articles.isEmpty()) {
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
                            ArticlesList(
                                articles = articles,
                                listState = listState,
                                translationStates = translationStates,
                                translateEnabled = config.translateEnabled,
                                sourceMode = sourceMode,
                                fetchedAtMillis = lastRefreshAt,
                                onClick = { a -> onOpenUrl(a.url, a.title) },
                                onTranslate = { vm.translateArticle(it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticlesList(
    articles: List<OpenAiAnthropicNews>,
    listState: LazyListState,
    translationStates: Map<String, TranslationState>,
    translateEnabled: Boolean,
    sourceMode: SourceMode,
    fetchedAtMillis: Long?,
    onClick: (OpenAiAnthropicNews) -> Unit,
    onTranslate: (OpenAiAnthropicNews) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // 列表顶部居中显示数据时间(归档快照时刻)
        item { ListUpdateTimeHeader(sourceMode, fetchedAtMillis) }
        itemsIndexed(
            items = articles,
            key = { _, a -> a.url }
        ) { index, article ->
            ArticleRow(
                item = article,
                translateEnabled = translateEnabled,
                translationState = translationStates[article.url] ?: TranslationState.Idle,
                onClick = { onClick(article) },
                onTranslate = { onTranslate(article) }
            )
            if (index != articles.lastIndex) {
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
 * 单条厂商动态行:序号徽章 + 厂商徽章 + 标题(主)+ 英文摘要 + 分类/时间 meta。
 *
 * 三层信息:
 *  1. 标题:加粗正文,最多两行;翻译开关开时行末带「译」按钮
 *  2. 英文摘要:弱色小字,最多两行;部分条目无此行
 *  2.5 整体译文:title+summary 合并翻译的中文译文块,显示在摘要之后
 *  3. meta:厂商徽章(OpenAI/Anthropic)+ 分类 + 发布时间
 *
 * @param item 动态数据(rank 即为列表排名)
 */
@Composable
private fun ArticleRow(
    item: OpenAiAnthropicNews,
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
        // 序号徽章:全 App 统一 RankBadge(同 ProductRow / RundownAi ArticleRow)
        RankBadge(rank = item.rank)

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

            // ② 英文摘要:弱色小字,最多两行;无摘要则跳过
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

            // ②.5 整体译文(title+summary 合并翻译):显示在摘要之后、meta 之前。
            if (translateEnabled && translationState is TranslationState.Success && !translationCollapsed) {
                Spacer(Modifier.height(4.dp))
                TranslatedText(translated = translationState.translated)
            }

            // ③ meta:厂商徽章 + 分类 + 发布时间
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (item.vendor.isNotBlank()) {
                    VendorBadge(vendor = item.vendor)
                }
                if (item.category.isNotBlank()) {
                    Text(
                        text = item.category,
                        style = AppText.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.vendor.isNotBlank() || item.category.isNotBlank()) {
                    Text(
                        text = "·",
                        style = AppText.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
                Text(
                    // publishedAt 格式不一(OpenAI 带 T..Z,Anthropic 仅日期),统一取前 10 位 yyyy-MM-dd
                    text = item.publishedAt.take(10),
                    style = AppText.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 厂商徽章:OpenAI / Anthropic 区分,弱化色块 + 图标。对齐全 App 徽章视觉。
 */
@Composable
private fun VendorBadge(vendor: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .background(
                color = cs.secondaryContainer,
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Business,
            contentDescription = null,
            modifier = Modifier.height(12.dp),
            tint = cs.onSecondaryContainer
        )
        Text(
            text = vendor,
            style = AppText.bodySmall,
            color = cs.onSecondaryContainer,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}
