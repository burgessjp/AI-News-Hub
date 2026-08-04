package com.peng.ainewshub.ui.summary

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.data.SourceSummary
import com.peng.ainewshub.data.SummaryContent
import com.peng.ainewshub.data.SummaryItem
import com.peng.ainewshub.ui.ErrorKind
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.anim.Motion
import com.peng.ainewshub.ui.components.BottomBarPillHeight
import com.peng.ainewshub.ui.components.ShimmerBox
import com.peng.ainewshub.ui.more.sourceMeta
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 摘要页共享件 —— 摘要 Tab(当日)与「历史摘要」按日期页(归档)共用的单页实现。
 *
 * 从 SummaryScreen 抽出:页 spec、顶部提示行+页面指示器(两屏 pager 共用)、
 * 单张源摘要页(紧凑扁头 + 条目正文,平铺无卡片,「查看完整列表」出口收口在扁头)。
 * 两屏保持同构(同一产品语言),差异仅在数据来源(latest 快照 vs history 索引按日期寻址)
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
 * 顶部行:左 = 数据来源提示;右 = 页面指示器(当前页横向胶囊,未选中圆点)。
 * 指示器可点击直接跳页;宽度/颜色随切页 tween 过渡(Motion.SHORT)。
 */
@Composable
internal fun SummaryHeaderRow(
    currentPage: Int,
    pageCount: Int,
    hint: String = "每日 AI 精选",
    onDotClick: (Int) -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = hint,
            style = AppText.caption,
            color = cs.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.size(8.dp))
        // 页面指示器(可点击跳页):当前页 16×6 胶囊(primary),未选中 6dp 圆点
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(pageCount) { i ->
                val isCurrent = i == currentPage
                val dotWidth by animateDpAsState(
                    targetValue = if (isCurrent) 16.dp else 6.dp,
                    animationSpec = tween(Motion.SHORT, easing = Motion.EmphasizedDecel),
                    label = "pageIndicatorWidth"
                )
                val dotColor by animateColorAsState(
                    targetValue = if (isCurrent) cs.primary
                    else cs.onSurfaceVariant.copy(alpha = AppAlpha.hairlineOverlay),
                    animationSpec = tween(Motion.SHORT, easing = Motion.EmphasizedDecel),
                    label = "pageIndicatorColor"
                )
                Box(
                    modifier = Modifier
                        .size(width = dotWidth, height = 6.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                        .clickable { onDotClick(i) }
                )
            }
        }
    }
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
 */
@Composable
internal fun SummaryCardPage(
    spec: SummaryCardSpec,
    state: UiState<SourceSummary>,
    onRetry: () -> Unit,
    reserveBottomBarSpace: Boolean = false
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
                    reserveBottomBarSpace = reserveBottomBarSpace
                )
            }
        }
    }
}

/**
 * 紧凑扁头 —— 一行高:源图标(源强调色 tint)+ 源名 + 右侧数据时刻(caption)。
 * 取代原 BrandGradient 渐变卡头:蓝紫渐变焦点已收口到总览页头条(AI 特性专用),
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
            Text(
                text = "数据时刻：${formatFetchedAt(state.data.fetchedAtMs)}",
                style = AppText.caption,
                color = cs.onSurfaceVariant
            )
        }
        if (onOpen != null) {
            Spacer(Modifier.size(2.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "查看完整列表",
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
        "hackernews" -> cs.tertiary            // 暖橙,呼应 HN 品牌与热度语义
        "github-trending" -> cs.primary
        "huggingface-papers" -> cs.primary
        "stormzhang-ai" -> cs.secondary        // 品牌紫,贴「AI 资讯」语义
        "producthunt" -> cs.primary            // PH 品牌橙红由 SourceBrand 承载,卡片用 primary
        "rundown-ai" -> cs.secondary           // 品牌紫,贴「AI newsletter」语义(与 stormzhang 同系)
        "openai-anthropic-news" -> cs.tertiary // 暖橙,呼应 OpenAI 品牌绿与厂商动态热度语义
        "aihot-featured" -> cs.primary         // 自家源,品牌 Future Blue 由 SourceBrand.AiHot 承载,卡片用 primary
        else -> cs.primary
    }
}

/**
 * 摘要正文 —— 条目化排版:每行一条,两位序号(01、02……源强调色 Bold)
 * 与正文首行基线对齐,条目间距 12dp。
 *
 * 两种数据格式视觉等价:
 * - [SummaryContent.Structured]:每项 title(加粗)+ desc(常规)拼成一行富文本;
 * - [SummaryContent.Plain]:整段纯文本按行切分,解析 **加粗** 标记(bullet 符号 trim 掉)。
 */
@Composable
private fun SummaryBody(
    content: SummaryContent,
    accent: Color,
    reserveBottomBarSpace: Boolean
) {
    when (content) {
        is SummaryContent.Structured -> SummaryItems(
            items = content.items,
            accent = accent,
            reserveBottomBarSpace = reserveBottomBarSpace
        )
        is SummaryContent.Plain -> SummaryPlainText(
            text = content.text,
            accent = accent,
            reserveBottomBarSpace = reserveBottomBarSpace
        )
    }
}

/** v2 结构化条目渲染:title 加粗作导语,desc 常规作正文,同行基线对齐。 */
@Composable
private fun SummaryItems(
    items: List<SummaryItem>,
    accent: Color,
    reserveBottomBarSpace: Boolean
) {
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
        itemsIndexed(items) { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "%02d".format(index + 1),
                    style = AppText.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier.alignByBaseline()
                )
                Text(
                    text = renderItemLine(item.title, item.desc),
                    style = AppText.body,
                    color = MaterialTheme.colorScheme.onSurface,
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
    val lines = text.lines().filter { it.isNotBlank() }
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "%02d".format(index + 1),
                    style = AppText.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier.alignByBaseline()
                )
                Text(
                    text = renderRichLine(line),
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
        // 匹配 **...**(非贪婪,不允许内部换行)
        val regex = Regex("\\*\\*(.+?)\\*\\*")
        var lastEnd = 0
        for (m in regex.findAll(raw)) {
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

@Composable
private fun SummaryError(
    message: String,
    onRetry: () -> Unit,
    kind: ErrorKind = ErrorKind.Unknown
) {
    // NoData 时显示空态文案;其他 kind 显示错误态文案
    val title = if (kind == ErrorKind.NoData) "今日摘要尚未生成" else "摘要暂时没加载出来"
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
            Text("重试", style = AppText.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 把归档 fetchedAtMs 格式化成「M月d日 HH:mm」。 */
private fun formatFetchedAt(ms: Long): String {
    if (ms <= 0L) return "未知"
    return runCatching {
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ms))
    }.getOrDefault("未知")
}
