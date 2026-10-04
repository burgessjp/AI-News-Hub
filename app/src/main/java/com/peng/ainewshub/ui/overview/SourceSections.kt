package com.peng.ainewshub.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.data.repo.SourceSummary
import com.peng.ainewshub.data.repo.SummaryContent
import com.peng.ainewshub.data.repo.SummaryItem
import com.peng.ainewshub.data.source.SourceFreshness
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.ShimmerBox
import com.peng.ainewshub.ui.components.ShimmerHost
import com.peng.ainewshub.ui.more.sourceMeta
import com.peng.ainewshub.ui.summary.hasUnseenDigest
import com.peng.ainewshub.ui.summary.renderItemLine
import com.peng.ainewshub.ui.summary.renderRichLine
import com.peng.ainewshub.ui.summary.sourceAccentOf
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText
import com.peng.ainewshub.ui.theme.TrackingWide

/**
 * 分源摘要区块共享件 —— 「今天」根屏(TodayScreen)与「过刊」页(HistoryHubScreen)
 * 共用的单源区块渲染:区块头([SourceSectionHeader])+ 加载骨架 + 条目行
 * (v2 结构化 [SourceSummaryItemRow] / v1 纯文本 [SourcePlainLineRow])。
 *
 * 区块头由调用方组合进各自的 LazyListScope(今天页带「已查看」指纹写入回调,
 * 过刊页恒 hasUnseen=false 纯回看);条目行平铺无卡片,序号取源强调色。
 */

/** 分源区块最多平铺的摘要条数:更多条目经区块头「查看全部」进源列表页。 */
internal const val SOURCE_SECTION_MAX_ITEMS = 3

/**
 * 分源摘要区块头 —— 源名 + 「新内容」圆点(源身份的强调色锚点在条目序号),右侧
 * 「查看全部 N 条 ›」文字链(primary 色 + 尾随 chevron,与词云入口同语言);
 * 整行可点进源完整列表。断供警示沿用摘要卡扁头语义(数据时刻超 24h 未前进
 * → 错误色「!」文字提示)。
 *
 * @param viewAllEnabled false(过刊页)时隐藏「查看全部」出口、整行不可点:
 *        源列表页展示的是今日数据,从历史日期跳转语义不符
 */
@Composable
internal fun SourceSectionHeader(
    source: String,
    state: UiState<SourceSummary>,
    hasUnseen: Boolean,
    onOpen: () -> Unit,
    viewAllEnabled: Boolean = true
) {
    val cs = MaterialTheme.colorScheme
    val meta = sourceMeta(source)
    val structuredCount =
        ((state as? UiState.Success)?.data?.content as? SummaryContent.Structured)?.items?.size ?: 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (viewAllEnabled) Modifier.clickable(onClick = onOpen) else Modifier)
            // 区块头上方留白 16dp:删线后区块间隔靠这里的段落感
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 标题槽:恒填满的 weight 容器(默认 fill=true)内部「源名 + 未读圆点」按内容宽
        // 靠左,让右侧「查看全部」出口钉死行尾 —— 若标题自身挂 weight(fill=false),
        // 未认领的权重份额不占位、尾部会整体左移出一截右侧空白(已踩坑回退)
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                // titleCompact(14/Medium,随字号档缩放)+ 宽字距:小节头层级语言
                text = meta.title,
                style = AppText.titleCompact,
                letterSpacing = TrackingWide,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 「新内容未查看」小圆点:装饰性(区块头进入视口即写入指纹熄灭),槽位恒占位防跳动
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (hasUnseen) cs.primary else Color.Transparent)
            )
        }
        if (state is UiState.Success && SourceFreshness.isStale(state.data.fetchedAtMs)) {
            // 断供警示:数据时刻超 24h 未前进(该源连续多批抓取失败),错误色轻提示
            Text(
                text = "!",
                style = AppText.caption,
                fontWeight = FontWeight.SemiBold,
                color = cs.error
            )
            Spacer(Modifier.width(6.dp))
        }
        if (viewAllEnabled) {
            Text(
                text = if (structuredCount > 0) {
                    pluralStringResource(R.plurals.today_source_view_all, structuredCount, structuredCount)
                } else {
                    stringResource(R.string.today_source_view_all_plain)
                },
                style = AppText.caption,
                fontWeight = FontWeight.SemiBold,
                color = cs.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = cs.primary
            )
        }
    }
}

