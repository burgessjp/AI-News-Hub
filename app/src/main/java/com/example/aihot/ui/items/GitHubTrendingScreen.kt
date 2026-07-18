package com.example.aihot.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.example.aihot.data.source.SourceMode
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aihot.data.TrendingRepo
import com.example.aihot.data.AiConfig
import com.example.aihot.ui.EmptyState
import com.example.aihot.ui.ErrorState
import com.example.aihot.ui.GitHubTrendingViewModel
import com.example.aihot.ui.TranslationState
import com.example.aihot.ui.UiState
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.AppTopBarDefaults
import com.example.aihot.ui.components.ListUpdateTimeHeader
import com.example.aihot.ui.components.RankBadge
import com.example.aihot.ui.components.RankRowSkeletonList
import com.example.aihot.ui.components.StatBadge
import com.example.aihot.ui.components.formatCount
import com.example.aihot.ui.theme.AppText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

/**
 * GitHub Trending 全屏页面(「更多」tab 二级页)。
 *
 * 视觉对齐 [HackerNewsScreen]:
 *  - 顶栏:返回箭头 + 「GitHub Trending」+ 右上「上次刷新 N 分钟前」
 *  - 列表:排名徽章([RankBadge] 统一分档)+ owner/name + 描述 + 语言色点·语言 · stars·forks·今日新增
 *
 * 交互:
 *  - 点击单条 → 内置 WebView 打开仓库([onOpenUrl])
 *  - 加载中:shimmer 骨架;失败:错误态 + 重试;空:空状态;下拉刷新走 forceRefresh
 *  - 描述翻译:翻译开关开且配置就绪时,描述行出现「译」按钮(见 [InlineTranslateButton])
 *
 * @param onBack 返回回调
 * @param onOpenUrl 点击仓库打开内置 WebView
 * @param onOpenSettings 配置未就绪时点「译」引导跳设置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubTrendingScreen(
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    // 滚动状态由 MainActivity 按 Page 持有:进 WebView 返回后保持位置
    listState: LazyListState,
    vm: GitHubTrendingViewModel = viewModel(key = "github_trending")
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lastRefreshAt by vm.lastRefreshAt.collectAsStateWithLifecycle()
    val sourceMode by vm.sourceMode.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val descStates by vm.descStates.collectAsStateWithLifecycle()
    val config by vm.configFlow.collectAsStateWithLifecycle(initialValue = AiConfig())
    val snackbarHostState = remember { SnackbarHostState() }

    // 配置未就绪提示:点「译」后若 state 变成 CONFIG_MISSING,弹一次引导
    LaunchedEffect(descStates) {
        descStates.values.firstOrNull { it is TranslationState.Error && it.message == TranslationState.CONFIG_MISSING }
            ?.let {
                val r = snackbarHostState.showSnackbar(
                    message = "请先在 设置 → 翻译 中配置 API",
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
                title = "GitHub Trending",
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
                    val repos = s.data
                    if (repos.isEmpty()) {
                        // 数据缺失空态(归档快照为空/实时无条目):给刷新恢复路径
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
                            TrendingList(
                                repos = repos,
                                listState = listState,
                                descStates = descStates,
                                translateEnabled = config.translateEnabled,
                                sourceMode = sourceMode,
                                fetchedAtMillis = lastRefreshAt,
                                onClick = { repo -> onOpenUrl(repo.url, "${repo.owner}/${repo.name}") },
                                onTranslate = { vm.translateDesc(it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendingList(
    repos: List<TrendingRepo>,
    listState: LazyListState,
    descStates: Map<String, TranslationState>,
    translateEnabled: Boolean,
    sourceMode: SourceMode,
    fetchedAtMillis: Long?,
    onClick: (TrendingRepo) -> Unit,
    onTranslate: (TrendingRepo) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // 列表顶部居中显示数据时间(实时/归档统一位置,文案不同)
        item { ListUpdateTimeHeader(sourceMode, fetchedAtMillis) }
        itemsIndexed(
            items = repos,
            key = { _, repo -> repo.url }
        ) { index, repo ->
            TrendingRow(
                repo = repo,
                translateEnabled = translateEnabled,
                translationState = descStates[repo.url] ?: TranslationState.Idle,
                onClick = { onClick(repo) },
                onTranslate = { onTranslate(repo) }
            )
            if (index != repos.lastIndex) {
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
 * 单条仓库行:序号徽章 + owner/name + 描述 + 语言色点·语言 · stars 总数 · forks · 今日新增。
 *
 * 三层信息:
 *  1. 标题:owner(弱色)/ name(加粗)
 *  2. 描述:最多两行,弱色;翻译开关开时行末带「译」按钮,译文显示在描述下方
 *  3. meta:语言(带色点)+ stars + forks + 今日新增(火焰图标,强调色)
 *
 * @param repo 仓库数据(rank 即为页面排名)
 */
