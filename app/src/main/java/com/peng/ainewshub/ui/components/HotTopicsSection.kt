package com.peng.ainewshub.ui.components

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peng.ainewshub.R
import com.peng.ainewshub.data.model.HotTopic
import com.peng.ainewshub.ui.HotTopicsViewModel
import com.peng.ainewshub.ui.UiState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.ui.theme.AppText

/**
 * 今日热点模块 —— 精选 tab 顶部的卡片式聚合模块。
 *
 * 视觉(纸墨日报):
 *  - 一整张方角区块(surfaceContainerLow 底,不裁圆角),顶部双细线 + 衬线
 *    「今日热点」标题 + 右侧来源数小字
 *  - 区块内每条热点一行:左序号徽章([RankBadge] 统一分档)+ 标题 + 来源/聚合数
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
    // onClick 是非 Composable 回调,文案提前取出
    val loadingLabel = stringResource(R.string.common_loading)

    // 失败(含「无热点」空结果)时静默隐藏,精选列表正常展示。
    val topics = (state as? UiState.Success)?.data ?: return

    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 不做圆角裁切:纸墨版面方角区块,卡头双细线通栏——圆角会在
                // 两上角把线削出弧形缺口(用户截图实证),去卡片化后也无需圆润
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            HotTopicsHeader(count = topics.size)
            topics.forEachIndexed { index, topic ->
                HotTopicRow(
                    rank = index + 1,
                    topic = topic,
                    onClick = {
                        val url = topic.permalink.ifBlank { topic.url }
                        if (url.isNotBlank()) onOpen(url, topic.title.ifBlank { loadingLabel })
                    }
                )
                if (index != topics.lastIndex) {
                    // 分隔线缩进对齐标题列:16(行 padding)+ 24(徽章)+ 10(间距)= 50
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 50.dp, end = 16.dp)
                            .height(0.5.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }
        }
    }
}

/** 卡片顶部标题栏:双细线起头 + 衬线「今日热点」+ 来源数,底部粗线收束。 */
@Composable
private fun HotTopicsHeader(count: Int) {
    val cs = MaterialTheme.colorScheme
    // 纸墨日报:双细线起头 + 衬线标题 + 底部粗线,去渐变卡头
    Column(modifier = Modifier.fillMaxWidth()) {
        DoubleRule()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.hot_topics_title),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Serif,
                color = cs.onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = pluralStringResource(R.plurals.hot_topics_count, count, count),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant
            )
        }
        SectionRule()
    }
}

/**
 * 单条热点行:序号徽章 + 标题 + 来源/聚合数。
 *
 * @param rank 1 起的序号,徽章配色由 [RankBadge] 统一分档。
 */
@Composable
private fun HotTopicRow(
    rank: Int,
    topic: HotTopic,
    onClick: () -> Unit
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 序号徽章:全 App 统一 RankBadge(24dp)
        RankBadge(rank = rank)

        Column(modifier = Modifier.weight(1f)) {
            // 标题(titleCompact 语义档:Medium 基线,标题场景覆盖 SemiBold;随字号设置缩放)
            Text(
                text = topic.title,
                style = AppText.titleCompact,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // 来源 · 聚合数
            val meta = buildString {
                if (topic.source.isNotBlank()) append(topic.source)
                if (topic.sourceCount > 1) {
                    if (isNotEmpty()) append(" · ")
                    append(
                        pluralStringResource(
                            R.plurals.hot_topics_source_count, topic.sourceCount, topic.sourceCount
                        )
                    )
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
