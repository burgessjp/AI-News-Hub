package com.peng.ainewshub.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.data.repo.SourceSummary
import com.peng.ainewshub.data.repo.SummaryContent
import com.peng.ainewshub.data.repo.SummaryRepository
import com.peng.ainewshub.data.source.SourceFreshness
import com.peng.ainewshub.ui.SectionNotice
import com.peng.ainewshub.ui.SummaryViewModel
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.BottomBarPillHeight
import com.peng.ainewshub.ui.components.DoubleRule
import com.peng.ainewshub.ui.components.BrandWordmark
import com.peng.ainewshub.ui.components.RankRowSkeletonList
import com.peng.ainewshub.ui.components.SectionHeader
import com.peng.ainewshub.ui.components.ShimmerBox
import com.peng.ainewshub.ui.components.ShimmerHost
import com.peng.ainewshub.ui.components.rememberHaptics
import com.peng.ainewshub.ui.components.rememberReadUrls
import com.peng.ainewshub.ui.more.sourceMeta
import com.peng.ainewshub.ui.summary.hasUnseenDigest
import com.peng.ainewshub.ui.summary.renderItemLine
import com.peng.ainewshub.ui.summary.renderRichLine
import com.peng.ainewshub.ui.summary.sourceAccentOf
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText

/**
 * 「今天」Tab 根屏 —— 一份垂直日报(v1.4.0 合并原 总览 + 摘要 两个根 tab)。
 *
 * 单条滚动叙事:综述 Hero → 「今日重点」Top10 → 分源摘要区块 ×8(按用户
 * `source_order` 顺序,每源最多平铺 [SOURCE_SECTION_MAX_ITEMS] 条,余量经
 * 区块头「查看全部 N 条 ›」进源完整列表)→ 页脚。读完重点顺着往下扫完分源,
 * 不再横向切页 —— 「今天」页是日报导览,不是全量流。
 *
 * 渐进渲染:[OverviewViewModel](综述+Top10)与 [SummaryViewModel](8 源摘要)
 * 并存、互不阻塞 —— overview 先到先画 Hero+Top10,各源独立到位独立渲染
 * (单源失败只影响自己的区块,总览失败不拖累分源、反之亦然)。下拉刷新刷全部。
 *
 * 分源区块头的「新内容未查看」语义(承自原摘要 chips 圆点):区块头进入视口即写入该源
 * 当前指纹(圆点熄灭,下一批重新亮起);「查看全部 ›」进源完整列表页。
 * 条目行渲染复用 [SummaryCard] 的富文本实现(renderItemLine / 源强调色)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenUrl: (url: String, title: String, source: String) -> Unit,
    // 顶栏搜索图标 → 本地搜索独立页(查设备内索引,覆盖本 App 浏览过的 8 源数据)
    onOpenSearch: () -> Unit = {},
    // 报头「热词」→ 热词榜二级页(近 N 天热词 + 词云入口)
    onOpenTrends: () -> Unit = {},
    // 分源摘要区块头「查看全部」→ 源完整列表二级页
    onOpenSource: (String) -> Unit,
    // 列表状态由 AiNewsHubApp 上提持有:切 tab / 进二级页返回后保持滚动位置
    listState: LazyListState,
    reselectSignal: Int = 0,
    overviewVm: OverviewViewModel = viewModel(),
    summaryVm: SummaryViewModel = viewModel()
) {
    val overviewState by overviewVm.state.collectAsStateWithLifecycle()
    val overviewRefreshing by overviewVm.isRefreshing.collectAsStateWithLifecycle()
    val sourceStates by summaryVm.states.collectAsStateWithLifecycle()
    val summaryRefreshing by summaryVm.isRefreshing.collectAsStateWithLifecycle()
    val sourceKeys by summaryVm.sourceKeys.collectAsStateWithLifecycle()
    val seen by summaryVm.seen.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()

    // 重击当前 tab:滚回顶部 + 双 VM 缓存感知刷新(指纹未变零开销)。
    // lastHandled 防「重新进入组合就自动刷新」。
    var lastHandledReselect by remember { mutableIntStateOf(reselectSignal) }
    LaunchedEffect(reselectSignal) {
        if (reselectSignal != lastHandledReselect) {
            lastHandledReselect = reselectSignal
            listState.animateScrollToItem(0)
            overviewVm.load()
            summaryVm.loadAll()
        }
    }

    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme

    Scaffold(
        containerColor = cs.surface,
        topBar = {
            // 纸墨日报报头:居中字标 + 右侧热词/本地搜索入口,报头下双细线
            // (线收敛后全 App 唯一的结构线)。日期/刊名/数据截至移入综述区标签行,
            // 报头不再承担日期副标题,与关注/更多页报头同构,首屏少占一行
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cs.surface)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                ) {
                    BrandWordmark(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(top = 14.dp, bottom = 12.dp)
                    )
                    // 报头动作位(右侧):热词榜入口 + 本地搜索。
                    // 文字按钮(去图标):纯排版语言,padding 撑足 48dp 触控高;搜索保持最右
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.tab_hotwords),
                            style = AppText.caption,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier
                                .clickable(onClick = onOpenTrends)
                                .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 16.dp)
                        )
                        Text(
                            text = stringResource(R.string.common_search),
                            style = AppText.caption,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier
                                .clickable(onClick = onOpenSearch)
                                .padding(start = 12.dp, end = 18.dp, top = 16.dp, bottom = 16.dp)
                        )
                    }
                }
                DoubleRule()
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 列表可滚入药丸 TAB 之下,但可视区不超出药丸底缘
                // (与 AiNewsHubApp 底栏定位一致:navigationBarsPadding + 距底 16dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            PullToRefreshBox(
                // 任一 VM 在刷即亮指示器(overview + 8 源一起刷)
                isRefreshing = overviewRefreshing || summaryRefreshing,
                onRefresh = {
                    haptics.tick()
                    overviewVm.refresh()
                    summaryVm.refresh()
                }
            ) {
                TodayContent(
                    overviewState = overviewState,
                    sourceKeys = sourceKeys,
                    sourceStates = sourceStates,
                    seen = seen,
                    listState = listState,
                    onOpenUrl = onOpenUrl,
                    onRetryOverview = { overviewVm.load() },
                    onRetrySource = { summaryVm.retry(it) },
                    onSeen = { source, fingerprint -> summaryVm.markSeen(source, fingerprint) },
                    onOpenSource = onOpenSource
                )
            }
        }
    }
}

/**
 * 日报主体:单 LazyColumn 自上而下 = 总览段(Hero / 骨架 / 内嵌提示 + Top10)
 * → 分源摘要区块 ×8 → 页脚。各段状态独立分支,互不阻塞。
 */