@Composable
private fun TrendingRow(
    repo: TrendingRepo,
    translateEnabled: Boolean,
    translationState: TranslationState,
    onClick: () -> Unit,
    onTranslate: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var descCollapsed by remember { mutableStateOf(false) }
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
        // 序号徽章:全 App 统一 RankBadge(同 HackerNewsRow)
        RankBadge(rank = repo.rank)

        Column(modifier = Modifier.weight(1f)) {
            // ① 标题:owner(弱色)/ name(加粗),对齐 GitHub 原生风(owner/name 强弱结构保持)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = repo.owner,
                    style = AppText.titleCompact,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = " / ",
                    style = AppText.titleCompact,
                    color = cs.onSurfaceVariant
                )
                Text(
                    text = repo.name,
                    style = AppText.titleCompact,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ② 描述:最多两行,弱色(部分仓库无描述则跳过)。
            //    翻译开关开时行末带「译」按钮,译文显示在描述下方(复用 HackerNews 同款组件)。
            if (repo.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = repo.description,
                        style = AppText.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (translateEnabled) {
                        Spacer(Modifier.width(6.dp))
                        InlineTranslateButton(
                            state = translationState,
                            collapsed = descCollapsed,
                            onToggleCollapse = { descCollapsed = !descCollapsed },
                            onTranslate = onTranslate
                        )
                    }
                }

                // ②.5 描述译文(仅翻译开关开且已翻译且未折叠时):弱色小字,显示在描述下方
                if (translateEnabled && translationState is TranslationState.Success && !descCollapsed) {
                    Spacer(Modifier.height(4.dp))
                    TranslatedText(translated = translationState.translated)
                }
            }

            // ③ meta:语言色点·语言 · stars 总数 · forks · 今日新增
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 语言(带色点);无语言则不渲染这一栏
                if (repo.language.isNotBlank()) {
                    LanguageTag(
                        language = repo.language,
                        colorHex = repo.languageColor
                    )
                }
                // 总 stars
                StatBadge(
                    icon = Icons.Filled.Star,
                    value = formatCount(repo.totalStars)
                )
                // forks
                StatBadge(
                    icon = Icons.AutoMirrored.Filled.CallSplit,
                    value = formatCount(repo.forks)
                )
                // 今日新增(primary 强调,让用户一眼看到「为什么上趋势」)
                if (repo.starsToday > 0) {
                    StatBadge(
                        icon = Icons.Filled.LocalFireDepartment,
                        value = "${formatCount(repo.starsToday)} today",
                        tint = cs.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/** 语言标签:语言色点 + 语言名。色点 hex 缺失时回退到 outline 中性色。 */
@Composable
private fun LanguageTag(language: String, colorHex: String) {
    val cs = MaterialTheme.colorScheme
    val dotColor = remember(colorHex) {
        runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
            .getOrNull() ?: cs.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = language,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            maxLines = 1
        )
    }
}
