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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.data.repo.SourceSummary
import com.peng.ainewshub.data.repo.SummaryContent
import com.peng.ainewshub.data.repo.SummaryRepository
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
import com.peng.ainewshub.ui.follows.FollowsHeaderRow
import com.peng.ainewshub.ui.follows.FollowItemRow
import com.peng.ainewshub.ui.follows.FollowsManageSheet
import com.peng.ainewshub.ui.follows.FollowsUi
import com.peng.ainewshub.ui.follows.FollowsViewModel
import com.peng.ainewshub.ui.follows.followsSourceLabel
import com.peng.ainewshub.ui.summary.hasUnseenDigest
import com.peng.ainewshub.ui.summary.sourceAccentOf
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText

/**
 * 「今天」Tab 根屏 —— 一份垂直日报(v1.4.0 合并原 总览 + 摘要 两个根 tab)。
 *
 * 单条滚动叙事:综述 Hero → 「今日重点」Top10 → 收起式「我的关注」行(原独立
 * 关注根 tab 降级并入;默认收起,点开原地展开命中流,无关注词时不渲染)
 * → 分源摘要区块 ×8(按用户
 * `source_order` 顺序,每源最多平铺 [SOURCE_SECTION_MAX_ITEMS] 条,余量经
 * 区块头「查看全部 N 条 ›」进源完整列表)→ 页脚。读完重点顺着往下扫完分源,
 * 不再横向切页 —— 「今天」页是日报导览,不是全量流。
 *
 * 渐进渲染:[OverviewViewModel](综述+Top10)、[SummaryViewModel](8 源摘要)与
 * [FollowsViewModel](关注语料,同缓存近零成本)并存、互不阻塞 —— overview
 * 先到先画 Hero+Top10,各源独立到位独立渲染(单源失败只影响自己的区块,总览
 * 失败不拖累分源、反之亦然)。下拉刷新刷全部。
 *
 * 分源区块头的「新内容未查看」语义(承自原摘要 chips 圆点):区块头进入视口即写入该源
 * 当前指纹(圆点熄灭,下一批重新亮起);「查看全部 ›」进源完整列表页。
 * 条目行渲染复用 [com.peng.ainewshub.ui.summary.SummaryRender] 的富文本实现
 * (renderItemLine / 源强调色)。
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
    summaryVm: SummaryViewModel = viewModel(),
    followsVm: FollowsViewModel = viewModel()
) {
    val overviewState by overviewVm.state.collectAsStateWithLifecycle()
    val overviewRefreshing by overviewVm.isRefreshing.collectAsStateWithLifecycle()
    val sourceStates by summaryVm.states.collectAsStateWithLifecycle()
    val summaryRefreshing by summaryVm.isRefreshing.collectAsStateWithLifecycle()
    val sourceKeys by summaryVm.sourceKeys.collectAsStateWithLifecycle()
    val seen by summaryVm.seen.collectAsStateWithLifecycle()
    val followsState by followsVm.state.collectAsStateWithLifecycle()
    val followsRefreshing by followsVm.isRefreshing.collectAsStateWithLifecycle()
    // 语料就绪(Success)才有关注行与管理弹层;Loading/Error 整段静默跳过
    val followsUi = (followsState as? UiState.Success)?.data
    val followsExpanded by followsVm.isExpanded.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    var showManage by rememberSaveable { mutableStateOf(false) }

    // 重击当前 tab:滚回顶部 + 三 VM 缓存感知刷新(指纹未变零开销)。
    // lastHandled 防「重新进入组合就自动刷新」。
    var lastHandledReselect by remember { mutableIntStateOf(reselectSignal) }
    LaunchedEffect(reselectSignal) {
        if (reselectSignal != lastHandledReselect) {
            lastHandledReselect = reselectSignal
            listState.animateScrollToItem(0)
            overviewVm.load()
            summaryVm.loadAll()
            followsVm.load()
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
                // 任一 VM 在刷即亮指示器(overview + 8 源 + 关注语料一起刷)
                isRefreshing = overviewRefreshing || summaryRefreshing || followsRefreshing,
                onRefresh = {
                    haptics.tick()
                    overviewVm.refresh()
                    summaryVm.refresh()
                    followsVm.refresh()
                }
            ) {
                TodayContent(
                    overviewState = overviewState,
                    followsUi = followsUi,
                    followsExpanded = followsExpanded,
                    sourceKeys = sourceKeys,
                    sourceStates = sourceStates,
                    seen = seen,
                    listState = listState,
                    onOpenUrl = onOpenUrl,
                    onRetryOverview = { overviewVm.load() },
                    onRetrySource = { summaryVm.retry(it) },
                    onSeen = { source, fingerprint -> summaryVm.markSeen(source, fingerprint) },
                    onOpenSource = onOpenSource,
                    onSelectKeyword = { followsVm.selectKeyword(it) },
                    onToggleFollows = { followsVm.toggleExpanded() },
                    onManage = { showManage = true }
                )
            }
        }
    }

    // 关键词管理弹层:语料就绪(Success)才有意义;入口在关注段统计区/空态
    if (showManage && followsUi != null) {
        FollowsManageSheet(
            keywords = followsUi.keywords,
            suggestions = followsUi.suggestions,
            onDismiss = { showManage = false },
            onAdd = { followsVm.addKeyword(it) },
            onRemove = { followsVm.removeKeyword(it) }
        )
    }
}

/**
 * 日报主体:单 LazyColumn 自上而下 = 总览段(Hero / 骨架 / 内嵌提示 + Top10)
 * → 我的关注收起行(展开时接命中流)→ 终章符 → 分源摘要区块 ×8 → 页脚。
 * 各段状态独立分支,互不阻塞。
 */
