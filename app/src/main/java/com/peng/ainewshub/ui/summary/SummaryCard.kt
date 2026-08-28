package com.peng.ainewshub.ui.summary

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.data.SourceFreshness
import com.peng.ainewshub.data.SourceKeys
import com.peng.ainewshub.data.SourceSummary
import com.peng.ainewshub.data.SummaryContent
import com.peng.ainewshub.data.SummaryItem
import com.peng.ainewshub.ui.ErrorKind
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.anim.Motion
import com.peng.ainewshub.ui.components.BottomBarPillHeight
import com.peng.ainewshub.ui.components.ShimmerBox
import com.peng.ainewshub.ui.components.ShimmerHost
import com.peng.ainewshub.ui.components.rememberReadUrls
import com.peng.ainewshub.ui.more.sourceMeta
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 摘要页共享件 —— 摘要 Tab(当日)与「历史摘要」按日期页(归档)共用的单页实现。
 *
 * 从 SummaryScreen 抽出:页 spec、顶部提示行+源名 chips 导航(两屏 pager 共用)、
 * 单张源摘要页(紧凑扁头 + 条目正文,平铺无卡片,「查看完整列表」出口收口在扁头;
 * v2 结构化条目 url 非空时整行可点直达原文)。两屏保持同构(同一产品语言),
 * 差异仅在数据来源(latest 快照 vs history 索引按日期寻址)
 * 与出口(历史页扁头纯展示、不可点)。
 */

/** 卡片配置:key → (标题 / 图标 / 进入列表的回调;历史页传 null,扁头纯展示、无出口)。 */
internal data class SummaryCardSpec(
    val source: String,
    val title: String,
    val icon: ImageVector,
    val onOpen: (() -> Unit)?
)

/**
 * 各摘要卡的统一配置。标题 / 图标 / 品牌色由 [sourceMeta] 单点定义,顺序跟随传入的 [keys]
 * (用户在「信息源」页自定义的顺序,默认全集顺序)。
 *
 * @param keys 卡片顺序(源 key 列表,来自 SummaryViewModel.sourceKeys)
 * @param onOpenFor 按源 key 给「查看完整列表」回调;返回 null 则该卡无出口、扁头不可点
 * (历史摘要按日期页:列表页展示的是当日数据,从历史跳转语义不符,故无出口)
 */
@Composable
internal fun summaryCardSpecs(
    keys: List<String>,
    onOpenFor: @Composable (source: String) -> (() -> Unit)?
): List<SummaryCardSpec> = keys.map { key ->
    val meta = sourceMeta(key)
    SummaryCardSpec(meta.key, meta.title, meta.icon, onOpenFor(key))
}

/**
 * 顶部区:上行 = 数据来源提示;下行 = 源名 chips 导航(横向可滚动)。
 *
 * chips 取代原圆点页指示器:圆点无语义,用户只能盲滑/盲点;源名 chip 让每页
 * 归属直接可见,点击即跳页。当前页 chip 为 primary 实底(其余 surfaceContainerHigh),
 * 颜色随切页 tween 过渡(Motion.SHORT);切页时自动横滚让当前 chip 进入可视区。
 * 带 [SummaryHeaderPage.hasUnread] 的 chip 尾部亮未读小圆点(今天哪个源有新东西
 * 一眼可辨;点开该源任一条目后经浏览历史 Flow 响应式熄灭),圆点槽位恒占位
 * 避免读/未读切换时 chip 宽度跳动。
 */
