package com.peng.ainewshub.ui.trends

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.data.SummaryRepository
import com.peng.ainewshub.data.TrendKeyword
import com.peng.ainewshub.data.TrendsDigest
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.BottomBarPillHeight
import com.peng.ainewshub.ui.components.BrandWordmark
import com.peng.ainewshub.ui.components.HairlineDivider
import com.peng.ainewshub.ui.components.RankBadge
import com.peng.ainewshub.ui.components.RankRowSkeletonList
import com.peng.ainewshub.ui.theme.AppText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 热词趋势 Tab 根屏 —— 流水线预生成的跨源热词榜(读归档 trends.json,统计
 * 为主 + 可选 AI 精修;与「总览」tab 同范式:流水线预生成、App 只读归档)。
 *
 * 结构(编辑风,去卡片化,与总览 Top10 平铺同语言):
 *  - 顶部时效 caption:「近 N 天热词 · 数据截至 M月d日」(归档每日跑批,先交代新鲜度)
 *  - 热词榜平铺:[RankBadge] + 排名变化小字(较昨日:+N/-N/持平/新上榜)+ 热词 +
 *    命中统计 + 14 日 sparkline(Canvas 手绘,不引图表库)+ 涨跌箭头;行间 0.5dp 发丝线(缩进对齐文字列)
 *  - 点击词条整行展开 ≤3 条代表条目(浅底通栏带,标题点击经 openUrl 进内置 WebView)
 *  - 页脚:生成时间
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(
    onOpenUrl: (url: String, title: String, source: String) -> Unit,
    // 词云二级页入口(TabRoot 分支内经 nav.push 构造;入口在 caption 行)
    onOpenCloud: () -> Unit,
    // 列表状态由 MainActivity 上提持有:切 tab / 进二级页返回后保持滚动位置
    listState: LazyListState,
    reselectSignal: Int = 0,
    vm: TrendsViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()

    // 重击当前 tab:滚回顶部 + 重读归档(命中 trends.json 2 分钟缓存零开销)。
    // lastHandled 防「重新进入组合就自动刷新」(同总览 tab 套路)。
    var lastHandledReselect by remember { mutableIntStateOf(reselectSignal) }
    LaunchedEffect(reselectSignal) {
        if (reselectSignal != lastHandledReselect) {
            lastHandledReselect = reselectSignal
            listState.animateScrollToItem(0)
            vm.load()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            // 一级根 tab 规格:品牌 wordmark;刷新收口到下拉手势,日期仅总览 tab 保留。
            // 关注入口已升为独立根 tab,顶栏不再放 action。
            AppTopBar(
                title = "AI NEWS HUB",
                titleContent = {
                    BrandWordmark(modifier = Modifier.height(44.dp))
                },
                horizontalPadding = 18.dp
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 列表可滚入药丸 TAB 之下,但可视区不超出药丸底缘(同总览)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            when (val s = state) {
                is TrendsState.Loading -> TrendsLoading()
                is TrendsState.NoData -> EmptyState(
                    title = stringResource(R.string.trends_no_data_title),
                    subtitle = stringResource(R.string.trends_no_data_subtitle),
                    icon = Icons.Outlined.HourglassEmpty,
                    actionLabel = stringResource(R.string.common_retry),
                    onAction = { vm.load() }
                )
                is TrendsState.Error -> ErrorState(
                    message = s.message,
                    onRetry = { vm.load() },
                    title = stringResource(R.string.trends_load_failed)
                )
                is TrendsState.Success -> PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { vm.refresh() }
                ) {
                    TrendsContent(
                        digest = s.digest,
                        listState = listState,
                        onOpenUrl = onOpenUrl,
                        onOpenCloud = onOpenCloud
                    )
                }
            }
        }
    }
}