@Composable
private fun TodayContent(
    overviewState: OverviewState,
    // 关注行快照(null = 语料未就绪/出错,整段不渲染)
    followsUi: FollowsUi?,
    // 关注行展开态(收起式,默认收起;展开内容在行下方原地渲染)
    followsExpanded: Boolean,
    sourceKeys: List<String>,
    sourceStates: Map<String, UiState<SourceSummary>>,
    seen: Map<String, Long>,
    listState: LazyListState,
    onOpenUrl: (String, String, String) -> Unit,
    onRetryOverview: () -> Unit,
    onRetrySource: (String) -> Unit,
    onSeen: (String, Long) -> Unit,
    onOpenSource: (String) -> Unit,
    onSelectKeyword: (String) -> Unit,
    onToggleFollows: () -> Unit,
    onManage: () -> Unit
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

        // ===== 我的关注:收起式入口行(无关注词/语料未就绪时整段不渲染) =====
        if (followsUi != null && followsUi.keywords.isNotEmpty()) {
            item(key = "follows-head", contentType = "follows-toggle-row") {
                FollowsToggleRow(
                    hitCount = followsUi.items.size,
                    expanded = followsExpanded,
                    onToggle = onToggleFollows
                )
            }
            if (followsExpanded) {
                item(key = "follows-keywords", contentType = "follows-head-row") {
                    FollowsHeaderRow(ui = followsUi, onSelect = onSelectKeyword, onManage = onManage)
                }
                if (followsUi.items.isEmpty()) {
                    // 有关注词但今天没有命中:保留段落与 chips,给换词/管理出口
                    item(key = "follows-empty", contentType = "notice") {
                        SectionNotice(
                            title = stringResource(R.string.follows_empty_no_match_title),
                            message = stringResource(R.string.follows_empty_no_match_subtitle),
                            actionLabel = stringResource(R.string.follows_manage_action),
                            onAction = onManage
                        )
                    }
                } else {
                    // key:url(空 url 只读条目以序号消歧;重复 url 以出现序号消歧)。
                    // (LazyListScope 非组合上下文不能 remember;小列表逐次重算可忽略)
                    val followKeys = run {
                        val seenUrls = mutableMapOf<String, Int>()
                        followsUi.items.mapIndexed { i, item ->
                            val base = item.entry.url.ifBlank { "blank-$i" }
                            val dup = seenUrls.getOrPut(base) { 0 }
                            seenUrls[base] = dup + 1
                            base + if (dup == 0) "" else "#$dup"
                        }
                    }
                    itemsIndexed(
                        followsUi.items,
                        key = { i, _ -> "fw-${followKeys[i]}" },
                        contentType = { _, _ -> "follows-item" }
                    ) { i, feedItem ->
                        val entry = feedItem.entry
                        val sourceLabel = followsSourceLabel(context, entry.source)
                        FollowItemRow(
                            index = i,
                            item = feedItem,
                            sourceLabel = sourceLabel,
                            isRead = entry.url.isNotEmpty() && entry.url in readUrls,
                            onClick = { onOpenUrl(entry.url, entry.title, sourceLabel) }
                        )
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

/**
 * 「我的关注」收起式入口行 —— 浅底长条(同热词页管理行视觉语言):标签 +
 * 今日命中数 + 尾随 展开/收起 文字动作(复用综述折叠词条)。点击整行切换;
 * 展开后的命中内容(chips + 条目)在行下方原地渲染,默认收起,今天页正文
 * 不直接展示关注内容。
 */
@Composable
private fun FollowsToggleRow(
    hitCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.small)
            .background(cs.surfaceContainerLow)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.follows_section_title),
            style = AppText.bodySmall,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.follows_hits_count, hitCount),
            style = AppText.bodySmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(
                if (expanded) R.string.overview_digest_collapse
                else R.string.overview_digest_expand
            ),
            style = AppText.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = cs.primary
        )
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