@Composable
private fun TodayContent(
    overviewState: OverviewState,
    sourceKeys: List<String>,
    sourceStates: Map<String, UiState<SourceSummary>>,
    seen: Map<String, Long>,
    listState: LazyListState,
    onOpenUrl: (String, String, String) -> Unit,
    onRetryOverview: () -> Unit,
    onRetrySource: (String) -> Unit,
    onSeen: (String, Long) -> Unit,
    onOpenSource: (String) -> Unit
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    // 已读判定:打开过的条目(url 命中浏览历史)整行弱化
    val readUrls = rememberReadUrls()
    // Top10 稳定 key:url 优先(breaking 前移等排序变化时走 move 复用),
    // 重复/空 url 以出现序号消歧保证唯一(重复 key 会直接崩溃)
    val topKeys = remember(overviewState) {
        val items = (overviewState as? OverviewState.Success)?.digest?.items.orEmpty()
        val seenUrls = mutableMapOf<String, Int>()
        items.map { e ->
            val base = e.url.ifBlank { "top" }
            val dup = seenUrls.getOrPut(base) { 0 }
            seenUrls[base] = dup + 1
            "top-" + base + if (dup == 0) "" else "#$dup"
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 根 tab 末项可停到药丸之上(药丸高 + 16dp 呼吸空间,列表本身可滚入药丸之下)
        contentPadding = PaddingValues(bottom = BottomBarPillHeight + 16.dp)
    ) {
        // ===== 总览段:综述 Hero + 今日重点 Top10 =====
        when (overviewState) {
            is OverviewState.Loading -> {
                // Hero 骨架:与内容态同构(栏目名 + 正文行,无结构线),纸面 shimmer
                item(key = "hero_skeleton", contentType = "skeleton") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ShimmerHost {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 14.dp)
                            ) {
                                ShimmerBox(modifier = Modifier.size(64.dp, 10.dp), cornerRadius = 4.dp)
                                Spacer(Modifier.height(10.dp))
                                ShimmerBox(modifier = Modifier.fillMaxWidth(0.95f).height(12.dp))
                                Spacer(Modifier.height(6.dp))
                                ShimmerBox(modifier = Modifier.fillMaxWidth(0.88f).height(12.dp))
                                Spacer(Modifier.height(6.dp))
                                ShimmerBox(modifier = Modifier.fillMaxWidth(0.6f).height(12.dp))
                                Spacer(Modifier.height(10.dp))
                                ShimmerBox(modifier = Modifier.size(110.dp, 9.dp), cornerRadius = 4.dp)
                            }
                        }
                    }
                }
                item(key = "rows_skeleton", contentType = "skeleton") { RankRowSkeletonList(count = 5) }
                item(key = "loading_hint", contentType = "skeleton") { OverviewLoadingHint() }
            }
            is OverviewState.NoData -> item(key = "overview_empty", contentType = "notice") {
                SectionNotice(
                    title = stringResource(R.string.overview_no_data_title),
                    message = stringResource(R.string.overview_no_data_subtitle),
                    actionLabel = stringResource(R.string.common_retry),
                    onAction = onRetryOverview
                )
            }
            is OverviewState.Error -> item(key = "overview_error", contentType = "notice") {
                SectionNotice(
                    title = stringResource(R.string.overview_load_failed),
                    message = overviewState.message,
                    actionLabel = stringResource(R.string.common_retry),
                    onAction = onRetryOverview
                )
            }
            is OverviewState.Success -> {
                val digest = overviewState.digest
                if (digest.dataFetchedAt > 0 || digest.digest.isNotBlank()) {
                    item(key = "lead", contentType = "lead") { OverviewLead(digest = digest) }
                }
                if (digest.items.isNotEmpty()) {
                    item(key = "focus_head", contentType = "focus-head") {
                        // 报纸大节头:红角标 + 衬线 sectionHead(线收敛后的层级锚点)
                        SectionHeader(title = stringResource(R.string.today_focus_section), large = true)
                    }
                    val items = digest.items
                    itemsIndexed(
                        items,
                        key = { i, _ -> topKeys[i] },
                        contentType = { _, e -> if (e.breaking) "top10-breaking" else "top10" }
                    ) { index, entry ->
                        Column {
                            TopEntryRow(
                                rank = index + 1,
                                entry = entry,
                                isRead = entry.url in readUrls,
                                onClick = {
                                    onOpenUrl(entry.url, entry.title, SummaryRepository.titleOf(context, entry.source))
                                }
                            )
                        }
                    }
                }
            }
        }

        // ===== 终章符:刊物完稿记号(日报读完的版面收尾) =====
        item(key = "end_mark", contentType = "end-mark") {
            Text(
                text = "■",
                style = AppText.caption,
                color = cs.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        // ===== 分源摘要段:按用户 source_order 逐源一节 =====
        sourceKeys.forEach { key ->
            val state = sourceStates[key] ?: UiState.Loading
            item(key = "src-$key-head", contentType = "src-head") {
                // 报纸分源区块:无线起头(线收敛:靠区块头排版与留白分层)
                SourceSectionHeader(
                    source = key,
                    state = state,
                    hasUnseen = hasUnseenDigest(state, seen[key]),
                    onOpen = { onOpenSource(key) }
                )
                // 区块进入视口 = 已查看该源:写入当前指纹(圆点熄灭);同指纹去重
                val fingerprint = (state as? UiState.Success)?.data?.fetchedAtMs
                if (fingerprint != null && seen[key] != fingerprint) {
                    LaunchedEffect(key, fingerprint) { onSeen(key, fingerprint) }
                }
            }
            when (state) {
                is UiState.Loading -> item(key = "src-$key-skeleton", contentType = "skeleton") {
                    SourceSectionSkeleton()
                }
                is UiState.Error -> item(key = "src-$key-error", contentType = "notice") {
                    SectionNotice(
                        title = stringResource(R.string.summary_error_title),
                        message = state.message,
                        actionLabel = stringResource(R.string.common_retry),
                        onAction = { onRetrySource(key) }
                    )
                }
                is UiState.Success -> when (val content = state.data.content) {
                    is SummaryContent.Structured -> {
                        // 每源最多平铺 2 条:「今天」页是日报不是全量流,长尾交给
                        //「查看全部」进源列表页(区块头文字链承载计数与出口)
                        val items = content.items.take(SOURCE_SECTION_MAX_ITEMS)
                        // 源内条目稳定 key:url 优先,重复/空以出现序号消歧。
                        // (LazyListScope 非组合上下文不能 remember;小列表逐次重算可忽略)
                        val itemKeys = run {
                            val seenUrls = mutableMapOf<String, Int>()
                            items.map { item ->
                                val base = item.url.ifBlank { "item" }
                                val dup = seenUrls.getOrPut(base) { 0 }
                                seenUrls[base] = dup + 1
                                base + if (dup == 0) "" else "#$dup"
                            }
                        }
                        itemsIndexed(
                            items,
                            key = { i, _ -> "src-$key-item-${itemKeys[i]}" },
                            contentType = { _, _ -> "src-item" }
                        ) { index, item ->
                            SourceSummaryItemRow(
                                index = index,
                                item = item,
                                accent = sourceAccentOf(key),
                                isRead = item.url.isNotBlank() && item.url in readUrls,
                                onClick = {
                                    onOpenUrl(item.url, item.title, SummaryRepository.titleOf(context, key))
                                }
                            )
                        }
                    }
                    is SummaryContent.Plain -> {
                        // v1 纯文本:按行切分渲染(历史快照兼容;同上不做 remember、同样截 2 条)
                        val lines = content.text.lines().filter { it.isNotBlank() }.take(SOURCE_SECTION_MAX_ITEMS)
                        itemsIndexed(
                            lines,
                            key = { i, _ -> "src-$key-line-$i" },
                            contentType = { _, _ -> "src-item" }
                        ) { index, line ->
                            SourcePlainLineRow(
                                index = index,
                                line = line,
                                accent = sourceAccentOf(key)
                            )
                        }
                    }
                    is SummaryContent.Unavailable -> item(key = "src-$key-unavailable", contentType = "notice") {
                        SectionNotice(
                            title = stringResource(R.string.summary_unavailable_title),
                            message = stringResource(R.string.summary_unavailable_subtitle),
                            actionLabel = stringResource(R.string.summary_view_full_list),
                            onAction = { onOpenSource(key) }
                        )
                    }
                }
            }
        }
    }
}

