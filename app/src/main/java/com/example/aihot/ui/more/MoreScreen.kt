package com.example.aihot.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aihot.ui.components.AppCard
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.SettingsGroupHeader
import com.example.aihot.ui.components.SettingsRow

/**
 * 更多 tab —— 聚合次要入口。
 *
 * 视觉与主列表页(Featured/All/Daily)同构:弃用逐行浮动卡片,改为
 * 「品牌头卡片 + 分组章节条 + 扁平行 + hairline 发丝分隔线」。
 *
 * 分组:
 *  - 浏览:历史日报 / HackerNews / 搜索
 *  - 偏好:设置 / 关于
 */
@Composable
fun MoreScreen(
    onOpenArchive: () -> Unit,
    onOpenHackerNews: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { AppTopBar(title = "更多") }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            // 顶/底留白,横向沿用全 App 统一的 18dp。章节条自身满宽,靠左右负边距无关。
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // 品牌头 —— 全页唯一保留卡片,赋予页面身份。
            item { BrandHeader() }

            item { SettingsGroupHeader("浏览") }
            item {
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = "历史日报",
                    subtitle = "查看往期 AI 日报",
                    onClick = onOpenArchive
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Whatshot,
                    title = "HackerNews",
                    subtitle = "HackerNews 热门榜单",
                    onClick = onOpenHackerNews
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Search,
                    title = "搜索",
                    subtitle = "按关键词查找 AI 动态",
                    showDivider = false, // 组内最后一行,组间靠下一条章节条自然分隔
                    onClick = onOpenSearch
                )
            }

            item { SettingsGroupHeader("偏好") }
            item {
                SettingsRow(
                    icon = Icons.Filled.Settings,
                    title = "设置",
                    subtitle = "主题、显示偏好",
                    onClick = onOpenSettings
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Info,
                    title = "关于",
                    subtitle = "版本、数据源、开源依赖",
                    showDivider = false,
                    onClick = onOpenAbout
                )
            }
        }
    }
}

/**
 * 品牌头卡片 —— cyan 圆形 logo + 名称 + slogan。
 *
 * 全页唯一保留的 [AppCard],作为视觉锚点;其余入口走扁平行。
 */
@Composable
private fun BrandHeader() {
    val cs = MaterialTheme.colorScheme
    AppCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // cyan 圆形 logo 块
            Surface(
                color = cs.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "AI",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = cs.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = "AIHot",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "精选每日 AI 资讯聚合客户端",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
    }
}
