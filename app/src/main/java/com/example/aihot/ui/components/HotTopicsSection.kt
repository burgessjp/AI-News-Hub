package com.example.aihot.ui.components

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aihot.data.HotTopic
import com.example.aihot.ui.HotTopicsViewModel
import com.example.aihot.ui.UiState
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 今日热点模块 —— 精选 tab 顶部的卡片式聚合模块。
 *
 * 视觉(对齐设计稿):
 *  - 一整张卡片,顶部 primary 渐变背景的标题栏(flame 图标 + 「今日热点」+ 右侧来源数小字)
 *  - 卡片内每条热点一行:左序号徽章(1-3 用 primary 强调,其余低对比)+ 标题 + 来源/聚合数
 *
 * 交互:
 *  - 点击单条 → 打开内置 WebView(优先 permalink,回退 url)
 *  - 加载失败 / 无数据 → 整个模块隐藏(返回空),不阻塞下方主列表
 *
 * @param onOpen (url, title) 回调,复用全局 openUrl 打开 WebView
 */
@Composable
fun HotTopicsSection(
    onOpen: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    vm: HotTopicsViewModel = viewModel(key = "hot-topics")
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // 失败(含「无热点」空结果)时静默隐藏,精选列表正常展示。
    val topics = (state as? UiState.Success)?.data ?: return

    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            HotTopicsHeader(count = topics.size)
            topics.forEachIndexed { index, topic ->
                HotTopicRow(
                    rank = index + 1,
                    topic = topic,
                    onClick = {
                        val url = topic.permalink.ifBlank { topic.url }
                        if (url.isNotBlank()) onOpen(url, topic.title.ifBlank { "加载中…" })
                    }
                )
                if (index != topics.lastIndex) {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 44.dp, end = 16.dp)
                            .height(0.5.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }
        }
    }
}

/**
 * 卡片顶部标题栏 —— primary 渐变背景 + flame 图标 + 「今日热点」。
 */
@Composable
private fun HotTopicsHeader(count: Int) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        cs.primary,
                        cs.primary.copy(alpha = 0.82f)
                    )
                )
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "今日热点",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = cs.onPrimary
        )
        Spacer(Modifier.weight(1f))
        // 右侧:聚合来源数小标签
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(cs.onPrimary.copy(alpha = 0.18f))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = "${count}条",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 单条热点行:序号徽章 + 标题 + 来源/聚合数。
 *
 * @param rank 1 起的序号;1-3 用 primary 强调,其余低对比。
 */
@Composable
private fun HotTopicRow(
    rank: Int,
    topic: HotTopic,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val topRank = rank <= 3
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(),
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 序号徽章:1-3 实心 primary,其余描边低对比
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (topRank) cs.primary else Color.Transparent
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = rank.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (topRank) cs.onPrimary else cs.onSurfaceVariant
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            // 标题
            Text(
                text = topic.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )
            // 来源 · 聚合数
            val meta = buildString {
                if (topic.source.isNotBlank()) append(topic.source)
                if (topic.sourceCount > 1) {
                    if (isNotEmpty()) append(" · ")
                    append("${topic.sourceCount} 源报道")
                }
            }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
