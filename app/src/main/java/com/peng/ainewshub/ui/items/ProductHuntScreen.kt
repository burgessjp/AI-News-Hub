package com.peng.ainewshub.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import com.peng.ainewshub.R
import com.peng.ainewshub.data.AiConfig
import com.peng.ainewshub.data.ProductHunt
import com.peng.ainewshub.data.source.SourceMode
import com.peng.ainewshub.ui.ProductHuntViewModel
import com.peng.ainewshub.ui.TranslationState
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.RankBadge
import com.peng.ainewshub.ui.components.RowDividerIfNeeded
import com.peng.ainewshub.ui.components.SourceListScaffold
import com.peng.ainewshub.ui.components.StatBadge
import com.peng.ainewshub.ui.components.TranslateConfigMissingEffect
import com.peng.ainewshub.ui.components.InlineTranslateButton
import com.peng.ainewshub.ui.components.TranslatedText
import com.peng.ainewshub.ui.components.formatCount
import com.peng.ainewshub.ui.components.updateTimeHeader
import com.peng.ainewshub.ui.components.rememberReadUrls
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText

/**
 * Product Hunt 当日热门全屏页面(「更多」tab 二级页)。
 *
 * 视觉对齐 [HuggingFacePapersScreen] / [GitHubTrendingScreen]:
 *  - 顶栏:返回箭头 + 「Product Hunt」+ 列表顶部「上次更新」时间头
 *  - 列表:排名徽章([RankBadge] 统一分档)+ 产品名(主)+ tagline(辅,弱色)
 *    + upvotes(primary 强调,热度主指标)· 评论数
 *
 * 交互:
 *  - 点击单条 → 内置 WebView 打开 PH 产品页([ProductHunt.targetUrl],url 优先)
 *  - 加载中:shimmer 骨架;失败:错误态 + 重试;空:空状态;下拉刷新走 forceRefresh
 *  - 整体翻译:翻译开关开且配置就绪时,标题行出现「译」按钮,name+tagline 合并一次翻译
 *
 * 注:Product Hunt 只走归档(Developer Token 不进 APK),两种 SourceMode 都读归档快照。
 *
 * @param onBack 返回回调
 * @param onOpenUrl 点击产品打开内置 WebView
 * @param onOpenSettings 配置未就绪时点「译」引导跳设置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductHuntScreen(
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    // 滚动状态由 MainActivity 按 Page 持有:进 WebView 返回后保持位置
    listState: LazyListState,
    vm: ProductHuntViewModel = viewModel(key = "product_hunt")
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lastRefreshAt by vm.lastRefreshAt.collectAsStateWithLifecycle()
    val sourceMode by vm.sourceMode.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val translationStates by vm.translationStates.collectAsStateWithLifecycle()
    val config by vm.configFlow.collectAsStateWithLifecycle(initialValue = AiConfig())
    val snackbarHostState = remember { SnackbarHostState() }

    // 已读判定(打开 URL 命中浏览历史):驱动行内标题弱化
    val readUrls = rememberReadUrls()

    // 配置未就绪提示:点「译」后若 state 变成 CONFIG_MISSING,弹一次引导
    TranslateConfigMissingEffect(translationStates, snackbarHostState, onOpenSettings)

    SourceListScaffold(
        title = stringResource(R.string.source_title_producthunt),
        onBack = onBack,
        state = state,
        isRefreshing = isRefreshing,
        onForceRefresh = { vm.forceRefresh() },
        listState = listState,
        snackbarHostState = snackbarHostState
    ) {
        val products = (state as UiState.Success).data
        updateTimeHeader(sourceMode, lastRefreshAt)
        itemsIndexed(items = products, key = { _, p -> p.id }) { index, product ->
            ProductRow(
                item = product,
                translateEnabled = config.translateEnabled,
                translationState = translationStates[product.slug.ifBlank { product.id }] ?: TranslationState.Idle,
                onClick = {
                    val target = product.targetUrl
                    if (target.isNotBlank()) onOpenUrl(target, product.name)
                },
                onTranslate = { vm.translateProduct(product) },
                // 已读 = 打开过的产品页链接(url 空则回退 website)命中浏览历史
                isRead = product.targetUrl in readUrls
            )
            RowDividerIfNeeded(index, products.size)
        }
    }
}

/**
 * 单条产品行:序号徽章 + 产品名(主)+ tagline(辅)+ upvotes · 评论数。
 *
 * 三层信息:
 *  1. 产品名:加粗正文,最多两行;翻译开关开时行末带「译」按钮
 *  2. tagline:弱色小字,最多两行(一句话价值定位);部分条目无此行
 *  2.5 整体译文:name+tagline 合并翻译的中文译文块,显示在 tagline 之后
 *  3. meta:upvotes(primary 强调,热度主指标)+ 评论数
 *
 * @param item 产品数据(rank 即为列表排名)
 * @param isRead 已读(打开 URL 命中浏览历史)时标题降透明弱化
 */