/** 加载提示行:「AI 正在生成」文案(AI 长输出,避免用户误以为卡死)。 */
@Composable
private fun OverviewLoadingHint() {
    val cs = MaterialTheme.colorScheme
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
            text = stringResource(R.string.overview_loading),
            style = AppText.bodySmall,
            color = cs.onSurfaceVariant
        )
    }
}

/** 分源区块最多平铺的摘要条数:更多条目经区块头「查看全部」进源列表页。 */
private const val SOURCE_SECTION_MAX_ITEMS = 3

/**
 * 分源摘要区块头 —— 源图标(强调色)+ 源名 + 「新内容」圆点,右侧
 * 「查看全部 N 条 ›」文字链(primary 色 + 尾随 chevron,与词云入口同语言);
 * 整行可点进源完整列表。沿用摘要卡扁头的断供警示(数据时刻超 24h 未前进
 * → 错误色 Warning 图标)。
 */
@Composable
private fun SourceSectionHeader(
    source: String,
    state: UiState<SourceSummary>,
    hasUnseen: Boolean,
    onOpen: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val meta = sourceMeta(source)
    val accent = sourceAccentOf(source)
    val structuredCount =
        ((state as? UiState.Success)?.data?.content as? SummaryContent.Structured)?.items?.size ?: 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
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
                text = meta.title,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
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
        Text(
            text = if (structuredCount > 0) {
                pluralStringResource(R.plurals.today_source_view_all, structuredCount, structuredCount)
            } else {
                stringResource(R.string.today_source_view_all_plain)
            },
            style = AppText.caption,
            fontWeight = FontWeight.SemiBold,
            color = cs.primary,
            maxLines = 1
        )
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = cs.primary
        )
    }
}