/** 加载中:与内容态同构的排名行骨架,避免转圈→内容态的结构跳变。 */
@Composable
private fun TrendsLoading() {
    val cs = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = BottomBarPillHeight + 16.dp)
    ) {
        item(key = "rows_skeleton") {
            RankRowSkeletonList(count = 8)
        }
        item(key = "loading_hint") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    color = cs.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.trends_loading),
                    style = AppText.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 热词榜内容渲染 —— 趋势 Tab 与「历史热词」日期页共用。
 *
 * [bottomReserve]:根 tab 为 true(末项可停到浮动药丸之上:药丸高 + 16dp 呼吸
 * 空间);二级页为 false(无浮动底栏,不留底部预留)。
 * [onOpenCloud]:词云页入口,仅趋势根 tab 传入(caption 行右侧出现「词云 ›」
 * 链接);历史日期页保持 null 不显示入口。
 */
@Composable
internal fun TrendsContent(
    digest: TrendsDigest,
    listState: LazyListState,
    onOpenUrl: (url: String, title: String, source: String) -> Unit,
    onOpenCloud: (() -> Unit)? = null,
    bottomReserve: Boolean = true
) {
    val context = LocalContext.current
    // 当前展开的词条(单展开,再点收起);瞬态 UI 状态,切 tab 丢失可接受
    var expandedTerm by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = if (bottomReserve) BottomBarPillHeight + 16.dp else 0.dp)
    ) {
        // 顶部时效 caption:窗口 + 数据截至(归档每日跑批,先交代新鲜度);
        // 根 tab 在行尾带「词云 ›」入口链接(顶栏无 actions,入口收进内容区)
        item(key = "caption", contentType = "caption") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.trends_window_caption,
                        digest.windowDays,
                        formatDay(context, digest.days.lastOrNull().orEmpty())
                    ),
                    style = AppText.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (onOpenCloud != null) {
                    Row(
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(onClick = onOpenCloud)
                            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.trends_cloud_entry),
                            style = AppText.caption,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        val keywords = digest.keywords
        itemsIndexed(
            keywords,
            key = { _, k -> "kw-${k.term}" },
            contentType = { _, _ -> "keyword" }
        ) { index, keyword ->
            Column {
                KeywordRow(
                    rank = index + 1,
                    keyword = keyword,
                    expanded = expandedTerm == keyword.term,
                    onToggle = {
                        expandedTerm = if (expandedTerm == keyword.term) null else keyword.term
                    },
                    onOpenUrl = onOpenUrl
                )
                if (index < keywords.lastIndex) {
                    HairlineDivider(startIndent = 54.dp)
                }
            }
        }

        // generatedAt 缺省 0(异常数据)时不渲染页脚,避免显示成 1970 年的时刻
        if (digest.generatedAt > 0) {
            item(key = "footer", contentType = "footer") {
                Text(
                    text = stringResource(R.string.trends_generated_at, formatClock(digest.generatedAt)),
                    style = AppText.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }
    }
}

/**
 * 热词行:[RankBadge] + 排名变化小字 + 热词/命中统计 + sparkline + 涨跌箭头。
 * 展开时下方带出代表条目浅底通栏带(对齐总览 breaking 色带语言:无卡片、色带边缘分隔)。
 */
@Composable
private fun KeywordRow(
    rank: Int,
    keyword: TrendKeyword,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenUrl: (url: String, title: String, source: String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 代表条目为空时禁点(展开无内容,不给无反馈的点击)
                .clickable(enabled = keyword.items.isNotEmpty(), onClick = onToggle)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 排名徽章 + 其下排名变化小字,固定 24dp 宽(与徽章同宽,行间文字列对齐)
            Column(
                modifier = Modifier.width(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RankBadge(rank = rank)
                Spacer(Modifier.height(2.dp))
                RankChangeLabel(keyword = keyword)
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = keyword.display,
                    style = AppText.body,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.trends_hits_meta, keyword.total, keyword.daysActive),
                    style = AppText.caption,
                    color = cs.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(Modifier.size(12.dp))
            Sparkline(
                values = keyword.daily,
                color = cs.primary,
                modifier = Modifier.size(width = 64.dp, height = 24.dp)
            )
            Spacer(Modifier.size(8.dp))
            TrendArrow(trend = keyword.trend)
        }
        if (expanded && keyword.items.isNotEmpty()) {
            KeywordItems(keyword = keyword, onOpenUrl = onOpenUrl)
        }
    }
}

/** 涨跌箭头:up=primary ↗ / down=tertiary ↘ / flat=弱色 –(近 3 日 vs 前 3 日命中和)。 */
@Composable
private fun TrendArrow(trend: String) {
    val cs = MaterialTheme.colorScheme
    val (icon, tint) = when (trend) {
        "up" -> Icons.AutoMirrored.Filled.TrendingUp to cs.primary
        "down" -> Icons.AutoMirrored.Filled.TrendingDown to cs.tertiary
        else -> Icons.Filled.Remove to cs.onSurfaceVariant
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(16.dp)
    )
}

/**
 * 排名变化小字(排名徽章下方,较昨日最后一期榜单):新上榜=primary「新」
 * (与上升同色——都是偏正面的信号,error 红只留给错误与下降语义)/
 * 上升=primary +N / 下降=tertiary -N / 持平=弱色。rankChange 为 null 且非
 * 新上榜时是流水线无历史基准(首期),不显示。
 */
@Composable
private fun RankChangeLabel(keyword: TrendKeyword) {
    val cs = MaterialTheme.colorScheme
    val rc = keyword.rankChange
    if (keyword.isNewEntry) {
        RankChangeText(stringResource(R.string.trends_rank_new), cs.primary)
    } else if (rc != null) {
        when {
            rc > 0 -> RankChangeText(stringResource(R.string.trends_rank_up, rc), cs.primary)
            rc < 0 -> RankChangeText(stringResource(R.string.trends_rank_down, -rc), cs.tertiary)
            else -> RankChangeText(stringResource(R.string.trends_rank_flat), cs.onSurfaceVariant)
        }
    }
}

/** 排名变化小字本体:caption 单行,由外层 24dp Column 负责居中。 */
@Composable
private fun RankChangeText(text: String, color: Color) {
    Text(
        text = text,
        style = AppText.caption,
        color = color,
        maxLines = 1
    )
}

/**
 * 14 日热度 sparkline —— Canvas 手绘折线(不引图表库):
 * 归一化到组件高度,末点圆点收束;全零/单点退化为水平线。
 */
@Composable
private fun Sparkline(
    values: List<Int>,
    color: Color,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        if (values.isEmpty()) return@Canvas
        val maxV = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        val stepX = if (values.size > 1) size.width / (values.size - 1) else 0f
        // 纵向留 15% 呼吸,避免折线贴边
        val usableH = size.height * 0.7f
        val topPad = size.height * 0.15f
        fun pointAt(i: Int): Offset {
            val x = if (values.size > 1) stepX * i else size.width / 2f
            val y = topPad + usableH * (1f - values[i].toFloat() / maxV)
            return Offset(x, y)
        }
        val path = androidx.compose.ui.graphics.Path()
        values.indices.forEach { i ->
            val p = pointAt(i)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        // 末点圆点(最新一日)
        val last = pointAt(values.lastIndex)
        drawCircle(color = color, radius = 2.5.dp.toPx(), center = last)
    }
}

/** 展开的代表条目带:surfaceContainerLow 浅底通栏,标题点击进内置 WebView。 */
@Composable
private fun KeywordItems(
    keyword: TrendKeyword,
    onOpenUrl: (url: String, title: String, source: String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surfaceContainerLow)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        keyword.items.forEachIndexed { i, item ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onOpenUrl(item.url, item.title, SummaryRepository.titleOf(context, item.source))
                    }
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = item.title,
                    style = AppText.bodySmall,
                    color = cs.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${SummaryRepository.titleOf(context, item.source)} · ${formatDay(context, item.date)}",
                    style = AppText.caption,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (i < keyword.items.lastIndex) {
                HairlineDivider()
            }
        }
    }
}

/** 窗口日期(yyyy-MM-dd)格式化为「M月d日」;解析失败原样返回。趋势 Tab 与词云页共用。 */
internal fun formatDay(context: Context, day: String): String =
    runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(day)
        if (parsed != null) {
            SimpleDateFormat(context.getString(R.string.date_fmt_month_day), Locale.getDefault()).format(parsed)
        } else day
    }.getOrDefault(day)

/** 生成时刻格式化为「HH:mm」。趋势 Tab 与词云页共用。 */
internal fun formatClock(ms: Long): String =
    runCatching { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms)) }.getOrDefault("")
