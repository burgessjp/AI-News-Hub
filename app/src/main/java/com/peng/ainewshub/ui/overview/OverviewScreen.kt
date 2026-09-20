package com.peng.ainewshub.ui.overview

import android.content.Context
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peng.ainewshub.R
import com.peng.ainewshub.data.repo.OverviewDigest
import com.peng.ainewshub.data.repo.OverviewEntry
import com.peng.ainewshub.data.PipelineSchedule
import com.peng.ainewshub.data.repo.SummaryRepository
import com.peng.ainewshub.ui.components.BottomBarPillHeight
import com.peng.ainewshub.ui.components.editionLabel
import com.peng.ainewshub.ui.components.RankBadge
import com.peng.ainewshub.ui.components.rememberReadUrls
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 总览内容共享件 —— 「今天」根屏(TodayScreen)与「历史总览」日期页(OverviewDateScreen)
 * 共用的渲染实现。
 *
 * 原 OverviewScreen 根屏已随 v1.4.0 日刊化并入 [TodayScreen](综述 Hero + Top10 +
 * 分源摘要区块的垂直日报);本文件只保留共享渲染件:
 *  - [OverviewContent]:历史总览日期页的整页渲染(hero + Top10 + 页脚,自持 LazyColumn)
 *  - [OverviewLead] / [TopEntryRow] / [OverviewFooter] 等:TodayScreen 逐 item 复用
 *
 * 结构(编辑风,去卡片化):
 *  - 首屏 digest Hero:刊期标签行(日期/刊名/数据截至)+ 衬线 digest 正文
 *    (digest 空串退化为纯文本刊期行)——首屏焦点即 AI 综合产物
 *    (权重反转:渐变焦点从单条新闻收口到综述,对齐「渐变只用于 AI 特性」纪律)
 *  - Top10 平铺列表:无卡片容器、无头条特殊位,行间不画线、靠行距分层;
 *    breaking 条目带红色「头条 ·」内联前缀,描述位由推荐理由顶替 AI 一句话
 *  - 页脚:生成时间 / 基于源数 / 缺源标注(刊名/「数据截至」已由首屏 Hero 承载,不重复)
 */

/**
 * 总览内容列表(digest Hero + Top10 平铺 + 页脚)。
 *
 * 「今天」页与「历史总览」日期页共用(后者复用同一渲染,仅差底部预留):
 *
 * @param bottomReserve true 预留浮动药丸底栏高度(根 tab);false 为二级页
 *        (无悬浮底栏),只留呼吸空间
 */
