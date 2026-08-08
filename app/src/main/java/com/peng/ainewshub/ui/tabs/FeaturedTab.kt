package com.peng.ainewshub.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.data.Mode
import com.peng.ainewshub.data.NewsItem
import com.peng.ainewshub.ui.HotTopicsViewModel
import com.peng.ainewshub.ui.ItemsViewModel
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.HotTopicsSection
import com.peng.ainewshub.ui.items.ItemsScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AIHot 精选二级页(原首页独立根 tab,现从「更多」页浏览组末位进入)。
 *
 * 顶栏:[返回箭头] + [标题 + 日期];列表顶部嵌入「今日热点」卡片与「最新精选」标题。
 * 因是二级页(底栏不悬浮),列表底部不再预留 BottomBarReservedHeight。
 *
 * 列表顶部装饰区(header)依次渲染:
 *  1. 今日热点卡片(/hot-topics,点击打开站内阅读页;失败/为空时自动隐藏)
 *  2. 「最新精选」区块标题 + 右侧「全部」入口(分隔热点与下方信息流)
 *
 * ViewModel 用 `key="featured"` 取独立实例,与「全部」「搜索」互不串扰。
 */
@Composable
fun FeaturedTab(
    onItemClick: (NewsItem) -> Unit,
    onOpenUrl: (String, String) -> Unit,
    onOpenAll: () -> Unit,
    onBack: () -> Unit,
    // 滚动状态由 MainActivity 上提持有:push 二级页返回后保持位置(见其内注释)
    listState: LazyListState,
    reselectSignal: Int = 0
) {
    val vm: ItemsViewModel = viewModel(key = "featured")
    // 进入精选时强制 mode=SELECTED(防止从其它页切来时 mode 残留)
    LaunchedEffect(Unit) { vm.setMode(Mode.SELECTED) }
    // 今日热点 ViewModel 提升到本层持有:下拉刷新时与列表联动刷新
    val hotVm: HotTopicsViewModel = viewModel(key = "hot-topics")

    // 实时日期「月日 · 周几」(格式串随界面语言,见 date_fmt_month_day_week)
    val datePattern = stringResource(R.string.date_fmt_month_day_week)
    val dateText = remember(datePattern) {
        SimpleDateFormat(datePattern, Locale.getDefault()).format(Date())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.source_title_aihot_featured),
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                horizontalPadding = 18.dp,
                actions = {
                    Text(
                        text = dateText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
    ) { padding ->
        ItemsScreen(
            onItemClick = onItemClick,
            vm = vm,
            listState = listState,
            // 二级页底栏不悬浮,列表底部不再预留浮动药丸底栏高度
            reserveBottomBarSpace = false,
            // 下拉刷新时联动刷新「今日热点」
            onRefreshExtra = { hotVm.refresh() },
            reselectSignal = reselectSignal,
            header = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 今日热点卡片
                    HotTopicsSection(
                        onOpen = onOpenUrl,
                        vm = hotVm,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                    )
                    // 「最新精选」区块标题 + 右侧「全部」入口(跳转到全部动态页)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.featured_latest),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.featured_all),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onOpenAll() }
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding)
        )
    }
}
