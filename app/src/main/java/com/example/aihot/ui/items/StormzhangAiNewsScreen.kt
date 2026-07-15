package com.example.aihot.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.example.aihot.data.source.SourceMode
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aihot.data.StormzhangAiNews
import com.example.aihot.ui.EmptyState
import com.example.aihot.ui.ErrorState
import com.example.aihot.ui.StormzhangAiNewsViewModel
import com.example.aihot.ui.UiState
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.AppTopBarDefaults
import com.example.aihot.ui.components.ListUpdateTimeHeader
import com.example.aihot.ui.components.NewsCardSkeletonList
import com.example.aihot.ui.theme.AppText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * stormzhang AI Daily 全屏页面(「更多」tab 二级页)。
 *
 * 视觉对齐 [GitHubTrendingScreen] / [LinuxDoHotScreen]:
 *  - 顶栏:返回箭头 + 「stormzhang AI 资讯」+ 右上「上次刷新 N 分钟前」
 *  - 列表:排名徽章(1-3 primary 强调)+ 中文摘要(主)+ 英文原文(辅,弱色)
 *    + 信源徽章(描边小标签)· 发布时间
 *
 * 交互:
 *  - 点击单条 → 内置 WebView 打开原文([onOpenUrl])
 *  - 加载中:shimmer 骨架;失败:错误态 + 重试;空:空状态;下拉刷新走 forceRefresh
 *
 * @param onBack 返回回调
 * @param onOpenUrl 点击条目打开内置 WebView
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StormzhangAiNewsScreen(
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
    vm: StormzhangAiNewsViewModel = viewModel(key = "stormzhang_ai_news")
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lastRefreshAt by vm.lastRefreshAt.collectAsStateWithLifecycle()
    val sourceMode by vm.sourceMode.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val pageDate by vm.pageDate.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "stormzhang AI 资讯",
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
                    // 右侧 actions 仅保留「页面日期」(资讯当天日期,如 2026.07.15)。
                    // 「上次刷新」已移到列表顶部居中(见 ListUpdateTimeHeader),顶栏不再显示。
                    if (pageDate.isNotBlank()) {
                        Text(
                            text = pageDate,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    onRetry = { vm.forceRefresh() }
                )
                is UiState.Success -> {
                    val news = s.data
                    if (news.isEmpty()) {
                        EmptyState(title = "暂无内容")
                    } else {
                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = { vm.forceRefresh() },
                        ) {
                            AiNewsList(
                                news = news,
                                sourceMode = sourceMode,
                                fetchedAtMillis = lastRefreshAt,
                                onClick = { item -> onOpenUrl(item.url, item.summary) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiNewsList(
    news: List<StormzhangAiNews>,
    sourceMode: SourceMode,
    fetchedAtMillis: Long?,
    onClick: (StormzhangAiNews) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // 列表顶部居中显示数据时间(实时/归档统一位置,文案不同)
        item { ListUpdateTimeHeader(sourceMode, fetchedAtMillis) }
        itemsIndexed(
            items = news,
            key = { _, item -> item.url }
        ) { index, item ->
            AiNewsRow(item = item, onClick = { onClick(item) })
            if (index != news.lastIndex) {
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
 * 单条资讯行:序号徽章 + 中文摘要(主)+ 英文原文(辅)+ 信源徽章 · 时间。
 *
 * 三层信息:
 *  1. 中文摘要:加粗正文,最多三行(AI 生成的一句话摘要,是本条主信息)
 *  2. 英文原文:弱色小字,最多两行(原文出处的一句话,辅助参考);部分条目无此行
 *  3. meta:信源徽章(描边小标签,primary 色)+ 发布时间(弱色)
 *
 * @param item 资讯数据(rank 即为页面排名)
 */
@Composable
private fun AiNewsRow(
    item: StormzhangAiNews,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val topRank = item.rank <= 3
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
        // 序号徽章:1-3 实心 primary,其余描边低对比(同 GitHubTrendingRow)
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (topRank) cs.primary else cs.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = item.rank.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (topRank) cs.onPrimary else cs.onSurfaceVariant
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            // ① 中文摘要(主标题):加粗,最多三行
            Text(
                text = item.summary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            // ② 英文原文(辅):弱色小字,最多两行;部分条目无此行则跳过
            if (item.english.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.english,
                    style = AppText.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ③ meta:信源徽章 + 发布时间
            if (item.source.isNotBlank() || item.time.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (item.source.isNotBlank()) {
                        SourceBadge(source = item.source)
                    }
                    if (item.time.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Schedule,
                                contentDescription = null,
                                tint = cs.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.size(3.dp))
                            Text(
                                text = item.time,
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 信源徽章:取自原站 badge CSS 的信源配色(字色 + 同色低透明底 + 同色描边)。
 *  - Hacker News 橙 / Reddit 红 / Product Hunt 粉 / The Rundown AI 紫 / TLDR AI 蓝
 *  - 未知名源走中性灰(badge-default)
 * 颜色由 [StormzhangAiNews.sourceColorHex] 给出,UI 层解析 hex → Color。
 */
@Composable
private fun SourceBadge(source: String) {
    val fallback = MaterialTheme.colorScheme.outline
    val color = remember(source) {
        runCatching { Color(android.graphics.Color.parseColor(StormzhangAiNews.sourceColorHex(source))) }
            .getOrNull() ?: fallback
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .border(width = 1.dp, color = color.copy(alpha = 0.20f), shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = source,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1
        )
    }
}
