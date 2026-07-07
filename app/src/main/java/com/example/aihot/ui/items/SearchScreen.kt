package com.example.aihot.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aihot.data.NewsItem
import com.example.aihot.ui.EmptyState
import com.example.aihot.ui.LoadingState
import com.example.aihot.ui.NewsCard
import com.example.aihot.ui.ItemsViewModel
import com.example.aihot.ui.UiState

/**
 * 搜索屏幕:独立的搜索栏 + 复用 ItemsViewModel 的 query 筛选。
 * 输入 ≥2 字触发 /items?q= 搜索。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onItemClick: (NewsItem) -> Unit,
    vm: ItemsViewModel = viewModel()
) {
    var text by rememberSaveable { mutableStateOf(vm.filter.value.query ?: "") }
    val state by vm.state.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            SearchTopBar(
                text = text,
                onTextChange = {
                    text = it
                    vm.setQuery(it)
                },
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
            when (val s = state) {
                is UiState.Loading -> {
                    if (filter.isSearching) LoadingState()
                    else EmptyState(
                        title = "搜索 AI 动态",
                        subtitle = "输入关键词,如「Claude」「OpenAI」「机器人」"
                    )
                }
                is UiState.Error -> com.example.aihot.ui.ErrorState(
                    message = s.message,
                    onRetry = { vm.refresh() }
                )
                is UiState.Success -> {
                    if (items.isEmpty()) {
                        EmptyState(
                            title = "未找到相关内容",
                            subtitle = "试试换个关键词"
                        )
                    } else {
                        LazyColumn(
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

/**
 * 自定义搜索顶栏 —— 绕开 TopAppBar(title 槽位被 navigationIcon 压缩),
 * 让搜索框与列表卡片共用 18dp horizontal padding,左右边缘精确对齐。
 */
@Composable
private fun SearchTopBar(
    text: String,
    onTextChange: (String) -> Unit,
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
                contentDescription = "返回",
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
 */
@Composable
private fun SearchField(
    text: String,
    onTextChange: (String) -> Unit,
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
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    Text(
                        text = "搜索 AI 动态…",
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
                contentDescription = "清空",
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
