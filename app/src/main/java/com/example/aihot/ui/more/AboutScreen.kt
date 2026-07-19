package com.example.aihot.ui.more

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aihot.ui.components.AppCard
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.AppTopBarDefaults
import com.example.aihot.ui.components.SectionHeader
import com.example.aihot.ui.theme.AppText

/**
 * 关于页 —— App 名/版本/数据源说明/项目链接/开源依赖清单。
 *
 * 数据来源:AIHot 精选、HackerNews、GitHub Trending、HuggingFace Papers、stormzhang AI、
 * Product Hunt、The Rundown AI、LinuxDo 八源(原「AI HOT」与「AIHot 精选」同指
 * aihot.virxact.com,合并为一项避免冗余)。
 *
 * 链接统一走内置 WebView([onOpenUrl],计入浏览历史),不跳外部浏览器 ——
 * 与全 App openUrl 策略一致。
 *
 * 版本号取自 build.gradle.kts 的 versionName(运行时 PackageManager 读取,避免硬编码漂移)。
 *
 * 视觉:品牌头走 AppCard 灰白描边卡 + primaryContainer 圆形「AI」logo;内容行统一
 * 走 [InfoRow] 轻量行(标题/副标题弱化字号与颜色,与章节标题 [SectionHeader] 的
 * labelLarge 同档但靠字重 + 颜色建立层级,避免头重脚轻)。
 */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenUrl: (String, String) -> Unit) {
    val context = LocalContext.current
    // 版本号取自包信息(对齐 build.gradle.kts versionName),不再硬编码
    val versionName = remember {
        @Suppress("DEPRECATION")
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "关于",
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
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // 品牌头(全页唯一卡片,品牌渐变)
            item { BrandHeader(versionName = versionName) }

            // 数据来源 —— 轻量行(无图标,文字弱化)
            item { SectionHeader("数据来源") }
            dataSources.forEachIndexed { idx, src ->
                item {
                    InfoRow(
                        title = src.title,
                        subtitle = src.subtitle,
                        onClick = { onOpenUrl(src.url, src.title) },
                        showDivider = idx != dataSources.lastIndex
                    )
                }
            }

            // 项目
            item { SectionHeader("项目") }
            item {
                InfoRow(
                    title = "项目源码",
                    subtitle = "GitHub · burgessjp/AI-News-Hub",
                    onClick = { onOpenUrl("https://github.com/burgessjp/AI-News-Hub", "项目源码") },
                    showDivider = false
                )
            }

            // 开源依赖 —— license 用圆角描边 Badge
            item { SectionHeader("开源依赖") }
            deps.forEachIndexed { idx, (name, license) ->
                item {
                    InfoRow(
                        title = name,
                        trailing = { LicenseBadge(license) },
                        showDivider = idx != deps.lastIndex
                    )
                }
            }
        }
    }
}

/**
 * 品牌头卡片 —— primaryContainer 圆形 logo + 名称 + 版本号 + slogan。
 */
@Composable
private fun BrandHeader(versionName: String) {
    val cs = MaterialTheme.colorScheme
    AppCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "AI News Hub",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = cs.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "v$versionName",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
                    )
                }
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

/**
 * 轻量信息行 —— 关于页内容主体,弱化字号与颜色以平衡章节标题(labelLarge)。
 *
 * 视觉对齐全 App 「扁平行 + hairline 分隔线」语言,但不走 [com.example.aihot.ui.components.SettingsRow]
 * 的 titleMedium/onSurface(16sp SemiBold + 满色)—— 那会让内容行重于章节标题(labelLarge
 * 14sp Bold),形成「头重脚轻」。这里:
 *  - 标题 [AppText.body] 14sp + onSurfaceVariant(弱色,与章节标题同档但靠字重建立层级)
 *  - 副标题 [AppText.caption] 11sp + outline(更弱,纯辅助)
 *  - 可选 [trailing](与默认 chevron 二选一,license Badge 用)
 *  - hairline 分隔线左缩进 18dp 起平
 *  - [onClick] 为空时不挂 clickable(纯展示行,如开源依赖清单)
 *
 * @param showDivider 行底是否绘 hairline 分隔线(组内除末行外都传 true)
 * @param trailing 行尾自定义内容(有则不显示默认 chevron)
 */
@Composable
private fun InfoRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { base -> if (onClick != null) base.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onClick = onClick
                ) else base }
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = AppText.body,
                    color = cs.onSurfaceVariant
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = AppText.caption,
                        color = cs.outline
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            } else {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = cs.outlineVariant
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = cs.outlineVariant,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp)
            )
        }
    }
}

/**
 * 开源依赖 license Badge —— 圆角描边胶囊,统一 license 呈现。
 *
 * 不引品牌色:outlineVariant 描边 + onSurfaceVariant 文字,与卡片描边同一语言。
 */
@Composable
private fun LicenseBadge(license: String) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        border = BorderStroke(0.5.dp, cs.outlineVariant)
    ) {
        Text(
            text = license,
            style = AppText.caption,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** 数据来源项 —— 标题 + 中文说明 + 跳转 URL(无图标,文字弱化呈现)。 */
private class DataSource(
    val title: String,
    val subtitle: String,
    val url: String
)

/** 八源(原「AI HOT」与「AIHot 精选」同 URL 合并为一项)。 */
private val dataSources: List<DataSource> = listOf(
    DataSource("AIHot 精选", "第三方 AI 资讯精选", "https://aihot.virxact.com"),
    DataSource("HackerNews", "技术圈热门讨论", "https://news.ycombinator.com"),
    DataSource("GitHub Trending", "热门开源仓库", "https://github.com/trending"),
    DataSource("HuggingFace Papers", "热门 AI 论文榜单", "https://huggingface.co/papers/trending"),
    DataSource("stormzhang AI", "每日 AI 资讯聚合", "https://news.stormzhang.ai"),
    DataSource("Product Hunt", "每日新产品榜单", "https://www.producthunt.com"),
    DataSource("The Rundown AI", "AI 日更 newsletter", "https://www.therundown.ai"),
    DataSource("LinuxDo", "L 站热门话题", "https://linux.do")
)

private val deps = listOf(
    "Jetpack Compose & Material 3" to "Apache-2.0",
    "Kotlin Coroutines" to "Apache-2.0",
    "OkHttp" to "Apache-2.0",
    "jsoup" to "MIT",
    "Coil" to "Apache-2.0",
    "Room" to "Apache-2.0"
)
