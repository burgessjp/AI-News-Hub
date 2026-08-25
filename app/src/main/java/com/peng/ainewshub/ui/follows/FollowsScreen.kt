package com.peng.ainewshub.ui.follows

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ripple
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.data.FollowFeedItem
import com.peng.ainewshub.data.FollowsRepository
import com.peng.ainewshub.data.SummaryRepository
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.BottomBarPillHeight
import com.peng.ainewshub.ui.components.BrandWordmark
import com.peng.ainewshub.ui.components.RankRowSkeletonList
import com.peng.ainewshub.ui.components.SectionHeader
import com.peng.ainewshub.ui.components.rememberReadUrls
import com.peng.ainewshub.ui.more.MAX_FOLLOWED_KEYWORDS
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「关注」tab 根屏(底栏第 3 个根 tab)—— 关键词订阅的命中流。
 * 原为趋势页顶栏图标进入的二级页(Page.Follows),现升为根 tab。
 *
 * 语料为当日总览 Top10 + 8 源结构化摘要(见 [com.peng.ainewshub.data.FollowsRepository]),
 * 过滤在 [FollowsViewModel] 内完成;本页只渲染三种形态:
 *  - **onboarding**:还没有关注词 → 引导 + 今日热词推荐一键添加;
 *  - **命中流**:关键词 chips(点选单词过滤/再点恢复)+ 命中条目列表 + 页脚时效标注;
 *  - **无命中**:有关注词但今天没有命中 → 引导换个词。
 *
 * 行为对齐既有根 tab(趋势):点击经 [onOpenUrl] 进内置 WebView(已读置灰自动联动浏览历史);
 * 下拉刷新绕过归档缓存重拉语料(仅 Success 分支,不回骨架);重击当前 tab 滚回顶部并重读。
 *
 * @param onOpenUrl 条目点击直达内置 WebView(source 标签传条目自身来源)
 * @param listState 滚动状态由 AiNewsHubApp 同层上提持有:切 tab / 进二级页返回后保持位置
 * @param reselectSignal 重击当前 tab 的信号(每次递增),据此滚回顶部并刷新
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FollowsScreen(
    onOpenUrl: (String, String, String) -> Unit,
    listState: LazyListState,
    reselectSignal: Int = 0,
    vm: FollowsViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val readUrls = rememberReadUrls()
    var showManage by rememberSaveable { mutableStateOf(false) }

    // 重击当前 tab:滚回顶部 + 重读语料(归档取数带缓存,低开销;同趋势 tab 套路)。
    // lastHandled 防「重新进入组合就自动刷新」。
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
            // 一级根 tab 规格:品牌 wordmark;刷新收口到下拉手势(同趋势 tab)
            AppTopBar(
                title = "AI NEWS HUB",
                titleContent = {
                    BrandWordmark(modifier = Modifier.height(44.dp))
                },
                horizontalPadding = 18.dp
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 列表可滚入药丸 TAB 之下,但可视区不超出药丸底缘(同趋势 tab)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            when (val s = state) {
                is UiState.Loading -> RankRowSkeletonList()
                is UiState.Error -> ErrorState(
                    message = s.message,
                    onRetry = { vm.retry() }
                )
                is UiState.Success -> PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { vm.refresh() }
                ) {
                    val ui = s.data
                    when {
                        // onboarding:还没有关注词,引导添加(推荐词直接可点)
                        ui.keywords.isEmpty() -> FollowsOnboarding(
                            ui = ui,
                            onAdd = vm::addKeyword,
                            onManage = { showManage = true }
                        )
                        // 有关注词但今天没有命中
                        ui.items.isEmpty() -> FollowsNoMatch(onManage = { showManage = true })
                        // 命中流:关键词 chips + 条目列表 + 页脚
                        else -> FollowsContent(
                            ui = ui,
                            listState = listState,
                            readUrls = readUrls,
                            onSelect = vm::selectKeyword,
                            onManage = { showManage = true },
                            onOpenUrl = onOpenUrl
                        )
                    }
                }
            }
        }
    }

    // 管理弹层:数据就绪(Success)才有意义;入口都在 Success 分支内
    val latestUi = (state as? UiState.Success)?.data
    if (showManage && latestUi != null) {
        FollowsManageSheet(
            keywords = latestUi.keywords,
            suggestions = latestUi.suggestions,
            onDismiss = { showManage = false },
            onAdd = vm::addKeyword,
            onRemove = vm::removeKeyword
        )
    }
}

