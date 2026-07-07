package com.example.aihot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aihot.data.NewsItem
import com.example.aihot.ui.components.AppTopBar

/**
 * 新闻详情屏幕。
 *
 * 布局思路(阅读流扁平化,与列表页 / DailyScreen 视觉统一):
 *  1. 标题置顶 —— 第一优先级,最重
 *  2. meta 行 —— 来源 · 分类 · 时间 …… 分数小药丸(单行紧凑)
 *  3. 摘要正文 —— 直接铺排,无卡片边框
 *  4. 英文原标题 —— 弱化引文样式(左侧竖线 accent),辅助参考
 *  5. hairline 分隔线 + 链接卡(permalink 优先,url fallback)
 *
 * 不再使用 ScoreBadgeLarge 大色块 / TonalSection 卡片框 ——
 * 全 App 已统一为"扁平 + 描边分层"风格,详情页跟进。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsDetailScreen(
    item: NewsItem,
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit = { _, _ -> }
) {
    Scaffold(
        topBar = {
            AppTopBar(
                title = "详情",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 14.dp, bottom = 24.dp)
        ) {
            // ① 标题
            if (item.title.isNotBlank()) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 26.sp
                )
            }

            // ② meta 行: 来源 · 分类 · 时间 …… 分数药丸
            Spacer(Modifier.height(8.dp))
            MetaRow(item = item)

            // ③ 摘要正文(无卡片框)
            if (!item.summary.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 24.sp
                )
            }

            // ④ 英文原标题(弱化引文样式)
            if (!item.titleEn.isNullOrBlank() && item.titleEn != item.title) {
                Spacer(Modifier.height(16.dp))
                EnTitleBlock(text = item.titleEn)
            }

            // ⑤ hairline 分隔线 + 链接行(扁平,与正文阅读流统一)
            val deepLink = item.permalink.takeIf { it.isNotBlank() }
                ?: item.url.takeIf { it.isNotBlank() }
            val showPerma = !item.permalink.isNullOrBlank()
            val showRaw = !item.url.isNullOrBlank() && item.url != item.permalink
            if (showPerma || showRaw) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                // 行间再叠一道 hairline(与列表页 NewsRowDivider 同构)
                if (showPerma && showRaw) {
                    LinkRowDivider()
                }
                if (showPerma) {
                    LinkRow(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        sourceName = "AI HOT 阅读页",
                        title = "查看中文翻译版(站内无墙)",
                        onClick = { onOpenUrl(item.permalink, "AI HOT 阅读页") }
                    )
                    if (showRaw) LinkRowDivider()
                }
                if (showRaw) {
                    LinkRow(
                        icon = Icons.AutoMirrored.Filled.Article,
                        sourceName = item.source.ifBlank { "原文" },
                        title = item.titleEn?.takeIf { it.isNotBlank() } ?: item.title,
                        onClick = { onOpenUrl(item.url, item.source.ifBlank { "原文" }) }
                    )
                }
            }
        }
    }
}

/**
 * meta 行 —— 单行紧凑,左侧来源·分类·时间(weight 1f),右侧分数小药丸。
 *
 * 与列表页 [NewsCard] 底部 meta 行同构,保证详情/列表 meta 信息风格一致。
 */
@Composable
private fun MetaRow(item: NewsItem) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧 meta 文本组:来源 · 分类 · 时间(用 · 拼接,weight 1f 可省略)
        val parts = buildList {
            if (item.source.isNotBlank()) add(item.source)
            val cat = item.categoryLabel()
            if (cat.isNotBlank()) add(cat)
            val time = relativeTime(item.publishedAt)
            if (time.isNotBlank()) add(time)
        }
        if (parts.isNotEmpty()) {
            Text(
                text = parts.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }

        // 右侧分数药丸
        if (item.score > 0) {
            ScorePill(score = item.score)
        }
    }
}

/**
 * 分数小药丸 —— meta 行内的紧凑分数展示。
 *
 * 着色分档沿用原 ScoreBadgeLarge 语义(80+ error / 60+ tertiary / 40+ secondary / else outline),
 * 但尺寸大幅缩小:labelMedium + small 圆角 + 半透明背景,贴近列表页分数观感。
 */
@Composable
private fun ScorePill(score: Int) {
    val cs = MaterialTheme.colorScheme
    val color = when {
        score >= 80 -> cs.error
        score >= 60 -> cs.tertiary
        score >= 40 -> cs.secondary
        else -> cs.outline
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = score.toString(),
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * 英文原标题 —— 弱化引文样式(左侧 primary 竖线 accent + onSurfaceVariant 文字)。
 *
 * 不用卡片边框,仅靠竖线 accent 暗示"引文/原文"语义,视觉权重低于摘要正文。
 */
@Composable
private fun EnTitleBlock(text: String) {
    val cs = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(20.dp)
                .background(cs.primary)
                .align(Alignment.Top)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = "原文标题",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }
}

/** 链接行间 hairline 分隔线 —— 与列表页 NewsRowDivider 同构。 */
@Composable
private fun LinkRowDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * 链接行 —— 扁平无卡片框,与详情页正文阅读流及列表页 [NewsCard] 风格统一。
 *
 * 结构(单行): primary 色图标 · 来源(灰) / 标题(SemiBold) …… 打开箭头
 *  - 图标裸色(无 primaryContainer 色块背景),与 meta 行图标同构
 *  - 行间依靠 [LinkRowDivider] 区分,不再用描边圆角卡片
 *  - 整行可点,带 ripple
 */
@Composable
private fun LinkRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    sourceName: String,
    title: String,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick
            )
            .padding(horizontal = 2.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = cs.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sourceName,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "打开",
            tint = cs.onSurfaceVariant,
            modifier = Modifier
                .size(16.dp)
                .rotate(180f)
        )
    }
}