@Composable
private fun ProductRow(
    item: ProductHunt,
    translateEnabled: Boolean,
    translationState: TranslationState,
    onClick: () -> Unit,
    onTranslate: () -> Unit,
    isRead: Boolean = false
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
        // 序号徽章:全 App 统一 RankBadge(同 HuggingFacePaperRow / GitHubTrendingRow)
        RankBadge(rank = item.rank)

        // 产品主图(thumbnail):40dp 方形圆角(对齐 BrowseHistory 缩略块尺寸 + shapes.small),
        // 缺失时不渲染(if 非空保护,不占位)。底色兜底加载中/失败态。
        if (item.thumbnailUrl.isNotBlank()) {
            AsyncImage(
                model = item.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(cs.surfaceContainerHigh)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            // ① 产品名(主):加粗,最多两行;翻译开关开时行末带「译」按钮
            var translationCollapsed by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = item.name,
                    style = AppText.titleCompact,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isRead) cs.onSurface.copy(alpha = AppAlpha.readDim) else cs.onSurface,
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

            // ② tagline(辅):弱色小字,最多两行;部分条目无此行则跳过
            if (item.tagline.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.tagline,
                    style = AppText.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ②.5 整体译文(name+tagline 合并翻译):显示在 tagline 之后、meta 之前。
            if (translateEnabled && translationState is TranslationState.Success && !translationCollapsed) {
                Spacer(Modifier.height(4.dp))
                TranslatedText(translated = translationState.translated)
            }

            // ②.6 话题标签(topics):至多 3 个,扁平小胶囊(对齐总览页 SourceChip 写法),
            //   弱色不抢 upvotes 焦点;纯展示不可点(点击区留给整行跳产品页)。
            if (item.topics.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item.topics.take(3).forEach { topic ->
                        TopicChip(topic)
                    }
                }
            }

            // ③ meta:upvotes(primary 强调)· 评论数
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // upvotes 是热度主指标,用 primary 强调
                StatBadge(
                    icon = Icons.Filled.ThumbUp,
                    value = formatCount(item.votesCount),
                    tint = cs.primary,
                    fontWeight = FontWeight.SemiBold
                )
                StatBadge(
                    icon = Icons.Filled.Forum,
                    value = formatCount(item.commentsCount)
                )
            }
        }
    }
}

/**
 * 话题标签胶囊 —— Product Hunt 产品行的话题标签(如 "Artificial Intelligence")。
 *
 * 视觉对齐总览页 SourceChip:surfaceContainerHigh 底衬 + caption 弱色文字 + CircleShape。
 * 纯展示不可点(整行已可点跳产品页)。用 onSurfaceVariant 保持低调,不抢 upvotes 的 primary 焦点。
 *
 * @param topic 话题名(原样展示)
 */
@Composable
private fun TopicChip(topic: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = topic,
            style = AppText.caption,
            color = cs.onSurfaceVariant,
            maxLines = 1
        )
    }
}
