package com.example.aihot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aihot.data.NewsItem
import com.example.aihot.ui.components.AppClickableCard
import com.example.aihot.ui.components.AppTopBar

/**
 * 新闻详情屏幕。
 *
 * MD3 合规要点:
 *  - 全部使用 MaterialTheme.colorScheme / typography / shapes
 *  - 通过 surfaceContainerHigh 色调 elevation 区分内容块,无阴影
 *  - 链接卡片按官方建议**优先 permalink**(站内中文翻译阅读页),url 作 fallback
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
                titleFontSize = 20.sp,
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
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 分数与元信息
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ScoreBadgeLarge(item.score)
                Column {
                    Text(
                        text = item.categoryLabel().ifBlank { "AI 动态" },
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = relativeTime(item.publishedAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 标题
            if (item.title.isNotBlank()) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 摘要块
            if (!item.summary.isNullOrBlank()) {
                TonalSection(title = "摘要") {
                    Text(
                        text = item.summary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 英文原标题
            if (!item.titleEn.isNullOrBlank() && item.titleEn != item.title) {
                TonalSection(title = "原文标题") {
                    Text(
                        text = item.titleEn,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 优先 permalink(站内中文翻译阅读页,无墙)
            val deepLink = item.permalink.takeIf { it.isNotBlank() }
                ?: item.url.takeIf { it.isNotBlank() }
            if (!deepLink.isNullOrBlank()) {
                val isPermaLink = deepLink == item.permalink
                LinkCard(
                    icon = if (isPermaLink) Icons.AutoMirrored.Filled.MenuBook else Icons.AutoMirrored.Filled.Article,
                    sourceName = if (isPermaLink) "AI HOT 阅读页" else item.source.ifBlank { "原文" },
                    title = if (isPermaLink) "查看中文翻译版(站内无墙)" else "查看原文",
                    onClick = { onOpenUrl(deepLink, if (isPermaLink) "AI HOT 阅读页" else "原文") }
                )
            }

            // 原文链接(若 permalink 已用,且 url 不同,再补一项原文)
            if (!item.url.isNullOrBlank() && item.url != item.permalink) {
                LinkCard(
                    icon = Icons.AutoMirrored.Filled.Article,
                    sourceName = item.source.ifBlank { "原文" },
                    title = item.titleEn?.takeIf { it.isNotBlank() }?.let { it } ?: item.title,
                    onClick = { onOpenUrl(item.url, item.source.ifBlank { "原文" }) }
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TonalSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) { content() }
        }
    }
}

@Composable
private fun LinkCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    sourceName: String,
    title: String,
    onClick: () -> Unit
) {
    AppClickableCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(8.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.OpenInBrowser,
                contentDescription = "打开",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ScoreBadgeLarge(score: Int) {
    val cs = MaterialTheme.colorScheme
    val color = when {
        score >= 80 -> cs.error
        score >= 60 -> cs.tertiary
        score >= 40 -> cs.secondary
        else -> cs.outline
    }
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = MaterialTheme.shapes.large
    ) {
        Text(
            text = "$score",
            color = color,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
