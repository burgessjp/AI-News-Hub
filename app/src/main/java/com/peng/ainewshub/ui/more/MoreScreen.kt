package com.peng.ainewshub.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.components.BottomBarReservedHeight
import com.peng.ainewshub.ui.components.BrandWordmark
import com.peng.ainewshub.ui.components.DoubleRule
import com.peng.ainewshub.ui.components.SectionHeader
import com.peng.ainewshub.ui.theme.AppText
import com.peng.ainewshub.data.source.DEFAULT_SOURCE_ORDER

/**
 * 更多/Hub tab —— 聚合次要入口,对齐 "Synthetic Intelligence News" 设计系统的
 * user_hub_profile 原型。
 *
 * 结构(自顶向下,简洁直入):
     *  - 信息源入口:点开 [SourcesScreen] 二级页(Hub 浏览区,8 个源全集,
     *    原内嵌在更多页的「浏览」组,现独立成页)
 *  - 历史组(tertiary 强调):历史回顾(总览/摘要/热词三段 hub)/ 浏览历史 / 收藏
 *    —— 彩色图标块行
 *  - 偏好组(secondary 强调):AI 服务 / 设置 / 关于 —— 浅灰图标块行
 *
 * 「AIHot 精选」原为首页独立根 tab,现收进 [SourcesScreen] 作为末位二级页(复用 FeaturedTab,
 * UI 完全不变:今日热点 + 最新精选列表 + 「全部 ›」入口)。
 * 日报及其历史归档入口已移至「全部动态」页(精选 → 全部 → 日报,日报页内含历史归档按钮)。
 * 搜索入口亦在「全部动态」页顶栏(全部动态是搜索主场景)。
 */
@Composable
fun MoreScreen(
    onOpenSources: () -> Unit,
    onOpenBrowseHistory: () -> Unit,
    onOpenFavorites: () -> Unit,
    // 历史回顾 hub(总览/摘要/热词三段合一;替代原三个独立历史入口)
    onOpenHistoryHub: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAiService: () -> Unit,
    onOpenAbout: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            // 报头与「今天」/「关注」页同构但更简:居中字标 + 双细线(无日期/动作位)。
            // 不走 AppTopBar(MD3 标题槽 start 对齐,字标会靠左)
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
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            // 底部预留浮动药丸底栏高度(MoreTab 是根 tab,底栏悬浮)
            contentPadding = PaddingValues(bottom = BottomBarReservedHeight)
        ) {
            // 信息源入口 —— 进入 Hub 浏览区独立页(SourcesScreen):8 源全集。
            // 用 primary 强调色块(聚合入口非单一源,不挂品牌色),与下方「历史」组同档。
            // 源数量跟随 DEFAULT_SOURCE_ORDER 动态计算,增删源时自动同步,避免再次漏改。
            item {
                MenuRow(
                    title = stringResource(R.string.sources_title),
                    subtitle = pluralStringResource(
                        R.plurals.more_sources_subtitle,
                        DEFAULT_SOURCE_ORDER.size,
                        DEFAULT_SOURCE_ORDER.size
                    ),
                    onClick = onOpenSources
                )
            }

            // 历史组(tertiary 强调)—— 历史回顾(总览/摘要/热词按日期回看 hub,单入口)/
            // 浏览历史 / 收藏
            item { SectionHeader(stringResource(R.string.more_section_history)) }
            item {
                MenuRow(
                    title = stringResource(R.string.history_hub_title),
                    subtitle = stringResource(R.string.more_history_hub_subtitle),
                    onClick = onOpenHistoryHub
                )
            }
            item {
                MenuRow(
                    title = stringResource(R.string.more_browse_history_title),
                    subtitle = stringResource(R.string.more_browse_history_subtitle),
                    onClick = onOpenBrowseHistory
                )
            }
            // 收藏(稍后读):WebView 顶栏星标的文章列表
            item {
                MenuRow(
                    title = stringResource(R.string.more_favorites_title),
                    subtitle = stringResource(R.string.more_favorites_subtitle),
                    onClick = onOpenFavorites
                )
            }

            // 偏好组(secondary 强调)—— 图标块用浅灰底 + 中性图标,
            // 与浏览组的彩色图标块拉开层次:内容入口彩色、设置项低调
            item { SectionHeader(stringResource(R.string.more_section_preferences)) }
            item {
                MenuRow(
                    title = stringResource(R.string.more_ai_service_title),
                    subtitle = stringResource(R.string.more_ai_service_subtitle),
                    onClick = onOpenAiService
                )
            }
            item {
                MenuRow(
                    title = stringResource(R.string.settings_title),
                    subtitle = stringResource(R.string.more_settings_subtitle),
                    onClick = onOpenSettings
                )
            }
            item {
                MenuRow(
                    title = stringResource(R.string.about_title),
                    subtitle = stringResource(R.string.more_about_subtitle),
                    onClick = onOpenAbout
                )
            }
        }
    }
}

/**
 * 目录菜单行 —— 报纸目录页语言(全 App 去图标改版):标题 + 副题 + 右「›」,
 * 无图标块、无行间线,靠留白分层;[trailing] 供行尾徽标(信息源页断供标注)。
 */
@Composable
internal fun MenuRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = AppText.caption,
                color = cs.onSurfaceVariant
            )
        }
        if (trailing != null) trailing()
        Spacer(Modifier.width(8.dp))
        // 「›」文字箭头:功能符号,菜单可点击暗示
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = cs.outlineVariant
        )
    }
}
