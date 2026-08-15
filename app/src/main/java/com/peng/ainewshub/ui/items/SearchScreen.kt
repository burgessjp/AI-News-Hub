package com.peng.ainewshub.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.data.HotTopic
import com.peng.ainewshub.data.NewsItem
import com.peng.ainewshub.data.NewsRepository
import com.peng.ainewshub.data.SearchIndexRepository
import com.peng.ainewshub.data.SearchItemEntity
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.LoadingState
import com.peng.ainewshub.ui.NewsCard
import com.peng.ainewshub.ui.ItemsViewModel
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.SectionHeader
import com.peng.ainewshub.ui.more.DEFAULT_SOURCE_ORDER
import com.peng.ainewshub.ui.more.SettingsStore
import com.peng.ainewshub.ui.more.sourceMeta
import com.peng.ainewshub.ui.theme.AppText
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * 搜索屏幕:独立的搜索栏 + 两种搜索范围。
 *  - 「联网」(默认,原行为):复用 ItemsViewModel 的 query 筛选,输入 ≥2 字触发
 *    /items?q= 搜索(300ms 防抖自动触发);
 *  - 「本地」:查设备内 Room 索引(见 [SearchIndexRepository],覆盖浏览过的 8 源
 *    批次数据),150ms 防抖即时响应,结果直达内置 WebView。
 *
 * 查询为空时展示发现区([SearchDiscovery]):「搜索历史」(display_prefs 持久化,
 * 最近 10 条,可清空)+「热门」(今日热词,复用第三方服务 aihot.virxact.com 的 /hot-topics)。
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onItemClick: (NewsItem) -> Unit,
    // 本地搜索结果直达 WebView 用(联网结果仍走 onItemClick 进详情页)
    onOpenUrl: (String, String, String) -> Unit,
    // 滚动状态由 MainActivity 按 Page 持有:进详情页返回后保持位置
    listState: LazyListState,
    vm: ItemsViewModel = viewModel()
) {
    var text by rememberSaveable { mutableStateOf(vm.filter.value.query ?: "") }
    val state by vm.state.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()

    // 搜索范围:联网(默认) / 本地。本地模式下不把 query 推给 ItemsViewModel,
    // 避免每次击键都打第三方 API;切回联网时若已有词条则恢复搜索。
    var localMode by rememberSaveable { mutableStateOf(false) }

    // 本地搜索输入:150ms 防抖(Room 索引小,无需 300ms;仍避免每键一查)
    var localQuery by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        snapshotFlow { text }
            .debounce(150)
            .collect { localQuery = it.trim() }
    }
    val localResults by remember(localQuery) { SearchIndexRepository.search(localQuery) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // 搜索历史(display_prefs 持久化,最近 10 条,最新在前)
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val searchHistory by settingsStore.searchHistoryFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    // 今日热词:与精选 tab「今日热点」同源(/hot-topics),进页拉一次;
    // 失败/为空时 hotTopics 保持空列表,热词区整块静默不显示
    val newsRepo = remember { NewsRepository() }
    var hotTopics by remember { mutableStateOf<List<HotTopic>>(emptyList()) }
    LaunchedEffect(Unit) {
        hotTopics = runCatching { newsRepo.fetchHotTopics() }.getOrDefault(emptyList())
    }

    // 记录历史的判定:仅在「用户明确提交搜索」时记录(键盘搜索键 / 点历史或热词 chip),
    // 300ms 防抖的自动搜索不记录 —— 避免每敲一个中间词都入库
    fun submitSearch(term: String) {
        val t = term.trim()
        if (t.length < 2) return  // <2 字不会触发搜索(见 isSearching),不入库
        text = t
        // 本地模式不驱动联网搜索(输入已进 localQuery 防抖流)
        if (!localMode) vm.setQuery(t)
        scope.launch { settingsStore.addSearchHistory(t) }
    }

    // 切换搜索范围:进本地 = 停掉联网搜索;回联网 = 恢复当前词条的搜索
    fun selectScope(local: Boolean) {
        localMode = local
        val t = text.trim()
        if (local) vm.setQuery(null)
        else if (t.length >= 2) vm.setQuery(t)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            SearchTopBar(
                text = text,
                onTextChange = {
                    text = it
                    vm.setQuery(it)
                },
                onSearch = { submitSearch(text) },
                onClear = {
                    text = ""
                    vm.setQuery(null)
                },
                onBack = {
                    // 退出搜索时清掉 query,回到精选列表
                    vm.setQuery(null)
                    onBack()
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 范围切换(联网 / 本地):置于搜索框下方,两模式共用发现区与搜索历史
            SearchScopeChips(localMode = localMode, onSelect = { selectScope(it) })

            // 输入已达搜索门槛(两模式通用);联网模式下与 isSearching 等价
            val queryActive = text.trim().length >= 2
            when {
                !queryActive -> {
                    if (searchHistory.isEmpty() && hotTopics.isEmpty()) {
                        // 历史与热词皆空(冷启动 + 热词拉取失败)的兜底引导
                        EmptyState(
                            title = stringResource(R.string.search_empty_title),
                            subtitle = stringResource(R.string.search_empty_subtitle),
                            icon = Icons.Filled.Search
                        )
                    } else {
                        SearchDiscovery(
                            history = searchHistory,
                            hotTopics = hotTopics,
                            onClearHistory = { scope.launch { settingsStore.clearSearchHistory() } },
                            onSubmit = { submitSearch(it) }
                        )
                    }
                }
                // 本地模式:查设备内索引,结果直达 WebView
                localMode -> LocalSearchResults(
                    results = localResults,
                    onOpenUrl = onOpenUrl
                )
                // 联网模式:原搜索结果链路
                else -> when (val s = state) {
                    is UiState.Loading -> LoadingState()
                    is UiState.Error -> com.peng.ainewshub.ui.ErrorState(
                        message = s.message,
                        title = stringResource(R.string.search_error_title),
                        onRetry = { vm.refresh() }
                    )
                    is UiState.Success -> {
                        if (items.isEmpty()) {
                            // 搜索无结果:SearchOff 图标 + 「换关键词」恢复路径
                            EmptyState(
                                title = stringResource(R.string.search_no_result_title),
                                subtitle = stringResource(R.string.items_try_other_keyword),
                                icon = Icons.Outlined.SearchOff
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(vertical = 4.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(items = items, key = { it.id }) { item ->
                                    NewsCard(item = item, onClick = { onItemClick(item) })
                                    if (item.id != items.last().id) {
                                        androidx.compose.material3.HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                            modifier = Modifier.padding(start = 72.dp, end = 18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 范围切换 chips(联网 / 本地)—— 复用列表页分类 chips 的药丸样式,置于搜索框下方。
 */
@Composable
private fun SearchScopeChips(
    localMode: Boolean,
    onSelect: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ScopeChip(
            selected = !localMode,
            label = stringResource(R.string.search_scope_online),
            onClick = { onSelect(false) }
        )
        ScopeChip(
            selected = localMode,
            label = stringResource(R.string.search_scope_local),
            onClick = { onSelect(true) }
        )
    }
}

@Composable
private fun ScopeChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        // 完全圆角药丸,对齐列表页分类 chips
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant
        ),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

/**
 * 本地搜索结果 —— Room 索引命中列表,点击直达内置 WebView(不走详情页:
 * 索引条目来自 8 个异构源,无 NewsItem 全量字段)。
 */
@Composable
private fun LocalSearchResults(
    results: List<SearchItemEntity>,
    onOpenUrl: (String, String, String) -> Unit
) {
    if (results.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.search_local_empty_title),
            subtitle = stringResource(R.string.search_local_empty_subtitle),
            icon = Icons.Outlined.SearchOff
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(vertical = 4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items = results, key = { it.url }) { e ->
            val label = localSourceLabel(e.source)
            LocalSearchRow(
                entity = e,
                sourceLabel = label,
                onClick = { onOpenUrl(e.url, e.title, label) }
            )
            if (e.url != results.last().url) {
                androidx.compose.material3.HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp)
                )
            }
        }
    }
}

/** 源 key → 本地化标题;非 8 源 key(aihot 条目的媒体名等)原样返回。 */
@Composable
private fun localSourceLabel(source: String): String =
    if (source in DEFAULT_SOURCE_ORDER) sourceMeta(source).title else source

/**
 * 本地搜索结果行 —— 结构对齐 [NewsCard](标题/摘要/来源)但无时间栏与热度徽章
 * (索引不含这些字段);已读弱化由 workstream C 统一接入。
 */
@Composable
private fun LocalSearchRow(
    entity: SearchItemEntity,
    sourceLabel: String,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(),
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text(
            text = entity.title,
            style = AppText.titleItem,
            color = cs.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (entity.summary.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = entity.summary,
                style = AppText.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = sourceLabel,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 搜索发现区 —— 查询为空时展示:「搜索历史」(trailing「清空」)+「热门」(今日热词)。
 *
 * 点击任一词条 = 填入并立即搜索(同时记入历史,历史词条置顶去重)。
 * 静态面板用 rememberScrollState 纵向滚动即可 —— 非列表内容,
 * 不涉及「列表滚动状态一律上层持有」的约定。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchDiscovery(
    history: List<String>,
    hotTopics: List<HotTopic>,
    onClearHistory: () -> Unit,
    onSubmit: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        if (history.isNotEmpty()) {
            SectionHeader(
                title = stringResource(R.string.search_history_title),
                trailing = {
                    // 条目少,直接清空,不做二次确认
                    Text(
                        text = stringResource(R.string.items_clear),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = androidx.compose.material3.ripple(bounded = false),
                            onClick = onClearHistory
                        )
                    )
                }
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                history.forEach { term ->
                    WordChip(text = term, onClick = { onSubmit(term) })
                }
            }
        }
        if (hotTopics.isNotEmpty()) {
            SectionHeader(title = stringResource(R.string.search_hot_title))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                hotTopics.forEach { topic ->
                    WordChip(text = topic.title, onClick = { onSubmit(topic.title) })
                }
            }
        }
    }
}

/**
 * 词条 chip —— surfaceContainerHigh 底胶囊,点击 = 填入并立即搜索。
 * 限宽 320dp 防长热词标题撑破行,超出单行省略。
 */
@Composable
private fun WordChip(text: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = cs.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 320.dp)
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/**
 * 自定义搜索顶栏 —— 绕开 TopAppBar(title 槽位被 navigationIcon 压缩),
 * 让搜索框与列表卡片共用 18dp horizontal padding,左右边缘精确对齐。
 */
@Composable
private fun SearchTopBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)   // 与 TopAppBar 默认高度一致
                .padding(horizontal = 18.dp),  // 与列表卡片同一 padding,保证左右对齐
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.material3.ripple(bounded = false),
                        onClick = onBack
                    )
            )
            SearchField(
                text = text,
                onTextChange = onTextChange,
                onSearch = onSearch,
                onClear = onClear,
                modifier = Modifier.weight(1f)
            )
        }
        androidx.compose.material3.HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/**
 * 自定义搜索框 —— 精确控制高度为 40dp(对齐 iOS / Telegram 搜索框标准)。
 *
 * 不用 Material3 TextField(其内部 min height 56dp + container padding 无法压低),
 * 改用 BasicTextField + Row 自定义布局,圆角全圆胶囊,文字与图标垂直居中。
 * 键盘搜索键(onSearch)= 明确提交,记入搜索历史。
 */
@Composable
private fun SearchField(
    text: String,
    onTextChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            textStyle = TextStyle(
                color = cs.onSurface,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
            ),
            cursorBrush = SolidColor(cs.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.search_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant
                    )
                } else {
                    inner()
                }
            },
            modifier = Modifier.weight(1f)
        )
        if (text.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.Clear,
                contentDescription = stringResource(R.string.items_clear),
                tint = cs.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClear
                    )
            )
        }
    }
}
