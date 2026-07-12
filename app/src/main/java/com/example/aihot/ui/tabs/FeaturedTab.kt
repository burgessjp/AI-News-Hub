package com.example.aihot.ui.tabs

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aihot.R
import com.example.aihot.data.Mode
import com.example.aihot.data.NewsItem
import com.example.aihot.ui.ItemsViewModel
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.HotTopicsSection
import com.example.aihot.ui.items.ItemsScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 精选 tab —— 顶栏 [App Logo + 日期];列表顶部嵌入「今日热点」卡片与「最新精选」标题。
 *
 * 列表顶部装饰区(header)依次渲染:
 *  1. 今日热点卡片(/hot-topics,点击打开站内阅读页;失败/为空时自动隐藏)
 *  2. 「最新精选」区块标题(分隔热点与下方信息流)
 *
 * ViewModel 用 `key="featured"` 取独立实例,与「全部」「搜索」互不串扰。
 */
@Composable
fun FeaturedTab(
    onItemClick: (NewsItem) -> Unit,
    onOpenUrl: (String, String) -> Unit
) {
    val vm: ItemsViewModel = viewModel(key = "featured")
    // 进入精选 tab 时强制 mode=SELECTED(防止从其它 tab 切来时 mode 残留)
    LaunchedEffect(Unit) { vm.setMode(Mode.SELECTED) }

    // 实时日期「月日 · 周几」(中文区域格式)
    val dateText = remember {
        SimpleDateFormat("M月d日 · E", Locale.CHINA).format(Date())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "AIHot",
                horizontalPadding = 18.dp,
                navigationIcon = {
                    // 左侧 App Logo:直接显示启动器前景图(ic_launcher_foreground)。
                    // 该 PNG 已含完整图标(青色渐变 + 'A' 字形),自身不透明,无需额外背景。
                    // adaptive-icon 规范要求前景四周有 ~25% 安全区,这里用 ContentScale.Crop
                    // 裁掉外圈留白,只保留中心图标本体。
                    // 不用 R.mipmap.ic_launcher —— 它在 API26+ 是 adaptive-icon XML,
                    // Compose painterResource 不支持,会抛 IllegalArgumentException。
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "AIHot",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                },
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
            header = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 今日热点卡片
                    HotTopicsSection(
                        onOpen = onOpenUrl,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                    )
                    // 「最新精选」区块标题
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "最新精选",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding)
        )
    }
}