@Composable
internal fun SummaryHeaderRow(
    currentPage: Int,
    pages: List<SummaryHeaderPage>,
    hint: String = stringResource(R.string.summary_daily_hint),
    onSelect: (Int) -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = hint,
            style = AppText.caption,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp)
        )
        Spacer(Modifier.size(8.dp))
        val chipsState = rememberLazyListState()
        // 切页(含手势滑动)后把当前 chip 滚进可视区:8 个源 chip 总宽超出屏宽
        LaunchedEffect(currentPage) {
            if (currentPage in pages.indices) chipsState.animateScrollToItem(currentPage)
        }
        LazyRow(
            state = chipsState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(pages) { i, page ->
                val isCurrent = i == currentPage
                val bg by animateColorAsState(
                    targetValue = if (isCurrent) cs.primary else cs.surfaceContainerHigh,
                    animationSpec = tween(Motion.SHORT, easing = Motion.EmphasizedDecel),
                    label = "sourceChipBg"
                )
                val fg by animateColorAsState(
                    targetValue = if (isCurrent) cs.onPrimary else cs.onSurfaceVariant,
                    animationSpec = tween(Motion.SHORT, easing = Motion.EmphasizedDecel),
                    label = "sourceChipFg"
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(bg)
                        // selectable:向读屏声明 Tab 角色与选中态(pager 页选择器)
                        .selectable(selected = isCurrent, role = Role.Tab) { onSelect(i) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = page.title,
                            style = AppText.caption,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = fg,
                            maxLines = 1
                        )
                        Spacer(Modifier.size(4.dp))
                        // 未读小圆点:装饰性(文本已承载语义),槽位恒占位防宽度跳动
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (page.hasUnread) {
                                        if (isCurrent) cs.onPrimary else cs.primary
                                    } else {
                                        androidx.compose.ui.graphics.Color.Transparent
                                    }
                                )
                        )
                    }
                }
            }
        }
    }
}

/** [SummaryHeaderRow] 的 chip 模型:标题 + 该源是否存在未读条目。 */
internal data class SummaryHeaderPage(
    val title: String,
    val hasUnread: Boolean
)

/**
 * 源摘要未读判定(摘要根 tab 与历史摘要日期页共用):结构化条目中存在
 * 「可点 url 且未进浏览历史」的条目即未读。空 url 条目只读不可点、永远不算
 * 未读(否则无出口消化该圆点);加载中/失败/旧纯文本格式一律视为无未读。
 */
internal fun hasUnreadSource(state: UiState<SourceSummary>?, readUrls: Set<String>): Boolean {
    val structured = (state as? UiState.Success)?.data?.content as? SummaryContent.Structured
        ?: return false
    return structured.items.any { it.url.isNotBlank() && it.url !in readUrls }
}

/**
 * 单张源摘要页 —— 平铺整页(无卡片容器、无渐变带),内含:
 *  - 紧凑扁头([SummaryPageHeader]):源图标(源强调色)+ 源名 + 数据时刻,下接发丝线;
 *    「查看完整列表」出口收口在此(onOpen 非空时整行可点 + 尾部 chevron)
 *  - 中部:摘要正文(可纵向滚动),按 state 分支,占满剩余高度
 *
 * 出口上移扁头后底部不再有常驻按钮条,纵向空间全部让给正文。
 *
 * @param reserveBottomBarSpace 根 tab(底栏为浮动药丸)传 true:列表可滚入药丸之下,
 * 底部 contentPadding 补「药丸高 + 16dp 呼吸」让末条能停到药丸之上(与总览页同一做法);
 * 二级页(历史摘要)底栏不悬浮,传 false
 * @param onOpenItem 条目点击回调(仅 v2 结构化条目且 [SummaryItem.url] 非空时可点,
 * 直达内置 WebView;v1 纯文本与空 url 条目保持只读)
 */
@Composable
internal fun SummaryCardPage(
    spec: SummaryCardSpec,
    state: UiState<SourceSummary>,
    onRetry: () -> Unit,
    reserveBottomBarSpace: Boolean = false,
    onOpenItem: (SummaryItem) -> Unit = {}
) {
    val accent = sourceAccentOf(spec.source)
    Column(modifier = Modifier.fillMaxSize()) {
        SummaryPageHeader(spec = spec, state = state, accent = accent)
        SummaryHairline()

        // 摘要正文(可滚动),按 state 分支
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (state) {
                is UiState.Loading -> SummarySkeleton()
                is UiState.Error -> SummaryError(
                    message = state.message,
                    kind = state.kind,
                    onRetry = onRetry
                )
                is UiState.Success -> SummaryBody(
                    content = state.data.content,
                    accent = accent,
                    reserveBottomBarSpace = reserveBottomBarSpace,
                    onOpenItem = onOpenItem,
                    onOpenFullList = spec.onOpen
                )
            }
        }
    }
}

