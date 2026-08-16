package com.peng.ainewshub.ui.more

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.data.UpdateChecker
import com.peng.ainewshub.ui.components.AppCard
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.SectionHeader
import com.peng.ainewshub.ui.components.SettingsRow
import com.peng.ainewshub.ui.theme.AppText
import kotlinx.coroutines.launch

/**
 * 关于页 —— 居中品牌头 + 两组入口。
 *
 * 重构后的信息架构(长清单下沉到二级页,主页只留入口):
 *  - 品牌头([BrandHeader]):居中 logo / 名称 / 版本胶囊 / slogan / 版权
 *  - 「资源」组:数据来源 → [AboutSourcesScreen](8 源官网)、
 *    开源依赖 → [AboutOssScreen](清单 + license)
 *  - 「项目」组:检查更新 / 更新日志 / 项目源码
 *
 * 入口行复用 [SettingsRow](36dp 强调色图标块),与设置页同语言;数据源顺序与开源
 * 清单均不在本页展开(细节见两个二级页)。
 *
 * 链接统一走内置 WebView([onOpenUrl],计入浏览历史),不跳外部浏览器 ——
 * 与全 App openUrl 策略一致。
 *
 * 版本号取自 build.gradle.kts 的 versionName(运行时 PackageManager 读取,避免硬编码漂移)。
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
    onOpenChangelog: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenOss: () -> Unit
) {
    val context = LocalContext.current
    // 版本号取自包信息(对齐 build.gradle.kts versionName),不再硬编码
    val versionName = remember {
        @Suppress("DEPRECATION")
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }
    // 「项目源码」行标题在非 Composable 的 onClick 回调里也要用(onOpenUrl 记录标题),提前取出
    val projectSourceTitle = stringResource(R.string.about_project_source_title)

    // 检查更新:手动查 GitHub Releases 最新 tag 与本地 versionName 比较(见 UpdateChecker)。
    // 失败静默视为「已是最新」;命中新版本弹窗展示说明,「去下载」经内置 WebView 打开
    // Release 页(点 APK 资产链接走 DownloadManager 下载,与网页下载体验一致)。
    val scope = rememberCoroutineScope()
    var updateChecking by remember { mutableStateOf(false) }
    var updateUpToDate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    val updatePageTitle = stringResource(R.string.about_update_check)
    fun checkUpdate() {
        if (updateChecking) return
        updateChecking = true
        updateUpToDate = false
        scope.launch {
            val info = UpdateChecker.check(versionName ?: "1.0")
            updateChecking = false
            if (info != null) updateInfo = info else updateUpToDate = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.about_title),
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // 居中品牌头(全页唯一卡片)
            item { BrandHeader(versionName = versionName) }

            // 资源 —— 长清单入口下沉二级页,数量跟随数据自动同步
            item { SectionHeader(stringResource(R.string.about_section_resources)) }
            item {
                SettingsRow(
                    icon = Icons.Filled.TravelExplore,
                    iconAccent = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.about_section_data_sources),
                    subtitle = pluralStringResource(
                        R.plurals.more_sources_subtitle,
                        DEFAULT_SOURCE_ORDER.size,
                        DEFAULT_SOURCE_ORDER.size
                    ),
                    onClick = onOpenSources
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Extension,
                    iconAccent = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.about_section_oss),
                    subtitle = pluralStringResource(
                        R.plurals.about_oss_entry_subtitle,
                        ossDeps.size,
                        ossDeps.size
                    ),
                    showDivider = false,
                    onClick = onOpenOss
                )
            }

            // 项目 —— 检查更新(行尾状态字)/ 更新日志 / 项目源码
            item { SectionHeader(stringResource(R.string.about_section_project)) }
            item {
                SettingsRow(
                    icon = Icons.Filled.SystemUpdate,
                    iconAccent = MaterialTheme.colorScheme.secondary,
                    title = stringResource(R.string.about_update_check),
                    // 检查中/已是最新在行尾给状态字;空闲时显示默认 chevron
                    trailing = if (updateChecking || updateUpToDate) {
                        {
                            Text(
                                text = stringResource(
                                    if (updateChecking) R.string.about_update_checking
                                    else R.string.about_update_up_to_date
                                ),
                                style = AppText.caption,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    } else null,
                    onClick = { checkUpdate() }
                )
            }
            item {
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.Notes,
                    iconAccent = MaterialTheme.colorScheme.secondary,
                    title = stringResource(R.string.changelog_title),
                    onClick = onOpenChangelog
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Code,
                    iconAccent = MaterialTheme.colorScheme.secondary,
                    title = projectSourceTitle,
                    subtitle = "GitHub · burgessjp/AI-News-Hub",
                    showDivider = false,
                    onClick = { onOpenUrl("https://github.com/burgessjp/AI-News-Hub", projectSourceTitle) }
                )
            }
        }
    }

    // 发现新版本:版本号 + 更新说明(截断防超长 body 撑爆弹窗)。「去下载」经内置
    // WebView 打开 Release 页;「查看更新日志」跳应用内更新日志页;「忽略」仅关弹窗,
    // 下次手动检查仍会提示
    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { updateInfo = null },
            title = {
                Text(
                    text = stringResource(R.string.about_update_available_title, info.version),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                if (info.notes.isNotBlank()) {
                    Text(
                        text = info.notes,
                        style = AppText.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    updateInfo = null
                    onOpenUrl(info.releaseUrl, updatePageTitle)
                }) {
                    Text(stringResource(R.string.about_update_download))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        updateInfo = null
                        onOpenChangelog()
                    }) {
                        Text(stringResource(R.string.changelog_view))
                    }
                    TextButton(onClick = { updateInfo = null }) {
                        Text(stringResource(R.string.common_ignore))
                    }
                }
            }
        )
    }
}

/**
 * 居中品牌头卡片 —— logo / 名称 / 版本胶囊 / slogan / 版权,纵向居中排布。
 *
 * 版本胶囊:secondaryContainer 实底小胶囊,让版本号从纯文本升级为可扫读的标签。
 */
@Composable
private fun BrandHeader(versionName: String) {
    val cs = MaterialTheme.colorScheme
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = cs.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "AI",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = cs.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "AI News Hub",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = CircleShape,
                color = cs.secondaryContainer
            ) {
                Text(
                    text = "v$versionName",
                    style = AppText.caption,
                    color = cs.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.about_slogan),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.about_license),
                style = AppText.caption,
                color = cs.outline,
                textAlign = TextAlign.Center
            )
        }
    }
}
