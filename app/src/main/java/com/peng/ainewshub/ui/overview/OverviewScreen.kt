package com.peng.ainewshub.ui.overview

import android.content.Context
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
 *  - 首屏 digest Hero:[BrandGradient] 通栏 = 「今日综述」label + digest 正文
 *    + 刊名/时效 caption(digest 空串退化为纯文本时效行)——首屏焦点即 AI 综合产物
 *    (权重反转:渐变焦点从单条新闻收口到综述,对齐「渐变只用于 AI 特性」纪律)
 *  - Top10 平铺列表:无卡片容器、无头条特殊位,行间 0.5dp 发丝线(缩进对齐文字列);
 *    breaking 条目带「突发」标签,推荐理由为左侧 2dp 竖条引述块
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
        // 首屏 digest Hero:今日综述 + 时效 caption(两者都缺失时不占位)
        if (digest.dataFetchedAt > 0 || digest.digest.isNotBlank()) {
            item(key = "lead", contentType = "lead") {
                OverviewLead(digest = digest)
            }
        }

        // Top10 全量平铺(去卡片,无头条特殊位;breaking 条目数据层已排最前,
        // 由「Breaking」标签承接强调)。行间不画线,靠行自身 14dp 纵向 padding 留白分层
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
 * 视觉(纸墨日报):双细线起头 → 报纸红 letterspaced「今日综述」栏目名 →
 * 衬线正文 → 底部发丝线收边。
 *
 * digest 折叠:长综述默认收 [DIGEST_COLLAPSED_LINES] 行,仅溢出时出现「展开/收起」
 * (onTextLayout 检测,短综述不渲染按钮);折叠态为瞬态 remember —— push Web 页
 * 返回后回落折叠(AnimatedContent 换页销毁页内 remember,项目无 SaveableStateHolder,
 * 与「默认折叠」意图一致)。
 *
 * 时效:归档一天只更数批,首屏让用户知道两件事——数据新鲜度(数据截至)与下次
 * 更新预期(下一批,批次唯一真相源 [PipelineSchedule]);「数据截至」由本区独占展示,
 * 页脚不再重复。digest 空串但 dataFetchedAt > 0(旧归档)退化为纯文本时效 caption。
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
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                Text(
                    text = stringResource(R.string.overview_digest_title),
                    style = AppText.caption,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.primary,
                    letterSpacing = 3.sp
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
                if (digest.dataFetchedAt > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = freshnessCaption(context, digest),
                        style = AppText.caption,
                        color = cs.onSurfaceVariant
                    )
                }
            }
        }
    } else if (digest.dataFetchedAt > 0) {
        // 旧归档无 digest 字段:退化为纯文本时效 caption,不占版面
        Text(
            text = freshnessCaption(context, digest),
            style = AppText.caption,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}

/** digest 折叠行数(展开后不限)。 */
private const val DIGEST_COLLAPSED_LINES = 6

/**
 * 时效 caption:「刊名 · 数据截至 X · 下一批 Y」。刊名由数据自身 generatedAt 映射
 * 槽位序号(数据是哪批的就是哪刊,不拿当前时刻冒充);下一批由批次唯一真相源
 * [PipelineSchedule.nextBatchEpoch] 算出,按设备时区格式化(与「数据截至」
 * 同口径:北京定义、本地显示);格式化失败退化为不含下一批的短句。
 */
private fun freshnessCaption(context: Context, digest: OverviewDigest): String {
    val fetchedAt = formatFetchedAt(context, digest.dataFetchedAt)
    val nextBatch = runCatching {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(PipelineSchedule.nextBatchEpoch()))
    }.getOrDefault("")
    return if (nextBatch.isNotEmpty()) {
        val edition = editionLabel(context, PipelineSchedule.slotIndexOn(digest.generatedAt))
        context.getString(R.string.overview_freshness_caption, edition, fetchedAt, nextBatch)
    } else {
        context.getString(R.string.overview_data_until, fetchedAt)
    }
}

/** 「Breaking」标签 —— breaking 条目卡内的小胶囊(tertiary 实底,热度强调色)。 */
@Composable
private fun BreakingTag(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(cs.primary)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(R.string.overview_breaking_tag),
            style = AppText.caption,
            fontWeight = FontWeight.Bold,
            color = cs.onPrimary
        )
    }
}

/**
 * 平铺热点行(1~10 名):[RankBadge] + 原标题 + AI 一句话 + 来源/指标,无卡片容器。
 * breaking 条目仅以「Breaking」标签提示,不加整行特殊背景;
 * 推荐理由为左侧 2dp 竖条引述块(原「卡中卡」面板随卡片容器一并去除)。
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        RankBadge(rank = rank, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (entry.breaking) {
                BreakingTag()
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = entry.title,
                style = AppText.body,
                fontFamily = FontFamily.Serif,
                // 头条升权:第 1 名 16sp SemiBold(大节头 17 之下、普通条目 14 之上),
                // 「今日重点」的重点感由它承担;衬线大字是报纸头版语言
                fontSize = if (rank == 1) 16.sp else AppText.body.fontSize,
                fontWeight = if (rank == 1) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isRead) cs.onSurface.copy(alpha = AppAlpha.readDim) else cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.comment.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = entry.comment,
                    style = AppText.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Breaking 专属「推荐理由」引述块:左侧 2dp tertiary 竖条 + 标签正文单 Text
            // 顺排(IntrinsicSize.Min 让竖条与文字等高)。
            // 与 comment 语义区分:comment=为什么重要,推荐理由=为什么是突发。
            if (entry.breaking && entry.breakingReason.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                val reasonLabel = stringResource(R.string.overview_breaking_reason_label)
                val reason = remember(entry.breakingReason, cs.tertiary, reasonLabel) {
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = cs.tertiary, fontWeight = FontWeight.SemiBold)) {
                            append("$reasonLabel ")
                        }
                        append(entry.breakingReason)
                    }
                }
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .clip(MaterialTheme.shapes.small)
                            .background(cs.tertiary)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = reason,
                        style = AppText.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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

/** 今天日期(系统时区),中文格式「M月d日 · 周x」,与「今天」页报头日期行同规格;模式串走 date_fmt_month_day_week。 */
internal fun formatToday(context: Context): String =
    runCatching {
        SimpleDateFormat(context.getString(R.string.date_fmt_month_day_week), Locale.getDefault()).format(Date())
    }.getOrDefault("")

/** 生成时刻格式化为「HH:mm」。 */
private fun formatClock(ms: Long): String =
    runCatching { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms)) }.getOrDefault("")

/** 数据时刻格式化(中文「M月d日 HH:mm」,与摘要卡头同规格);模式串走 date_fmt_month_day_time。 */
private fun formatFetchedAt(context: Context, ms: Long): String =
    runCatching {
        SimpleDateFormat(context.getString(R.string.date_fmt_month_day_time), Locale.getDefault()).format(Date(ms))
    }.getOrDefault(context.getString(R.string.overview_time_unknown))