@Composable
internal fun OverviewContent(
    digest: OverviewDigest,
    listState: LazyListState,
    onOpenUrl: (url: String, title: String, source: String) -> Unit,
    bottomReserve: Boolean = true
) {
    // LocalContext.current 只能在 @Composable 上下文取,提前取出供回调内复用
    val context = LocalContext.current
    // 已读判定:打开过的条目(entry.url 命中浏览历史)标题弱化
    val readUrls = rememberReadUrls()
    // Top10 稳定 key:url 优先(breaking 前移等排序变化时走 move 复用而非销毁重建),
    // 重复/空 url 以出现序号消歧保证唯一(重复 key 会直接崩溃)
    val topKeys = remember(digest.items) {
        val seen = mutableMapOf<String, Int>()
        digest.items.map { e ->
            val base = e.url.ifBlank { "top" }
            val dup = seen.getOrPut(base) { 0 }
            seen[base] = dup + 1
            base + if (dup == 0) "" else "#$dup"
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 根 tab 末项可停到药丸之上(药丸高 + 16dp 呼吸空间,列表本身可滚入药丸之下,
        // 容器已按药丸底缘裁剪);二级页无悬浮底栏,只留呼吸空间
        contentPadding = PaddingValues(
            bottom = if (bottomReserve) BottomBarPillHeight + 16.dp else 24.dp
        )
    ) {
        // 首屏 digest Hero:综述正文 + 刊期标签行(两者都缺失时不占位)
        if (digest.dataFetchedAt > 0 || digest.digest.isNotBlank()) {
            item(key = "lead", contentType = "lead") {
                OverviewLead(digest = digest)
            }
        }

        // Top10 全量平铺(去卡片,无头条特殊位;breaking 条目数据层已排最前,
        // 由「头条」内联标签承接强调)。行间不画线,靠行自身 10dp 纵向 padding 留白分层
        val items = digest.items
        itemsIndexed(
            items,
            key = { i, _ -> topKeys[i] },
            contentType = { _, e -> if (e.breaking) "top10-breaking" else "top10" }
        ) { index, entry ->
            TopEntryRow(
                rank = index + 1,
                entry = entry,
                isRead = entry.url in readUrls,
                onClick = { onOpenUrl(entry.url, entry.title, SummaryRepository.titleOf(context, entry.source)) }
            )
        }

        item(key = "footer", contentType = "footer") {
            OverviewFooter(digest = digest)
        }
    }
}

/**
 * 首屏 digest Hero —— 跨源「今日综述」的页面焦点区(权重反转:原头条渐变 Hero 已去除,
 * 渐变焦点从单条新闻收口到 AI 综合产物,对齐 Color.kt「渐变只用于 AI 特性」纪律)。
 *
 * 视觉(纸墨日报):报纸红刊期行「M月d日 · 周x · 刊名 · 数据截至」(日期/时效
 * 信息收拢于此,报头不再有日期副标题)→ 衬线正文。
 *
 * digest 折叠:长综述默认收 [DIGEST_COLLAPSED_LINES] 行,仅溢出时出现「展开/收起」
 * (onTextLayout 检测,短综述不渲染按钮);折叠态为瞬态 remember —— push Web 页
 * 返回后回落折叠(AnimatedContent 换页销毁页内 remember,项目无 SaveableStateHolder,
 * 与「默认折叠」意图一致)。
 *
 * digest 空串但 dataFetchedAt > 0(旧归档)退化为纯文本刊期行。
 */
@Composable
internal fun OverviewLead(digest: OverviewDigest) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    if (digest.digest.isNotBlank()) {
        var expanded by remember { mutableStateOf(false) }
        // 折叠态检测溢出:仅在折叠布局回调里读 hasVisualOverflow,展开后不回写
        var digestOverflowed by remember { mutableStateOf(false) }
        Column(modifier = Modifier.fillMaxWidth()) {
            // 纸墨日报 Hero:红色 letterspaced 栏目名 → 衬线正文 → 时效 caption,
            // 无起收边线(线收敛:结构线只留各页报头一处;正文用系统衬线,
            // 缺 CJK 衬线的 ROM 优雅降级无衬线)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 6.dp)
            ) {
                Text(
                    // 标签行即刊期行:日期/刊名/数据截至收在这一行(原报头日期副标题
                    // 与本区底部时效 caption 的信息合并上移);日期取数据自身批次
                    text = digestHeadline(context, digest),
                    style = AppText.caption,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.primary,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = digest.digest,
                    style = AppText.body,
                    fontFamily = FontFamily.Serif,
                    color = cs.onSurface,
                    lineHeight = 24.sp, // 综述衬线正文行高(纸墨版面规格,有意宽于正文档)
                    maxLines = if (expanded) Int.MAX_VALUE else DIGEST_COLLAPSED_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) digestOverflowed = it.hasVisualOverflow }
                )
                // 展开/收起:仅长综述渲染(短文无按钮);文案按钮 + onClickLabel 供读屏
                if (digestOverflowed) {
                    val toggleLabel = stringResource(
                        if (expanded) R.string.overview_digest_collapse else R.string.overview_digest_expand
                    )
                    Text(
                        text = toggleLabel,
                        style = AppText.caption,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.primary,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(onClickLabel = toggleLabel) { expanded = !expanded }
                            .padding(vertical = 2.dp)
                    )
                }
            }
        }
    } else if (digest.dataFetchedAt > 0) {
        // 旧归档无 digest 字段:退化为纯文本刊期行,不占版面
        Text(
            text = digestHeadline(context, digest),
            style = AppText.caption,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}

/** digest 折叠行数(展开后不限)。 */
private const val DIGEST_COLLAPSED_LINES = 6

/**
 * 综述区刊期行:「M月d日 · 周x · 刊名 · 数据截至 HH:mm」。
 * 日期与刊名取数据自身批次时间(generatedAt,数据是哪批的就是哪天)——历史总览
 * 日期页复用本组件时显示的是那一天刊期,不拿当前时刻冒充;generatedAt 缺失时
 * 依次退到 dataFetchedAt。截至时刻来自 dataFetchedAt(≤0 的旧归档省略尾段)。
 * 原报头日期副标题与综述下时效 caption 的信息都收拢到这一行。
 */
private fun digestHeadline(context: Context, digest: OverviewDigest): String {
    val basis = when {
        digest.generatedAt > 0 -> digest.generatedAt
        digest.dataFetchedAt > 0 -> digest.dataFetchedAt
        else -> System.currentTimeMillis()
    }
    val datePart = runCatching {
        SimpleDateFormat(context.getString(R.string.date_fmt_month_day_week), Locale.getDefault()).format(Date(basis))
    }.getOrDefault("")
    val edition = editionLabel(context, PipelineSchedule.slotIndexOn(basis))
    val dateEdition = if (datePart.isEmpty()) edition else "$datePart · $edition"
    if (digest.dataFetchedAt <= 0) return dateEdition
    val asOf = runCatching {
        SimpleDateFormat(context.getString(R.string.date_fmt_clock), Locale.getDefault()).format(Date(digest.dataFetchedAt))
    }.getOrDefault("")
    return if (asOf.isEmpty()) dateEdition
    else "$dateEdition · " + context.getString(R.string.overview_data_until, asOf)
}

/**
 * 平铺热点行(1~10 名):[RankBadge] + 原标题 + 描述 + 来源/指标,无卡片容器。
 * breaking 条目以红色「头条 ·」文字前缀内联在标题行首(随标题折行,不独占一行、无背景);
 * 其描述位由推荐理由顶替 AI 一句话(独立的引述块不再渲染,行更紧凑)。
 */
@Composable
internal fun TopEntryRow(
    rank: Int,
    entry: OverviewEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // 已读(打开过原文)时标题降透明弱化;推荐理由等强调不受影响
    isRead: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    // 「头条」内联前缀(读屏可读):纯文字红色 SemiBold +「·」分隔,无背景——
    // 与全 App 纯文字刊物语言一致,也免去行内背景按行高涂色的留白问题
    val tagText = stringResource(R.string.overview_breaking_tag)
    val title = remember(entry.title, entry.breaking, tagText, cs.primary) {
        buildAnnotatedString {
            if (entry.breaking) {
                withStyle(
                    SpanStyle(
                        color = cs.primary,
                        fontWeight = FontWeight.Bold
                    )
                ) { append("$tagText · ") }
            }
            append(entry.title)
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        RankBadge(rank = rank, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = AppText.body,
                fontFamily = FontFamily.Serif,
                color = if (isRead) cs.onSurface.copy(alpha = AppAlpha.readDim) else cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // 描述位:breaking 条目由推荐理由顶替 AI 一句话(信息更锐利,
            // 原独立引述块随密度优化并入此行)
            val bodyText = when {
                entry.breaking && entry.breakingReason.isNotBlank() -> entry.breakingReason
                else -> entry.comment
            }
            if (bodyText.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = bodyText,
                    style = AppText.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(6.dp))
            // 文末署名行(报纸署名语言):「—— 源名 · 指标」小灰字,破折号前缀把
            // meta 从上方 comment 内容行里剥出来;无胶囊底衬、无图标(胶囊是 App
            // 语言且自带分隔噪音,与线收敛的「以白当黑」相悖)
            val sourceTitle = SummaryRepository.titleOf(LocalContext.current, entry.source)
            Text(
                text = "—— " + if (entry.metrics.isBlank()) sourceTitle
                       else sourceTitle + " · " + entry.metrics,
                style = AppText.caption,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 页脚:生成时间 / 基于源数 / 缺源标注(「数据截至」已在首屏 digest Hero 展示,此处不重复)。 */
@Composable
internal fun OverviewFooter(digest: OverviewDigest) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    // 各片段在进 buildString 前算好(stringResource 只能在 @Composable 上下文调)
    val generatedText = stringResource(R.string.overview_generated_at, formatClock(digest.generatedAt))
    val sourceCount = SummaryRepository.SOURCE_KEYS.size
    val basedOnText = pluralStringResource(R.plurals.overview_based_on_sources, sourceCount, sourceCount)
    val listSeparator = stringResource(R.string.overview_list_separator)
    val missingText = if (digest.missingSources.isNotEmpty()) {
        stringResource(
            R.string.overview_missing_sources,
            digest.missingSources.joinToString(listSeparator) { SummaryRepository.titleOf(context, it) }
        )
    } else {
        null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = generatedText,
            style = AppText.caption,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = buildString {
                append(basedOnText)
                if (missingText != null) append(" · $missingText")
            },
            style = AppText.caption,
            color = cs.onSurfaceVariant
        )
    }
}

/** 生成时刻格式化为「HH:mm」。 */
private fun formatClock(ms: Long): String =
    runCatching { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms)) }.getOrDefault("")
