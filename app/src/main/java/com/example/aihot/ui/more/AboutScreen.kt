package com.example.aihot.ui.more

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aihot.ui.components.AppCard
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.AppTopBarDefaults
import com.example.aihot.ui.components.SettingsGroupHeader
import com.example.aihot.ui.components.SettingsRow

/**
 * 关于页 —— App 名/版本/数据源说明/项目链接/开源依赖清单。
 *
 * 数据来源:AI HOT、HackerNews、GitHub Trending、HuggingFace Papers、stormzhang AI、LinuxDo 六源。
 *
 * 版本号取自 build.gradle.kts 的 versionName(运行时 PackageManager 读取,避免硬编码漂移)。
 *
 * 视觉与主列表页同构:品牌头卡片 + 章节条 + 扁平行 + hairline 分隔线。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
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
            // 品牌头(全页唯一卡片)
            item { BrandHeader(versionName = versionName) }

            // 数据来源
            item { SettingsGroupHeader("数据来源") }
            dataSources.forEachIndexed { idx, (name, url) ->
                item {
                    SettingsRow(
                        title = name,
                        subtitle = url,
                        onClick = { uriHandler.openUri(url) },
                        showDivider = idx != dataSources.lastIndex
                    )
                }
            }

            // 项目
            item { SettingsGroupHeader("项目") }
            item {
                SettingsRow(
                    title = "项目源码",
                    subtitle = "GitHub · burgessjp/AI-News-Hub",
                    onClick = { uriHandler.openUri("https://github.com/burgessjp/AI-News-Hub") },
                    showDivider = false
                )
            }

            // 开源依赖
            item { SettingsGroupHeader("开源依赖") }
            deps.forEachIndexed { idx, (name, license) ->
                item {
                    SettingsRow(
                        title = name,
                        showDivider = idx != deps.lastIndex,
                        trailing = {
                            Text(
                                text = license,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        showChevron = false
                    )
                }
            }
        }
    }
}

/**
 * 品牌头卡片 —— cyan 圆形 logo + 名称 + 版本号 + slogan。
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

private val dataSources = listOf(
    "AI HOT" to "https://aihot.virxact.com",
    "HackerNews" to "https://news.ycombinator.com",
    "GitHub Trending" to "https://github.com/trending",
    "HuggingFace Papers" to "https://huggingface.co/papers/trending",
    "stormzhang AI" to "https://news.stormzhang.ai",
    "LinuxDo" to "https://linux.do"
)

private val deps = listOf(
    "Jetpack Compose & Material 3" to "Apache-2.0",
    "Kotlin Coroutines" to "Apache-2.0",
    "OkHttp" to "Apache-2.0",
    "jsoup" to "MIT",
    "Coil" to "Apache-2.0",
    "Room" to "Apache-2.0"
)
