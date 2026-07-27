package com.peng.ainewshub.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.data.OverviewDigest
import com.peng.ainewshub.data.OverviewEntry
import com.peng.ainewshub.data.SummaryRepository
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.BottomBarPillHeight
import com.peng.ainewshub.ui.components.BrandWordmark
import com.peng.ainewshub.ui.components.RankBadge
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText
import com.peng.ainewshub.ui.theme.BrandGradient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 今日总览 Tab 根屏 —— 流水线预生成的跨源综合分析(读归档 latest_overview 字段)。
 *
 * 结构(A+B 混合编辑风,去卡片化):
 *  - 头条 Hero:第 1 条(breaking 条目数据层已排最前)以 [BrandGradient] 通栏
 *    大字号呈现,无圆角无描边;渐变属 AI 特性专用,本页整体即 AI 特性,合规
 *  - 2~10 名平铺列表:无卡片容器,行间 0.5dp 发丝线(缩进对齐文字列);
 *    breaking 条目整行 tertiary 浅底通栏 + 「Breaking」标签,推荐理由改左侧
 *    2dp 竖条引述块;与浅底带相邻的行间不画分隔线,由色带边缘自然分隔
 *  - 页脚:生成时间 / 数据截至 / 缺源标注
 *
 * 与「摘要」tab 同范式:都读流水线预生成的归档字段,App 端不再调 AI。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    onOpenUrl: (url: String, title: String, source: String) -> Unit,
    // 列表状态由 MainActivity 上提持有:切 tab / 进二级页返回后保持滚动位置
    listState: LazyListState,
    reselectSignal: Int = 0,
    vm: OverviewViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()

    // 重击当前 tab:滚回顶部 + 缓存感知刷新(指纹未变零开销,归档更新才重新生成)。
    // lastHandled 防「重新进入组合就自动刷新」(同摘要 tab 套路)。
    var lastHandledReselect by remember { mutableIntStateOf(reselectSignal) }
    LaunchedEffect(reselectSignal) {
        if (reselectSignal != lastHandledReselect) {
            lastHandledReselect = reselectSignal
            listState.animateScrollToItem(0)
            vm.load()
        }
    }

    val dateText = remember { formatToday() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            // 一级根 tab 规格(对齐摘要/更多):品牌 wordmark + 右侧日期 + 刷新
            AppTopBar(
                title = "AI NEWS HUB",
                titleContent = {
                    // 品牌字标(矢量,跟随设置页自选的深/浅主题),替换原纯文字标题
                    BrandWordmark(modifier = Modifier.height(44.dp))
                },
                horizontalPadding = 18.dp,
                actions = {
                    // 刷新按钮在左(刷新中转圈),日期文案在右;
                    // 转圈与按钮同占 32dp,保证与日期文案的间距两种状态下一致
                    if (isRefreshing) {
                        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        IconButton(onClick = { vm.refresh() }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "重新生成",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        text = dateText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 列表可滚入药丸 TAB 之下,但可视区不超出药丸底缘
                // (与 MainActivity 底栏定位一致:navigationBarsPadding + 距底 16dp),
                // 内容不再透出到药丸与系统导航栏之间的间隙
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            when (val s = state) {
                is OverviewState.Loading -> OverviewLoading()
                is OverviewState.NoData -> EmptyState(
                    title = "今日总览尚未生成",
                    subtitle = "今天的内容还在准备中,请稍后再来",
                    icon = Icons.Outlined.HourglassEmpty,
                    actionLabel = "重试",
                    onAction = { vm.load() }
                )
                is OverviewState.Error -> ErrorState(
                    message = s.message,
                    onRetry = { vm.load() },
                    title = "总览加载失败"
                )
                is OverviewState.Success -> OverviewContent(
                    digest = s.digest,
                    listState = listState,
                    onOpenUrl = onOpenUrl
                )
            }
        }
    }
}

/** 加载中:转圈 + 预期耗时说明(AI 长输出,避免用户误以为卡死)。 */
@Composable
private fun OverviewLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "正在加载今日总览…",
            style = AppText.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OverviewContent(
    digest: OverviewDigest,
    listState: LazyListState,
    onOpenUrl: (url: String, title: String, source: String) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 末项可停到药丸之上:药丸高 + 16dp 呼吸空间
        // (列表本身可滚入药丸之下,容器已按药丸底缘裁剪)
        contentPadding = PaddingValues(bottom = BottomBarPillHeight + 16.dp)
    ) {
        // 头条 Hero:第 1 条(breaking 条目数据层已排最前)BrandGradient 通栏大字号
        digest.items.firstOrNull()?.let { first ->
            item(key = "hero-${first.url}", contentType = "hero") {
                OverviewHero(
                    entry = first,
                    onClick = { onOpenUrl(first.url, first.title, SummaryRepository.titleOf(first.source)) }
                )
            }
        }

        // 2~10 名平铺(去卡片)。发丝线仅在同类型相邻行间绘制;与 breaking
        // 浅底带相邻时不画,由色带边缘自然分隔
        val rest = digest.items.drop(1)
        itemsIndexed(
            rest,
            key = { i, e -> "top-${i + 1}-${e.url}" },
            contentType = { _, e -> if (e.breaking) "top10-breaking" else "top10" }
        ) { index, entry ->
            Column {
                TopEntryRow(
                    rank = index + 2,
                    entry = entry,
                    onClick = { onOpenUrl(entry.url, entry.title, SummaryRepository.titleOf(entry.source)) }
                )
                val next = rest.getOrNull(index + 1)
                if (next != null && entry.breaking == next.breaking) {
                    RowDivider()
                }
            }
        }

        item(key = "footer", contentType = "footer") {
            OverviewFooter(digest = digest)
        }
    }
}

/** 「Breaking」标签 —— breaking 条目卡内的小胶囊(tertiary 实底,热度强调色)。 */
@Composable
private fun BreakingTag(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(cs.tertiary)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "Breaking",
            style = AppText.caption,
            fontWeight = FontWeight.Bold,
            color = cs.onTertiary
        )
    }
}

