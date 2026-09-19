package com.peng.ainewshub.ui.trends

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.SectionNotice
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.BottomBarPillHeight
import com.peng.ainewshub.ui.components.DoubleRule
import com.peng.ainewshub.ui.components.BrandWordmark
import com.peng.ainewshub.ui.components.RankRowSkeletonList
import com.peng.ainewshub.ui.components.SectionHeader
import com.peng.ainewshub.ui.components.rememberHaptics
import com.peng.ainewshub.ui.components.rememberReadUrls
import com.peng.ainewshub.ui.follows.FollowItemRow
import com.peng.ainewshub.ui.follows.FollowsFooter
import com.peng.ainewshub.ui.follows.FollowsHeaderRow
import com.peng.ainewshub.ui.follows.FollowsManageSheet
import com.peng.ainewshub.ui.follows.FollowsViewModel
import com.peng.ainewshub.ui.follows.followsSourceLabel
import com.peng.ainewshub.ui.theme.AppText

/**
 * 「热词」Tab 根屏 —— 单页两段(v1.4.0 合并原 关注 + 趋势 两个根 tab):
 * 上段「我的关注」关键词命中流(早晨扫自己关心的),下段「近 N 天热词榜」
 * + 词云入口(往下逛发现)。一条滚动两种身份,与「今天」页的垂直叙事同构。
 *
 * 两段各自独立取数([FollowsViewModel] 聚合语料 / [TrendsViewModel] 读 trends.json),
 * 互不阻塞:关注段 Loading/错误只占本段,热词榜照常渲染,反之亦然。
 * 下拉刷新刷两段;重击 tab 滚回顶部并重读。
 *
 * 无关注词时关注段收成一行引导(点击开管理弹层),不占整屏引导态;
 * 管理弹层与「+ 关注」(热词行展开区)是同一套关注词存储的两个入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotwordsScreen(
    onOpenUrl: (url: String, title: String, source: String) -> Unit,
    // 词云二级页入口(caption 行「词云 ›」)
    onOpenCloud: () -> Unit,
    // 带词进本地搜索(热词展开区「查看全部命中」)
    onOpenLocalSearch: (String) -> Unit,
    // 列表状态由 AiNewsHubApp 上提持有:切 tab / 进二级页返回后保持滚动位置
    listState: LazyListState,
    reselectSignal: Int = 0,
    followsVm: FollowsViewModel = viewModel(),
    trendsVm: TrendsViewModel = viewModel()
) {
    val followsState by followsVm.state.collectAsStateWithLifecycle()
    val followsRefreshing by followsVm.isRefreshing.collectAsStateWithLifecycle()
    val trendsState by trendsVm.state.collectAsStateWithLifecycle()
    val trendsRefreshing by trendsVm.isRefreshing.collectAsStateWithLifecycle()
    // 已关注词集合(小写):热词行展开区「+ 关注」按钮的已关注态判定
    val followedKeywords by trendsVm.followedKeywords.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val readUrls = rememberReadUrls()
    var showManage by rememberSaveable { mutableStateOf(false) }

    // 重击当前 tab:滚回顶部 + 两段重读(归档取数带缓存,低开销)。
    // lastHandled 防「重新进入组合就自动刷新」。
    var lastHandledReselect by remember { mutableIntStateOf(reselectSignal) }
    LaunchedEffect(reselectSignal) {
        if (reselectSignal != lastHandledReselect) {
            lastHandledReselect = reselectSignal
            listState.animateScrollToItem(0)
            followsVm.load()
            trendsVm.load()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            // 报头与「今天」页同构:居中字标 + 双细线(无日期/动作位),
            // 刷新收口到下拉手势
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding(),
                    contentAlignment = Alignment.Center
                ) {
                    BrandWordmark(
                        modifier = Modifier.padding(top = 14.dp, bottom = 12.dp)
                    )
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
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            PullToRefreshBox(
                // 任一段在刷即亮指示器(关注语料 + trends.json 一起刷)
                isRefreshing = followsRefreshing || trendsRefreshing,
                onRefresh = {
                    haptics.tick()
                    followsVm.refresh()
                    trendsVm.refresh()
                }
            ) {
                HotwordsContent(
                    followsState = followsState,
                    trendsState = trendsState,
                    followedKeywords = followedKeywords,
                    readUrls = readUrls,
                    listState = listState,
                    onOpenUrl = onOpenUrl,
                    onOpenCloud = onOpenCloud,
                    onOpenLocalSearch = onOpenLocalSearch,
                    onFollowKeyword = { trendsVm.followKeyword(it) },
                    onSelectKeyword = { followsVm.selectKeyword(it) },
                    onRetryFollows = { followsVm.retry() },
                    onRetryTrends = { trendsVm.load() },
                    onManage = { showManage = true }
                )
            }
        }
    }

    // 管理弹层:关注语料就绪(Success)才有意义;入口在关注段各分支内
    val latestFollowsUi = (followsState as? UiState.Success)?.data
    if (showManage && latestFollowsUi != null) {
        FollowsManageSheet(
            keywords = latestFollowsUi.keywords,
            suggestions = latestFollowsUi.suggestions,
            onDismiss = { showManage = false },
            onAdd = { followsVm.addKeyword(it) },
            onRemove = { followsVm.removeKeyword(it) }
        )
    }
}

/** 热词页主体:单 LazyColumn = 「我的关注」段 → 热词榜段,两段状态独立分支。 */
@Composable
private fun HotwordsContent(
    followsState: UiState<com.peng.ainewshub.ui.follows.FollowsUi>,
    trendsState: TrendsState,
    followedKeywords: Set<String>,
    readUrls: Set<String>,
    listState: LazyListState,
    onOpenUrl: (String, String, String) -> Unit,
    onOpenCloud: () -> Unit,
    onOpenLocalSearch: (String) -> Unit,
    onFollowKeyword: (String) -> Unit,
    onSelectKeyword: (String) -> Unit,
    onRetryFollows: () -> Unit,
    onRetryTrends: () -> Unit,
    onManage: () -> Unit
) {
    val context = LocalContext.current
    // 当前展开的热词词条(单展开,再点收起);瞬态 UI 状态,切 tab 丢失可接受
    var expandedTerm by remember { mutableStateOf<String?>(null) }
    val haptics = rememberHaptics()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 根 tab 末项可停到药丸之上(药丸高 + 16dp 呼吸空间)
        contentPadding = PaddingValues(bottom = BottomBarPillHeight + 16.dp)
    ) {
        // ===== 上段:我的关注(关键词命中流) =====
        item(key = "follows-head", contentType = "section-head") {
            SectionHeader(title = stringResource(R.string.follows_section_title), large = true)
        }
        when (followsState) {
            is UiState.Loading -> item(key = "follows-skeleton", contentType = "skeleton") {
                RankRowSkeletonList(count = 3)
            }
            is UiState.Error -> item(key = "follows-error", contentType = "notice") {
                SectionNotice(
                    title = stringResource(R.string.common_load_failed),
                    message = followsState.message,
                    actionLabel = stringResource(R.string.common_retry),
                    onAction = onRetryFollows
                )
            }
            is UiState.Success -> {
                val ui = followsState.data
                when {
                    // 无关注词:收成一行引导(点击开管理弹层),不占整屏引导态
                    ui.keywords.isEmpty() -> item(key = "follows-guide", contentType = "notice") {
                        FollowsGuideRow(onManage = onManage)
                    }
                    // 有关注词但今天没有命中
                    ui.items.isEmpty() -> item(key = "follows-empty", contentType = "notice") {
                        SectionNotice(
                            title = stringResource(R.string.follows_empty_no_match_title),
                            message = stringResource(R.string.follows_empty_no_match_subtitle),
                            actionLabel = stringResource(R.string.follows_manage_action),
                            onAction = onManage
                        )
                    }
                    // 命中流:关键词 chips + 条目列表 + 页脚
                    else -> {
                        // key:url(空 url 只读条目以序号消歧;重复 url 以出现序号消歧)。
                        // (LazyListScope 非组合上下文不能 remember;小列表逐次重算可忽略)
                        val itemKeys = run {
                            val seenUrls = mutableMapOf<String, Int>()
                            ui.items.mapIndexed { i, item ->
                                val base = item.entry.url.ifBlank { "blank-$i" }
                                val dup = seenUrls.getOrPut(base) { 0 }
                                seenUrls[base] = dup + 1
                                base + if (dup == 0) "" else "#$dup"
                            }
                        }
                        item(key = "follows-keywords", contentType = "follows-head-row") {
                            FollowsHeaderRow(ui = ui, onSelect = onSelectKeyword, onManage = onManage)
                        }
                        itemsIndexed(
                            ui.items,
                            key = { i, _ -> "fw-${itemKeys[i]}" },
                            contentType = { _, _ -> "follows-item" }
                        ) { i, feedItem ->
                            val entry = feedItem.entry
                            val sourceLabel = followsSourceLabel(context, entry.source)
                            FollowItemRow(
                                item = feedItem,
                                sourceLabel = sourceLabel,
                                isRead = entry.url.isNotEmpty() && entry.url in readUrls,
                                onClick = { onOpenUrl(entry.url, entry.title, sourceLabel) }
                            )
                        }
                        item(key = "follows-footer", contentType = "footer") { FollowsFooter(ui = ui) }
                    }
                }
            }
        }

        // ===== 下段:近 N 天热词榜 + 词云入口 =====
        when (trendsState) {
            is TrendsState.Loading -> item(key = "trends-skeleton", contentType = "skeleton") {
                RankRowSkeletonList(count = 5)
            }
            is TrendsState.NoData -> item(key = "trends-empty", contentType = "notice") {
                SectionNotice(
                    title = stringResource(R.string.trends_no_data_title),
                    message = stringResource(R.string.trends_no_data_subtitle),
                    actionLabel = stringResource(R.string.common_retry),
                    onAction = onRetryTrends
                )
            }
            is TrendsState.Error -> item(key = "trends-error", contentType = "notice") {
                SectionNotice(
                    title = stringResource(R.string.trends_load_failed),
                    message = trendsState.message,
                    actionLabel = stringResource(R.string.common_retry),
                    onAction = onRetryTrends
                )
            }
            is TrendsState.Success -> {
                val digest = trendsState.digest
                item(key = "trends-caption", contentType = "caption") {
                    TrendsCaptionRow(digest = digest, onOpenCloud = onOpenCloud)
                }
                val keywords = digest.keywords
                itemsIndexed(
                    keywords,
                    key = { _, k -> "hot-kw-${k.term}" },
                    contentType = { _, _ -> "keyword" }
                ) { index, keyword ->
                    Column {
                        KeywordRow(
                            rank = index + 1,
                            keyword = keyword,
                            expanded = expandedTerm == keyword.term,
                            onToggle = {
                                haptics.tick()
                                expandedTerm = if (expandedTerm == keyword.term) null else keyword.term
                            },
                            onOpenUrl = onOpenUrl,
                            onFollowKeyword = onFollowKeyword,
                            onSearchTerm = onOpenLocalSearch,
                            followedKeywords = followedKeywords
                        )
                    }
                }
                if (digest.generatedAt > 0) {
                    item(key = "trends-footer", contentType = "footer") { TrendsFooter(digest = digest) }
                }
            }
        }
    }
}

/**
 * 无关注词时的收起式引导行:surfaceContainerLow 浅底长条(+ 图标 + 引导文案 +
 * chevron),点击直达管理弹层。替代原整屏 onboarding 空态(与「热词榜在下」的
 * 单页结构更相称 —— 引导不挡路,想用再展开)。
 */
@Composable
private fun FollowsGuideRow(onManage: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.small)
            .background(cs.surfaceContainerLow)
            .clickable(onClick = onManage)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 「+」引导前缀符(去图标)
        Text(
            text = "+",
            style = AppText.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = cs.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.follows_section_guide),
            style = AppText.bodySmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurfaceVariant
        )
    }
}