/** 命中流主体:关键词区 + 条目列表 + 页脚时效标注。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FollowsContent(
    ui: FollowsUi,
    listState: LazyListState,
    readUrls: Set<String>,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onOpenUrl: (String, String, String) -> Unit
) {
    // key:url(空 url 只读条目以序号消歧;重复 url 以出现序号消歧,保证唯一)
    val context = LocalContext.current
    val itemKeys = remember(ui.items) {
        val seen = mutableMapOf<String, Int>()
        ui.items.mapIndexed { i, item ->
            val base = item.entry.url.ifBlank { "blank-$i" }
            val dup = seen.getOrPut(base) { 0 }
            seen[base] = dup + 1
            base + if (dup == 0) "" else "#$dup"
        }
    }
    LazyColumn(
        state = listState,
        // 底部预留悬浮药丸底栏(末项可停到药丸之上,同趋势 tab)
        contentPadding = PaddingValues(top = 4.dp, bottom = BottomBarPillHeight + 16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "keywords") {
            FollowsHeaderRow(ui = ui, onSelect = onSelect, onManage = onManage)
        }
        itemsIndexed(items = ui.items, key = { i, _ -> itemKeys[i] }) { i, feedItem ->
            val entry = feedItem.entry
            val sourceLabel = followsSourceLabel(context, entry.source)
            FollowItemRow(
                item = feedItem,
                sourceLabel = sourceLabel,
                isRead = entry.url.isNotEmpty() && entry.url in readUrls,
                onClick = { onOpenUrl(entry.url, entry.title, sourceLabel) }
            )
            if (i != ui.items.lastIndex) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            }
        }
        item(key = "footer") { FollowsFooter(ui = ui) }
    }
}

/** 关键词区:统计行 + 编辑入口 + 关键词 chips(点选单选过滤,再点恢复全部)。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FollowsHeaderRow(
    ui: FollowsUi,
    onSelect: (String) -> Unit,
    onManage: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.follows_stats_caption,
                    ui.keywords.size,
                    // 单选过滤时以当前过滤结果计数,与列表所见一致
                    ui.items.size
                ),
                style = AppText.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onManage) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.follows_add_action),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ui.keywords.forEach { keyword ->
                FollowKeywordChip(
                    text = keyword,
                    selected = keyword == ui.selectedKeyword,
                    onClick = { onSelect(keyword) }
                )
            }
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/**
 * 关注词 chip —— 配色对齐摘要 Tab 源名 chips:选中 primary 实底 SemiBold,
 * 未选 surfaceContainerHigh;点选 = 只看该词,再点恢复全部。
 */