/** 分源区块加载骨架:三行轻量 shimmer(单源独立到位前的占位)。 */
@Composable
private fun SourceSectionSkeleton() {
    ShimmerHost {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            repeat(3) { i ->
                val width = if (i == 2) 0.7f else 0.95f
                Column {
                    ShimmerBox(modifier = Modifier.fillMaxWidth(width).height(14.dp), cornerRadius = 4.dp)
                    Spacer(Modifier.height(4.dp))
                    ShimmerBox(modifier = Modifier.fillMaxWidth(width * 0.92f).height(12.dp), cornerRadius = 4.dp)
                }
            }
        }
    }
}

/** v2 结构化条目行(分源区块内):序号(源强调色)+ 富文本行,已读弱化,可点直达原文。 */
@Composable
private fun SourceSummaryItemRow(
    index: Int,
    item: com.peng.ainewshub.data.repo.SummaryItem,
    accent: Color,
    isRead: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    // AnnotatedString 构建 remember:行重组(滚动入视口/点击涟漪)不再重复拼装
    val line = remember(item.title, item.desc) { renderItemLine(item.title, item.desc) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (item.url.isNotBlank()) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 5.dp)
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
            color = if (isRead) cs.onSurface.copy(alpha = AppAlpha.readDim) else cs.onSurface,
            modifier = Modifier.alignByBaseline()
        )
    }
}

/** v1 纯文本行(分源区块内,历史快照兼容):序号 + **加粗** 标记富文本,只读。 */
@Composable
private fun SourcePlainLineRow(
    index: Int,
    line: String,
    accent: Color
) {
    val rich = remember(line) { renderRichLine(line) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 5.dp)
    ) {
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
