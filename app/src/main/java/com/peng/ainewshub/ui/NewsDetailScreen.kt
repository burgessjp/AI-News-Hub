package com.peng.ainewshub.ui
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.data.NewsItem
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText

/**
 * 新闻详情屏幕。
 *
 * 布局思路(阅读流扁平化,与列表页 / DailyScreen 视觉统一):
 *  1. 标题置顶 —— 第一优先级,最重
 *  2. meta 行 —— 来源 · 分类 · 时间 …… 分数小药丸(单行紧凑)
 *  3. 摘要正文 —— 直接铺排,无卡片边框
 *  4. 英文原标题 —— 弱化引文样式(左侧竖线 accent),辅助参考
 *  5. 链接块 —— surfaceContainerLow 底 + shapes.small 圆角,整块可点(permalink 优先,url fallback)
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
    // onClick 是非 Composable 回调,文案提前取出
    val readerPageLabel = stringResource(R.string.detail_reader_page)
    val originalLabel = stringResource(R.string.detail_original)
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.detail_title),
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
            // ① 标题(一级大标题档,阅读流第一优先级)
            if (item.title.isNotBlank()) {
                Text(
                    text = item.title,
                    style = AppText.titleHero,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
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
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // ④ 英文原标题(弱化引文样式)
            if (!item.titleEn.isNullOrBlank() && item.titleEn != item.title) {
                Spacer(Modifier.height(16.dp))
                EnTitleBlock(text = item.titleEn)
            }

            // ⑤ 链接块(surfaceContainerLow 底 + shapes.small 圆角,整块可点)
            val showPerma = !item.permalink.isNullOrBlank()
            val showRaw = !item.url.isNullOrBlank() && item.url != item.permalink
            if (showPerma || showRaw) {
                Spacer(Modifier.height(20.dp))
                if (showPerma) {
                    LinkCard(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        sourceName = readerPageLabel,
                        title = stringResource(R.string.detail_reader_link_title),
                        onClick = { onOpenUrl(item.permalink, readerPageLabel) }
                    )
                    if (showRaw) Spacer(Modifier.height(10.dp))
                }
                if (showRaw) {
                    LinkCard(
                        icon = Icons.AutoMirrored.Filled.Article,
                        sourceName = item.source.ifBlank { originalLabel },
                        title = item.titleEn?.takeIf { it.isNotBlank() } ?: item.title,
                        onClick = { onOpenUrl(item.url, item.source.ifBlank { originalLabel }) }
                    )
                }
            }
        }
    }
}

/**
 * meta 行 —— 单行紧凑,左侧来源 / 分类 / 时间(各带 12dp 小图标,weight 1f),
 * 右侧分数小药丸。
 *
 * 与列表页 [NewsCard] 底部 meta 行同构,保证详情/列表 meta 信息风格一致;
 * 来源允许压缩(weight 1f fill=false),超长单行省略。
 */
@Composable
private fun MetaRow(item: NewsItem) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (item.source.isNotBlank()) {
                MetaItem(
                    icon = Icons.Outlined.Language,
                    text = item.source,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            val cat = item.categoryLabelRes()?.let { stringResource(it) } ?: item.category.orEmpty()
            if (cat.isNotBlank()) {
                MetaItem(icon = Icons.Outlined.Category, text = cat)
            }
            val time = relativeTime(context, item.publishedAt)
            if (time.isNotBlank()) {
                MetaItem(icon = Icons.Outlined.Schedule, text = time)
            }
        }

        // 右侧分数药丸
        if (item.score > 0) {
            ScorePill(score = item.score)
        }
    }
}

/** meta 项 —— 12dp 小图标 + 4dp 间距 + labelSmall 文本(单行省略)。 */
@Composable
private fun MetaItem(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 分数小药丸 —— meta 行内的紧凑分数展示。
 *
 * 着色分档与列表 HotBadge(NewsCard)一致(80+ tertiary / 60+ secondary /
 * 40+ primary / else onSurfaceVariant);尺寸:labelMedium + small 圆角 + 半透明背景。
 */
@Composable
private fun ScorePill(score: Int) {
    val cs = MaterialTheme.colorScheme
    val color = when {
        score >= 80 -> cs.tertiary
        score >= 60 -> cs.secondary
        score >= 40 -> cs.primary
        else -> cs.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = AppAlpha.badgeOverlay),
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
                text = stringResource(R.string.detail_original_title),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = text,
                style = AppText.bodyTight,
                color = cs.onSurfaceVariant
            )
        }
    }
}

/**
 * 链接块 —— surfaceContainerLow 底 + shapes.small 圆角的整块可点区域。
 *
 * 结构: primary 色图标 · 来源(灰) / 标题(SemiBold) …… 打开箭头;内 padding 12dp。
 * 多块之间用 10dp 间隔,不再用 hairline 分隔线。
 */
@Composable
private fun LinkCard(
    icon: ImageVector,
    sourceName: String,
    title: String,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        color = cs.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
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
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.detail_cd_open),
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