@Composable
private fun FollowKeywordChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = text,
        style = AppText.bodySmall,
        fontWeight = if (selected) FontWeight.SemiBold else null,
        color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 200.dp)
            .clip(CircleShape)
            .background(if (selected) cs.primary else cs.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/**
 * 命中条目行 —— 结构对齐本地搜索结果行(标题/摘要/来源),另带:
 * 命中词(primary 小字)、总览条目的指标行与 Breaking 标签。
 * url 为空的条目只读不可点(与摘要 Tab 行为一致)。
 */
@Composable
private fun FollowItemRow(
    item: FollowFeedItem,
    sourceLabel: String,
    isRead: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val entry = item.entry
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                enabled = entry.url.isNotEmpty(),
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (entry.breaking) {
                FollowBreakingTag()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = entry.title,
                style = AppText.titleItem,
                color = if (isRead) cs.onSurface.copy(alpha = AppAlpha.readDim) else cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (entry.desc.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.desc,
                style = AppText.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 命中词:本页的身份标识,primary 强调
            Text(
                text = item.matchedKeywords.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = cs.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (entry.metrics.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.metrics,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = sourceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 「Breaking」标签 —— 样式对齐总览页同款(tertiary 实底小胶囊)。 */
@Composable
private fun FollowBreakingTag() {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(cs.tertiary)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(R.string.overview_breaking_tag),
            style = AppText.caption,
            fontWeight = FontWeight.Bold,
            color = cs.onTertiary
        )
    }
}

/** 页脚:缺失源标注(总览伪源映射为 Tab 名)+ 数据截至时效。 */
@Composable
private fun FollowsFooter(ui: FollowsUi) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (ui.missingSources.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.follows_missing_sources,
                    ui.missingSources.joinToString("、") { followsSourceLabel(context, it) }
                ),
                style = AppText.caption,
                color = cs.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
        }
        if (ui.dataFetchedAt > 0) {
            Text(
                text = stringResource(
                    R.string.overview_data_until,
                    formatFetchedAt(context, ui.dataFetchedAt)
                ),
                style = AppText.caption,
                color = cs.onSurfaceVariant
            )
        }
    }
}

/** onboarding 空态:引导语 + 动作进管理弹层 + 推荐词直接点加。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FollowsOnboarding(
    ui: FollowsUi,
    onAdd: (String) -> Unit,
    onManage: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // 底部预留悬浮药丸底栏(scroll 之后 = 内容 padding,推荐词可滚到药丸之上)
            .padding(bottom = BottomBarPillHeight + 16.dp)
    ) {
        EmptyState(
            title = stringResource(R.string.follows_empty_no_keywords_title),
            subtitle = stringResource(R.string.follows_empty_no_keywords_subtitle),
            icon = Icons.Outlined.PersonAddAlt,
            actionLabel = stringResource(R.string.follows_manage_action),
            onAction = onManage
        )
        if (ui.suggestions.isNotEmpty()) {
            SectionHeader(title = stringResource(R.string.follows_suggestions_title))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ui.suggestions.forEach { word -> FollowSuggestChip(text = word, onClick = { onAdd(word) }) }
            }
        }
    }
}

/** 无命中空态:引导换词或明天再来,动作直达管理弹层。 */
@Composable
private fun FollowsNoMatch(onManage: () -> Unit) {
    EmptyState(
        title = stringResource(R.string.follows_empty_no_match_title),
        subtitle = stringResource(R.string.follows_empty_no_match_subtitle),
        icon = Icons.Outlined.SearchOff,
        actionLabel = stringResource(R.string.follows_manage_action),
        onAction = onManage
    )
}

/**
 * 关键词管理弹层 —— 输入添加 / 已关注词删除 / 推荐词一键加。
 * 上限 [MAX_FOLLOWED_KEYWORDS]:达上限时禁用添加并提示(存储层同样兜底忽略)。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FollowsManageSheet(
    keywords: List<String>,
    suggestions: List<String>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    val atLimit = keywords.size >= MAX_FOLLOWED_KEYWORDS
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
        ) {
            // 弹层标题对齐全 App 弹层惯例 titleSection(20sp),与 14sp 小节条/12sp chips 拉开层级
            Text(
                text = stringResource(R.string.follows_manage_title),
                style = AppText.titleSection
            )
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                KeywordInputPill(
                    text = text,
                    onTextChange = { text = it },
                    onSubmit = {
                        onAdd(text)
                        text = ""
                    },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        onAdd(text)
                        text = ""
                    },
                    enabled = text.isNotBlank() && !atLimit
                ) {
                    Text(stringResource(R.string.follows_add_action))
                }
            }
            if (atLimit) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.follows_limit_toast),
                    style = AppText.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (keywords.isNotEmpty()) {
                // 弹层内容层已统一 18dp 边距:章节条去竖线、清零水平缩进,与其他内容左对齐
                SectionHeader(
                    title = stringResource(R.string.follows_following_section),
                    showAccent = false,
                    contentPadding = PaddingValues(top = 12.dp, bottom = 6.dp)
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    keywords.forEach { keyword ->
                        FollowRemoveChip(
                            text = keyword,
                            onRemove = { onRemove(keyword) }
                        )
                    }
                }
            }
            if (suggestions.isNotEmpty()) {
                SectionHeader(
                    title = stringResource(R.string.follows_suggestions_title),
                    showAccent = false,
                    contentPadding = PaddingValues(top = 12.dp, bottom = 6.dp)
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    suggestions.forEach { word ->
                        FollowSuggestChip(text = word, onClick = { onAdd(word) })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** 输入胶囊 —— 结构对齐本地搜索顶栏的搜索框(返回键/完成键即提交)。 */
@Composable
private fun KeywordInputPill(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.follows_input_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    inner()
                }
            },
            modifier = Modifier.weight(1f)
        )
    }
}

/** 可删除的已关注词 chip:文字 + ✕。 */
@Composable
private fun FollowRemoveChip(text: String, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .widthIn(max = 220.dp)
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .clickable(onClick = onRemove)
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Text(
            text = text,
            style = AppText.bodySmall,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.follows_remove_cd, text),
            tint = cs.onSurfaceVariant,
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onRemove
                )
                .padding(2.dp)
        )
    }
}

/** 推荐词 chip:onboarding 空态与管理弹层共用,点击即关注。 */
@Composable
private fun FollowSuggestChip(text: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = text,
        style = AppText.bodySmall,
        color = cs.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 320.dp)
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** 源 key → 本地化标题;总览伪 key 映射为 Tab 名,未知 key 原样返回(与总览页脚同法)。 */
private fun followsSourceLabel(context: Context, source: String): String =
    if (source == FollowsRepository.OVERVIEW_KEY) context.getString(R.string.tab_overview)
    else SummaryRepository.titleOf(context, source)

/** 数据时刻格式化(「M月d日 HH:mm」,与总览/摘要卡头同规格;模式串走 date_fmt_month_day_time)。 */
private fun formatFetchedAt(context: Context, ms: Long): String =
    runCatching {
        SimpleDateFormat(context.getString(R.string.date_fmt_month_day_time), Locale.getDefault()).format(Date(ms))
    }.getOrDefault("")