/**
 * 头条 Hero —— 第 1 条热点的通栏头版(A+B 混合设计的 B)。
 *
 * 视觉:full-bleed [BrandGradient],无圆角无描边,与下方平铺列表形成重量对比;
 * 渐变上文字/徽章一律 onPrimary 系(浅主题深渐变+白字,深主题浅渐变+深字,
 * 对比度说明见 theme/Color.kt)。
 * 内容:[RankBadge](1) + (breaking 时)[BreakingTag] / 大标题(titleSection)/
 * AI 点评 / breaking 推荐理由(onPrimaryOverlay 半透面板)/ 来源胶囊 + 指标。
 */
@Composable
private fun OverviewHero(entry: OverviewEntry, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandGradient)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RankBadge(rank = 1)
            if (entry.breaking) {
                Spacer(Modifier.width(8.dp))
                BreakingTag()
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = entry.title,
            style = AppText.titleSection,
            color = cs.onPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (entry.comment.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.comment,
                style = AppText.bodySmall,
                color = cs.onPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 推荐理由面板:onPrimaryOverlay 半透底(与 HotTopicsHeader 计数胶囊同语言)
        if (entry.breaking && entry.breakingReason.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            val reason = remember(entry.breakingReason, cs.onPrimary) {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = cs.onPrimary, fontWeight = FontWeight.SemiBold)) {
                        append("推荐理由 ")
                    }
                    append(entry.breakingReason)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(cs.onPrimary.copy(alpha = AppAlpha.onPrimaryOverlay))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = cs.onPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = reason,
                        style = AppText.bodySmall,
                        color = cs.onPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 来源胶囊:深底浅 chip(onPrimaryOverlay 底 + onPrimary 字)
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(cs.onPrimary.copy(alpha = AppAlpha.onPrimaryOverlay))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = SummaryRepository.titleOf(entry.source),
                    style = AppText.caption,
                    color = cs.onPrimary,
                    maxLines = 1
                )
            }
            if (entry.metrics.isNotBlank()) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = entry.metrics,
                    style = AppText.caption,
                    color = cs.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 平铺热点行(2~10 名):[RankBadge] + 原标题 + AI 一句话 + 来源/指标,无卡片容器。
 * breaking 条目:整行 tertiary 浅底通栏(无圆角描边)+「Breaking」标签,
 * 推荐理由为左侧 2dp 竖条引述块(原「卡中卡」面板随卡片容器一并去除)。
 */
@Composable
private fun TopEntryRow(
    rank: Int,
    entry: OverviewEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (entry.breaking) {
                    Modifier.background(cs.tertiary.copy(alpha = AppAlpha.badgeOverlay))
                } else {
                    Modifier
                }
            )
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
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
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
                val reason = remember(entry.breakingReason, cs.tertiary) {
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = cs.tertiary, fontWeight = FontWeight.SemiBold)) {
                            append("推荐理由 ")
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceChip(title = SummaryRepository.titleOf(entry.source))
                if (entry.metrics.isNotBlank()) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = entry.metrics,
                        style = AppText.caption,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** 行间发丝线:缩进对齐文字列(18 行 padding + 24 徽章 + 12 间距 = 54),与 HotTopicsSection 同语言。 */
@Composable
private fun RowDivider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(start = 54.dp, end = 18.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/** 来源徽章:surfaceContainerHigh 底衬小胶囊。 */
@Composable
private fun SourceChip(title: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text = title, style = AppText.caption, color = cs.onSurfaceVariant, maxLines = 1)
    }
}

/** 页脚:生成时间 / 数据截至 / 模型与 token / 缺源标注。 */
@Composable
private fun OverviewFooter(digest: OverviewDigest) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = buildString {
                append("生成于 ${formatClock(digest.generatedAt)}")
                if (digest.dataFetchedAt > 0) append(" · 数据截至 ${formatFetchedAt(digest.dataFetchedAt)}")
            },
            style = AppText.caption,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = buildString {
                append("基于 ${SummaryRepository.SOURCE_KEYS.size} 源当日内容")
                if (digest.missingSources.isNotEmpty()) {
                    append(" · 缺 ${digest.missingSources.joinToString("、") { SummaryRepository.titleOf(it) }}")
                }
            },
            style = AppText.caption,
            color = cs.onSurfaceVariant
        )
    }
}

/** 今天日期(系统时区),格式「M月d日 · 周x」,与摘要 tab 顶栏日期同规格。 */
private fun formatToday(): String =
    runCatching {
        SimpleDateFormat("M月d日 · E", Locale.CHINA).format(Date())
    }.getOrDefault("")

/** 生成时刻格式化为「HH:mm」。 */
private fun formatClock(ms: Long): String =
    runCatching { SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ms)) }.getOrDefault("")

/** 数据时刻格式化为「M月d日 HH:mm」(与摘要卡头同规格)。 */
private fun formatFetchedAt(ms: Long): String =
    runCatching { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ms)) }.getOrDefault("未知")
