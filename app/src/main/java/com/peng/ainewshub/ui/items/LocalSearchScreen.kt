package com.peng.ainewshub.ui.items

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peng.ainewshub.R
import com.peng.ainewshub.data.SearchIndexRepository
import com.peng.ainewshub.data.SearchItemEntity
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.components.SectionHeader
import com.peng.ainewshub.ui.components.rememberReadUrls
import com.peng.ainewshub.ui.more.DEFAULT_SOURCE_ORDER
import com.peng.ainewshub.ui.more.SettingsStore
import com.peng.ainewshub.ui.more.sourceMeta
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * 本地搜索页(独立二级页,总览顶栏进入)—— 查设备内 Room 索引,只覆盖本 App
 * 自己的数据:联网浏览 8 源时各 Repository 自动回填的批次条目(见
 * [SearchIndexRepository]),与「全部动态」页的联网搜索(aihot 第三方 API)完全独立,
 * 互不影响、互不共用页面。
 *
 * 行为:
 *  - 输入 ≥2 字 150ms 防抖后查索引(LIKE,FTS 分词不支持中文;索引万级以内即时返回)
 *  - 结果行:标题/摘要/来源,已读(打开 URL 命中浏览历史)标题弱化,点击直达内置 WebView
 *  - 查询为空时展示「搜索历史」(与联网搜索页共用同一份 —— 都是用户的搜索行为;
 *    热词发现区不出现在本页,那来自第三方 API)
 *
 * @param onBack 返回回调
 * @param onOpenUrl 结果点击直达内置 WebView(source 标签传条目自身来源)
 * @param listState 滚动状态由 MainActivity 按 Page 持有:进 WebView 返回后保持位置
 */
@OptIn(FlowPreview::class, ExperimentalLayoutApi::class)
@Composable
fun LocalSearchScreen(
    onBack: () -> Unit,
    onOpenUrl: (String, String, String) -> Unit,
    listState: LazyListState
) {
    var text by rememberSaveable { mutableStateOf("") }

    // 查询输入:150ms 防抖(索引小查询快,无需 300ms;仍避免每键一查)
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        snapshotFlow { text }
            .debounce(150)
            .collect { query = it.trim() }
    }
    val results by remember(query) { SearchIndexRepository.search(query) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // 已读判定:与各列表共用同一来源(浏览历史 URL 集合)
    val readUrls = rememberReadUrls()

    // 搜索历史(display_prefs 持久化,最近 10 条;与联网搜索页共用)
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val searchHistory by settingsStore.searchHistoryFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    // 清空搜索历史确认弹窗:破坏性操作与收藏/历史清空同范式,不直接清
    var showClearHistoryDialog by rememberSaveable { mutableStateOf(false) }

    // 仅「用户明确提交搜索」时记录(键盘搜索键 / 点历史 chip),防抖自动查询不入库
    fun submitSearch(term: String) {
        val t = term.trim()
        if (t.length < 2) return
        text = t
        scope.launch { settingsStore.addSearchHistory(t) }
    }

    // 清空搜索历史二次确认(此前注释自认「不做二次确认」,是全 App 唯一无确认的清空操作)
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text(stringResource(R.string.search_clear_history_title)) },
            text = { Text(stringResource(R.string.search_clear_history_message)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { settingsStore.clearSearchHistory() }
                    showClearHistoryDialog = false
                }) { Text(stringResource(R.string.items_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LocalSearchTopBar(
                text = text,
                onTextChange = { text = it },
                onSearch = { submitSearch(text) },
                onClear = { text = "" },
                onBack = onBack
            )
        }
    ) { padding ->
        // imePadding:输入法弹出时结果/历史区收在键盘之上
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            val queryActive = text.trim().length >= 2
            when {
                // 查询为空:搜索历史发现区(无历史时给本页说明的引导空态)
                !queryActive -> {
                    if (searchHistory.isEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.local_search_intro_title),
                            subtitle = stringResource(R.string.local_search_intro_subtitle),
                            icon = Icons.Outlined.Devices
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            SectionHeader(
                                title = stringResource(R.string.search_history_title),
                                trailing = {
                                    // 弹确认框后再清(破坏性操作统一范式)
                                    Text(
                                        text = stringResource(R.string.items_clear),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = androidx.compose.material3.ripple(bounded = false),
                                            onClick = { showClearHistoryDialog = true }
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
                                searchHistory.forEach { term ->
                                    WordChip(text = term, onClick = { submitSearch(term) })
                                }
                            }
                        }
                    }
                }
                // 无结果:SearchOff 图标 + 换关键词引导
                results.isEmpty() -> EmptyState(
                    title = stringResource(R.string.search_local_empty_title),
                    subtitle = stringResource(R.string.search_local_empty_subtitle),
                    icon = Icons.Outlined.SearchOff
                )
                // 结果列表:点击直达 WebView;已读弱化标题
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items = results, key = { it.url }) { e ->
                        val label = localSourceLabel(e.source)
                        LocalSearchRow(
                            entity = e,
                            sourceLabel = label,
                            isRead = e.url in readUrls,
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
        }
    }
}

/** 源 key → 本地化标题;非 8 源 key(aihot 条目的媒体名等)原样返回。 */
@Composable
private fun localSourceLabel(source: String): String =
    if (source in DEFAULT_SOURCE_ORDER) sourceMeta(source).title else source

/**
 * 本地搜索结果行 —— 结构对齐 NewsCard(标题/摘要/来源)但无时间栏与热度徽章
 * (索引不含这些字段);已读时标题降透明弱化(与各列表一致)。
 */
@Composable
private fun LocalSearchRow(
    entity: SearchItemEntity,
    sourceLabel: String,
    isRead: Boolean,
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
            color = if (isRead) cs.onSurface.copy(alpha = AppAlpha.readDim) else cs.onSurface,
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
 * 词条 chip —— surfaceContainerHigh 底胶囊,点击 = 填入并立即搜索。
 * 限宽 320dp 防长词条撑破行,超出单行省略。
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
 * 自定义搜索顶栏 —— 与联网搜索页(SearchScreen)同款结构(绕开 TopAppBar 的
 * title 槽位压缩,搜索框与列表行共用 18dp padding 左右对齐)。两页刻意各自持有
 * 私有实现:本地搜索页独立演进,不与联网搜索页耦合。
 */
@Composable
private fun LocalSearchTopBar(
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
                .padding(horizontal = 18.dp),  // 与列表行同一 padding,保证左右对齐
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
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
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
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.local_search_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                if (text.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    // 清空触控目标:铺满 40dp 胶囊高度的方形命中区(此前仅图标本身 16dp 可点且无涟漪)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = androidx.compose.material3.ripple(),
                                onClick = onClear
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Clear,
                            contentDescription = stringResource(R.string.items_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}