/** 分源区块加载骨架:三条单行 shimmer,同构条目行结构(序号槽 + 单行正文),到位不塌行。 */
@Composable
internal fun SourceSectionSkeleton() {
    ShimmerHost {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            repeat(3) { i ->
                // 与 SourceSummaryItemRow 同构:24dp 序号槽 + 12dp 间距 + 单行正文
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShimmerBox(modifier = Modifier.size(24.dp, 12.dp), cornerRadius = 4.dp)
                    Box(modifier = Modifier.weight(1f)) {
                        val width = if (i == 2) 0.7f else 0.95f
                        ShimmerBox(modifier = Modifier.fillMaxWidth(width).height(14.dp), cornerRadius = 4.dp)
                    }
                }
            }
        }
    }
}

/** v2 结构化条目行(分源区块内):序号(源强调色,24dp 槽与 Top10/关注段成列)+ 富文本行,已读弱化,可点直达原文。 */
@Composable
internal fun SourceSummaryItemRow(
    index: Int,
    item: SummaryItem,
    accent: Color,
    isRead: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    // desc 降档样式(onSurfaceVariant 灰 + bodySmall 小一号)须在组合上下文取值:
    // AppText 随字号档(fontScale)缩放,纯函数内不可硬编码;remember key 含
    // isRead/bodySmall/色值,已读弱化、字号档与深浅色切换都触发重算
    val bodySmall = AppText.bodySmall
    val line = remember(item.title, item.desc, isRead, bodySmall, cs.onSurfaceVariant) {
        val descColor =
            if (isRead) cs.onSurfaceVariant.copy(alpha = AppAlpha.readDim) else cs.onSurfaceVariant
        val descStyle = SpanStyle(
            color = descColor,
            fontSize = bodySmall.fontSize,
            letterSpacing = bodySmall.letterSpacing
        )
        renderItemLine(item.title, item.desc, descStyle)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (item.url.isNotBlank()) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 5.dp)
    ) {
        // 序号列:24dp 固定槽 + 12dp 间距,与 Top10/关注段正文起点成列(54dp);
        // 字形/源强调色保留分源身份,单行条目顶对齐微调贴首行视觉基线
        Text(
            text = "%02d".format(index + 1),
            style = AppText.bodySmall,
            fontWeight = FontWeight.Bold,
            color = accent,
            modifier = Modifier
                .width(24.dp)
                .padding(top = 2.dp)
        )
        Spacer(Modifier.size(12.dp))
        Text(
            // 不限行数:摘要条目在本区块即全文,完整可读(长尾靠「查看全部」承接)
            text = line,
            style = AppText.body,
            color = if (isRead) cs.onSurface.copy(alpha = AppAlpha.readDim) else cs.onSurface
        )
    }
}

/** v1 纯文本行(分源区块内,历史快照兼容):序号(同 24dp 槽)+ **加粗** 标记富文本,只读。 */
@Composable
internal fun SourcePlainLineRow(
    index: Int,
    line: String,
    accent: Color
) {
    val rich = remember(line) { renderRichLine(line) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 5.dp)
    ) {
        Text(
            text = "%02d".format(index + 1),
            style = AppText.bodySmall,
            fontWeight = FontWeight.Bold,
            color = accent,
            modifier = Modifier
                .width(24.dp)
                .padding(top = 2.dp)
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = rich,
            style = AppText.body,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
