package com.example.aihot.ui.more

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.AppTopBarDefaults

/**
 * 信息源(Sources)二级页 —— Hub 浏览区的独立出口,从「更多」页 push 进入。
 *
 * 原 MoreScreen 的「浏览」组(8 个源磁贴)整体迁移至此:8 个第三方源 + AIHot 精选。
 *  - HackerNews / GitHub Trending / LinuxDo / HuggingFace Paper Trending / Product Hunt
 *    / The Rundown AI / OpenAI x Anthropic / stormzhang AI 资讯 —— 品牌色图标块(固定品牌色,收口于 [SourceBrand])
 *  - AIHot 精选 —— 末位入口(复用 FeaturedTab,UI 含今日热点 + 最新精选 + 「全部 ›」)
 *
 * 二级页惯例:顶栏带返回箭头、标题用 secondaryTitleFontSize,列表不预留浮动底栏
 * (二级页底栏不悬浮)。无章节条(顶栏标题即「信息源」,再加章节条重复)。
 */
@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    onOpenHackerNews: () -> Unit,
    onOpenGitHubTrending: () -> Unit,
    onOpenLinuxDo: () -> Unit,
    onOpenStormzhangAiNews: () -> Unit,
    onOpenHuggingFacePapers: () -> Unit,
    onOpenProductHunt: () -> Unit,
    onOpenRundownAi: () -> Unit,
    onOpenOpenAiAnthropicNews: () -> Unit,
    onOpenFeaturedHub: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "信息源",
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            // 二级页底栏不悬浮,无需预留 BottomBarReservedHeight
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            item {
                IconTileRow(
                    icon = Icons.Filled.Whatshot,
                    brand = SourceBrand.HackerNews,
                    title = "HackerNews",
                    subtitle = "HackerNews 热门榜单",
                    onClick = onOpenHackerNews
                )
            }
            item {
                IconTileRow(
                    icon = Icons.Filled.Code,
                    brand = SourceBrand.GitHub,
                    title = "GitHub Trending",
                    subtitle = "GitHub 热门仓库",
                    onClick = onOpenGitHubTrending
                )
            }
            item {
                IconTileRow(
                    icon = Icons.Filled.Forum,
                    brand = SourceBrand.LinuxDo,
                    title = "LinuxDo 热榜",
                    subtitle = "L 站热门话题",
                    onClick = onOpenLinuxDo
                )
            }
            item {
                IconTileRow(
                    icon = Icons.Filled.School,
                    brand = SourceBrand.HuggingFace,
                    title = "HuggingFace Paper Trending",
                    subtitle = "热门 AI 论文榜单",
                    onClick = onOpenHuggingFacePapers
                )
            }
            item {
                IconTileRow(
                    icon = Icons.Filled.RocketLaunch,
                    brand = SourceBrand.ProductHunt,
                    title = "Product Hunt",
                    subtitle = "每日新产品榜单",
                    onClick = onOpenProductHunt
                )
            }
            item {
                IconTileRow(
                    icon = Icons.AutoMirrored.Filled.Article,
                    brand = SourceBrand.TheRundownAi,
                    title = "The Rundown AI",
                    subtitle = "AI 日更 newsletter",
                    onClick = onOpenRundownAi
                )
            }
            item {
                IconTileRow(
                    icon = Icons.Filled.Business,
                    brand = SourceBrand.OpenAiAnthropicNews,
                    title = "OpenAI x Anthropic",
                    subtitle = "OpenAI + Anthropic 厂商动态",
                    onClick = onOpenOpenAiAnthropicNews
                )
            }
            item {
                IconTileRow(
                    icon = Icons.Filled.Newspaper,
                    brand = SourceBrand.Stormzhang,
                    title = "stormzhang AI 资讯",
                    subtitle = "每日 AI 资讯聚合",
                    onClick = onOpenStormzhangAiNews
                )
            }
            item {
                IconTileRow(
                    icon = Icons.Filled.Whatshot,
                    brand = SourceBrand.AiHot,
                    title = "AIHot 精选",
                    subtitle = "自家 AI 资讯精选",
                    showDivider = false,
                    onClick = onOpenFeaturedHub
                )
            }
        }
    }
}