/**
 * 紧凑扁头 —— 一行高:源图标(源强调色 tint)+ 源名 + 右侧数据时刻(caption)。
 * 取代原 BrandGradient 渐变卡头:蓝紫渐变焦点已收口到总览页 digest Hero(AI 特性专用),
 * 摘要页不再逐源重复渐变带,纵向空间让给正文。
 *
 * 「查看完整列表」出口也收口在此:[SummaryCardSpec.onOpen] 非空时整行可点,
 * 尾部加 chevron 披露指示(语义同设置页/关于页的列表行);为 null(历史摘要页)
 * 则纯展示不可点、无 chevron。
 */
@Composable
private fun SummaryPageHeader(
    spec: SummaryCardSpec,
    state: UiState<SourceSummary>,
    accent: Color
) {
    val cs = MaterialTheme.colorScheme
    val onOpen = spec.onOpen
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            spec.icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = spec.title,
            style = AppText.titleItem,
            color = cs.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (state is UiState.Success) {
            // 断供警示:数据时刻超 24h 未前进(该源连续多批抓取失败)时错误色 + 警示图标;
            // 完整说明在源列表页顶部的断供横幅,这里只做轻量提示
            val stale = SourceFreshness.isStale(state.data.fetchedAtMs)
            if (stale) {
                Icon(
                    Icons.Outlined.Warning,
                    // 断供状态语义只靠图形+颜色表达不行,补读屏描述
                    contentDescription = stringResource(R.string.summary_stale_cd),
                    tint = cs.error,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.size(2.dp))
            }
            Text(
                text = stringResource(R.string.summary_data_moment, formatFetchedAt(context, state.data.fetchedAtMs)),
                style = AppText.caption,
                color = if (stale) cs.error else cs.onSurfaceVariant
            )
        }
        if (onOpen != null) {
            Spacer(Modifier.size(2.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.summary_view_full_list),
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 页内发丝线 —— 扁头下缘(0.5dp outlineVariant,与总览页同语言)。 */
@Composable
private fun SummaryHairline() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/**
 * 源强调色 —— 卡头图标 tint 与条目序号的差异化锚点。
 * 8 张卡同构(同一产品语言),仅靠强调色与图标区分源。
 */
@Composable
private fun sourceAccentOf(source: String): Color {
    val cs = MaterialTheme.colorScheme
    return when (source) {
        SourceKeys.HACKERNEWS -> cs.tertiary            // 暖橙,呼应 HN 品牌与热度语义
        SourceKeys.GITHUB_TRENDING -> cs.primary
        SourceKeys.HUGGINGFACE_PAPERS -> cs.primary
        SourceKeys.STORMZHANG_AI -> cs.secondary        // 品牌紫,贴「AI 资讯」语义
        SourceKeys.PRODUCTHUNT -> cs.primary            // PH 品牌橙红由 SourceBrand 承载,卡片用 primary
        SourceKeys.RUNDOWN_AI -> cs.secondary           // 品牌紫,贴「AI newsletter」语义(与 stormzhang 同系)
        SourceKeys.OPENAI_ANTHROPIC_NEWS -> cs.tertiary // 暖橙,呼应 OpenAI 品牌绿与厂商动态热度语义
        SourceKeys.AIHOT_FEATURED -> cs.primary         // 自家源,品牌 Future Blue 由 SourceBrand.AiHot 承载,卡片用 primary
        else -> cs.primary
    }
}

/**
 * 摘要正文 —— 条目化排版:每行一条,两位序号(01、02……源强调色 Bold)
 * 与正文首行基线对齐,条目间距 12dp。
 *
 * 三种形态的视觉处理:
 * - [SummaryContent.Structured]:每项 title(加粗)+ desc(常规)拼成一行富文本;
 * - [SummaryContent.Plain]:整段纯文本按行切分,解析 **加粗** 标记(bullet 符号 trim 掉);
 * - [SummaryContent.Unavailable]:本批 AI 摘要缺失(快照 items 完好)→ 居中降级提示
 *   + 「查看完整列表」出口(重试无意义,刻意不走错误态)。
 */
@Composable
private fun SummaryBody(
    content: SummaryContent,
    accent: Color,
    reserveBottomBarSpace: Boolean,
    onOpenItem: (SummaryItem) -> Unit,
    onOpenFullList: (() -> Unit)?
) {
    when (content) {
        is SummaryContent.Structured -> SummaryItems(
            items = content.items,
            accent = accent,
            reserveBottomBarSpace = reserveBottomBarSpace,
            onOpenItem = onOpenItem
        )
        is SummaryContent.Plain -> SummaryPlainText(
            text = content.text,
            accent = accent,
            reserveBottomBarSpace = reserveBottomBarSpace
        )
        is SummaryContent.Unavailable -> SummaryUnavailable(onOpenFullList = onOpenFullList)
    }
}

/**
 * 「本批未生成 AI 摘要」降级态 —— 流水线 AI 调用失败、仅摘要字段缺失,原始列表不受影响。
 * 与错误态刻意区分:这不是网络问题,重试大概率无效;历史摘要页无出口([onOpenFullList]
 * 为 null)时只显示说明。
 */
@Composable
private fun SummaryUnavailable(onOpenFullList: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.HourglassEmpty,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.summary_unavailable_title),
            style = AppText.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.summary_unavailable_subtitle),
            style = AppText.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (onOpenFullList != null) {
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onOpenFullList) {
                Text(stringResource(R.string.summary_view_full_list), style = AppText.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** v2 结构化条目渲染:title 加粗作导语,desc 常规作正文,同行基线对齐;url 非空的条目整行可点(直达原文)。 */
@Composable
private fun SummaryItems(
    items: List<SummaryItem>,
    accent: Color,
    reserveBottomBarSpace: Boolean,
    onOpenItem: (SummaryItem) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    // 已读判定:打开过的条目(url 命中浏览历史)正文降透明弱化;行内仅设字重的
    // AnnotatedString 不覆盖颜色,Text 基础色即整行生效
    val readUrls = rememberReadUrls()
    // 稳定 key:url 优先,重复/空以出现序号消歧保证唯一(重复 key 会直接崩溃)
    val itemKeys = remember(items) {
        val seen = mutableMapOf<String, Int>()
        items.map { item ->
            val base = item.url.ifBlank { "item" }
            val dup = seen.getOrPut(base) { 0 }
            seen[base] = dup + 1
            base + if (dup == 0) "" else "#$dup"
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // 根 tab:末条可停到药丸之上(药丸高 + 16dp 呼吸),列表本身滚入药丸之下;
        // 二级页无悬浮底栏,只留 4dp 呼吸
        contentPadding = PaddingValues(
            top = 4.dp,
            bottom = if (reserveBottomBarSpace) BottomBarPillHeight + 16.dp else 4.dp
        )
    ) {
        itemsIndexed(items, key = { i, _ -> itemKeys[i] }) { index, item ->
            // AnnotatedString 构建 remember:行重组(滚动入视口/点击涟漪)不再重复拼装
            val line = remember(item.title, item.desc) {
                renderItemLine(item.title, item.desc)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.then(
                    if (item.url.isNotBlank()) Modifier.clickable { onOpenItem(item) } else Modifier
                )
            ) {
                Text(
                    text = "%02d".format(index + 1),
                    style = AppText.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier.alignByBaseline()
                )
                Text(
                    text = line,
                    style = AppText.body,
                    color = if (item.url.isNotBlank() && item.url in readUrls) {
                        cs.onSurface.copy(alpha = AppAlpha.readDim)
                    } else {
                        cs.onSurface
                    },
                    modifier = Modifier.alignByBaseline()
                )
            }
        }
    }
}

/** v1 纯文本渲染:沿用旧的按行切分 + renderRichLine 逻辑。 */
@Composable
private fun SummaryPlainText(
    text: String,
    accent: Color,
    reserveBottomBarSpace: Boolean
) {
    // 行切分 remember:整段文本只在内容变化时重切一次,行重组不再重复处理
    val lines = remember(text) { text.lines().filter { it.isNotBlank() } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // 同 SummaryItems:根 tab 底部补「药丸高 + 16dp」,二级页只留 4dp
        contentPadding = PaddingValues(
            top = 4.dp,
            bottom = if (reserveBottomBarSpace) BottomBarPillHeight + 16.dp else 4.dp
        )
    ) {
        itemsIndexed(lines) { index, line ->
            // 加粗段解析 remember:行重组不再重复跑正则
            val rich = remember(line) { renderRichLine(line) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "%02d".format(index + 1),
                    style = AppText.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier.alignByBaseline()
                )
                Text(
                    text = rich,
                    style = AppText.body,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.alignByBaseline()
                )
            }
        }
    }
}

/**
 * 把单条 [SummaryItem] 拼成 [AnnotatedString]:title(SemiBold)+ desc(Normal)。
 *
 * title 与 desc 之间用全角冒号「：」连接,视觉上对齐 v1 纯文本「**标题**：描述」的观感,
 * 保证新旧格式切换时用户感知一致。
 */
private fun renderItemLine(title: String, desc: String): AnnotatedString {
    val boldStyle = SpanStyle(fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        withStyle(boldStyle) { append(title) }
        append("：")
        append(desc)
    }
}

/** 加粗段 `**...**` 匹配(非贪婪,不允许内部换行);进程一份,不随行重建。 */
private val BOLD_SEGMENT_REGEX = Regex("\\*\\*(.+?)\\*\\*")

/**
 * 把单行文本解析为 [AnnotatedString]:**...** 段落渲染为 SemiBold,其余 Normal。
 *
 * prompt 要求每条格式「• **标题**：简述」,加粗段即标题,视觉上与正文拉开层级。
 * 实现:正则切 ** 包裹的段,交替应用 Normal / Bold 样式。支持一行内多处加粗。
 */
private fun renderRichLine(line: String): AnnotatedString {
    // 去掉行首 bullet 与多余空白,统一缩进由排版负责
    val raw = line.trim().removePrefix("•").trimStart()
    if (raw.isBlank()) return AnnotatedString(line)
    val boldStyle = SpanStyle(fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        var idx = 0
        var lastEnd = 0
        for (m in BOLD_SEGMENT_REGEX.findAll(raw)) {
            if (m.range.first > lastEnd) append(raw.substring(lastEnd, m.range.first))
            withStyle(boldStyle) { append(m.groupValues[1]) }
            lastEnd = m.range.last + 1
            idx = lastEnd
        }
        if (idx < raw.length) append(raw.substring(idx))
    }
}

@Composable
private fun SummarySkeleton() {
    ShimmerHost {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            repeat(6) { i ->
                val width = if (i % 3 == 2) 0.7f else if (i % 3 == 1) 0.9f else 1f
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShimmerBox(modifier = Modifier.fillMaxWidth(width).height(14.dp), cornerRadius = 4.dp)
                    ShimmerBox(modifier = Modifier.fillMaxWidth(0.95f).height(12.dp), cornerRadius = 4.dp)
                }
            }
        }
    }
}

@Composable
private fun SummaryError(
    message: String,
    onRetry: () -> Unit,
    kind: ErrorKind = ErrorKind.Unknown
) {
    // NoData 时显示空态文案;其他 kind 显示错误态文案
    val title = stringResource(
        if (kind == ErrorKind.NoData) R.string.summary_not_generated_yet else R.string.summary_error_title
    )
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 卡片内嵌的紧凑错误态:CloudOff 小图标 + 口语化标题 + 底层错误详情
        Icon(
            Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = title,
            style = AppText.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = message,
            style = AppText.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(8.dp))
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.common_retry), style = AppText.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 把归档 fetchedAtMs 格式化成「M月d日 HH:mm」(模式串 date_fmt_month_day_time 随语言)。 */
private fun formatFetchedAt(context: Context, ms: Long): String {
    if (ms <= 0L) return context.getString(R.string.summary_time_unknown)
    return runCatching {
        SimpleDateFormat(context.getString(R.string.date_fmt_month_day_time), Locale.getDefault()).format(Date(ms))
    }.getOrDefault(context.getString(R.string.summary_time_unknown))
}
