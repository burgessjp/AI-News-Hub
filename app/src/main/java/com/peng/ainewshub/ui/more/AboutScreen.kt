package com.peng.ainewshub.ui.more

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peng.ainewshub.R
import com.peng.ainewshub.data.net.UpdateChecker
import com.peng.ainewshub.data.net.UpdateDownloadService
import com.peng.ainewshub.data.net.UpdateDownloadState
import com.peng.ainewshub.data.net.UpdateDownloader
import com.peng.ainewshub.ui.components.AppCard
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.SectionHeader
import com.peng.ainewshub.ui.components.SettingsRow
import com.peng.ainewshub.ui.theme.AppText
import kotlinx.coroutines.launch
import java.io.File
import com.peng.ainewshub.data.source.DEFAULT_SOURCE_ORDER

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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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

    // 检查更新:手动查 GitHub Releases 与本地 versionName 比较(见 UpdateChecker)。
    // 失败静默视为「已是最新」;命中新版本弹窗展示更新说明,下载交前台服务后台进行
    // (见 UpdateDownloadService),无 APK 资产或下载失败才兜底回 Release 网页。
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

    // 弹窗内直装状态机由前台服务 [UpdateDownloadService] 承载(本次改造核心):
    // 下载不再挂在页面组合作用域上 —— 关弹窗 / 离开关于页 / App 退后台均继续,
    // 进度常驻通知栏;此处只订阅进程级状态,重进关于页弹窗按状态恢复。
    // 进程重建丢失状态时回退「待下载」重来即可,APK 落缓存目录会先清旧再写,无残留问题
    val downloadState by UpdateDownloadService.state.collectAsStateWithLifecycle()

    fun startDownload(info: UpdateChecker.UpdateInfo) {
        // Release 未挂 APK 资产(异常情况):直接回网页兜底
        val url = info.downloadUrl ?: run {
            updateInfo = null
            onOpenUrl(info.releaseUrl, updatePageTitle)
            return
        }
        UpdateDownloadService.start(context, url, info.version)
    }

    fun install(apk: File) {
        when (UpdateDownloader.install(context, apk)) {
            // Android 8+ 首次需在系统设置授权「安装未知应用」:提示后跳设置页,
            // 授权返回后弹窗仍在(状态未清),再点「安装」即真正拉起安装器
            UpdateDownloader.InstallState.NeedPermission -> {
                Toast.makeText(context, R.string.about_update_install_hint, Toast.LENGTH_LONG).show()
                UpdateDownloader.requestInstallPermission(context)
            }
            UpdateDownloader.InstallState.Started -> updateInfo = null
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

    // 发现新版本弹窗:标题 → 「本次更新了什么」常显块 → 下载状态区(进度/失败/
    // 完成)→ 主操作 + 次操作。下载中可关弹窗(前台服务承载,后台继续);「忽略」
    // 只关弹窗不取消下载。底部半屏样式(对齐 OnboardingSheet):主操作为全宽按钮
    // (按状态切换),次操作收为下方居中文字按钮
    updateInfo?.let { info ->
        // 本弹窗版本的下载状态:服务里残留的是其它版本(重查出的新版本)时按空闲处理
        val dState = downloadState.forVersion(info.version)
        ModalBottomSheet(
            onDismissRequest = { updateInfo = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = stringResource(R.string.about_update_available_title, info.version),
                    style = AppText.titleSection,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(16.dp))
                // 本次更新了什么:任何下载状态都常显(不因进度/失败/完成被顶掉),
                // 跨版本更新时逐版本展示;高度封顶内滚防撑爆半屏
                if (info.notes.isNotEmpty()) {
                    UpdateNotesBlock(notes = info.notes)
                    Spacer(Modifier.height(16.dp))
                }
                when (dState) {
                    is UpdateDownloadState.Downloading -> {
                        val progress = dState.progress
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = stringResource(
                                    R.string.about_update_downloading_percent,
                                    (progress * 100).toInt()
                                ),
                                style = AppText.caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = stringResource(R.string.about_update_downloading),
                                style = AppText.caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                    is UpdateDownloadState.Failed -> Text(
                        text = stringResource(R.string.about_update_download_failed),
                        style = AppText.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    is UpdateDownloadState.Done -> Text(
                        text = stringResource(R.string.about_update_download_done),
                        style = AppText.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    UpdateDownloadState.Idle -> Unit
                }
                Spacer(Modifier.height(24.dp))
                // 主操作(按状态切换):下载中取消 / 失败重试 / 已下载安装 / 默认下载
                Button(
                    onClick = {
                        when (dState) {
                            is UpdateDownloadState.Downloading -> UpdateDownloadService.cancel(context)
                            is UpdateDownloadState.Failed -> startDownload(info)
                            is UpdateDownloadState.Done -> install(dState.apk)
                            UpdateDownloadState.Idle -> startDownload(info)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            when (dState) {
                                is UpdateDownloadState.Downloading -> R.string.common_cancel
                                is UpdateDownloadState.Failed -> R.string.common_retry
                                is UpdateDownloadState.Done -> R.string.about_update_install
                                UpdateDownloadState.Idle -> R.string.about_update_download
                            }
                        ),
                        style = AppText.body,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                // 次操作:失败时网页兜底 / 更新日志 / 忽略。FlowRow 居中排布,
                // 英文文案较长时自动换行不溢出
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    // 下载失败时额外给网页兜底入口(直链异常时仍可手动下载)
                    if (dState is UpdateDownloadState.Failed) {
                        TextButton(onClick = {
                            updateInfo = null
                            onOpenUrl(info.releaseUrl, updatePageTitle)
                        }) {
                            Text(stringResource(R.string.about_update_web_fallback))
                        }
                    }
                    TextButton(onClick = {
                        updateInfo = null
                        onOpenChangelog()
                    }) {
                        Text(stringResource(R.string.changelog_view))
                    }
                    // 忽略仅关弹窗:下载已在后台,不打断(取消走主按钮或通知栏)
                    TextButton(onClick = { updateInfo = null }) {
                        Text(stringResource(R.string.common_ignore))
                    }
                }
            }
        }
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

/** 把进程级下载状态挂到弹窗版本:非该版本的残留(重查出的新版本)视为空闲,不串台。 */
private fun UpdateDownloadState.forVersion(version: String): UpdateDownloadState = when (this) {
    is UpdateDownloadState.Idle -> this
    is UpdateDownloadState.Downloading -> if (this.version == version) this else UpdateDownloadState.Idle
    is UpdateDownloadState.Done -> if (this.version == version) this else UpdateDownloadState.Idle
    is UpdateDownloadState.Failed -> if (this.version == version) this else UpdateDownloadState.Idle
}

/**
 * 更新弹窗的「本次更新了什么」块 —— [UpdateChecker.UpdateNote] 列表渲染。
 *
 * 每版本 body 拼上 `## [version]` 合成头后复用 [parseChangelog] 解析(与 App 内
 * 更新日志页同格式:release.yml 从 CHANGELOG.md 提取版本节写入 Release body),
 * 结构化渲染分类 + `**加粗**` 条目;解析不出条目但 body 非空的历史 Release
 * (自动生成的 body)退纯文本截断展示,两者皆空不渲染该版本。
 */
@Composable
private fun UpdateNotesBlock(notes: List<UpdateChecker.UpdateNote>, modifier: Modifier = Modifier) {
    val parsed = remember(notes) {
        notes.map { note ->
            note to parseChangelog("## [${note.version}]\n${note.markdown}").firstOrNull()
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
    ) {
        parsed.forEachIndexed { index, (note, version) ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            if (version != null && version.entryCount > 0) {
                // 多版本(跨版本更新)时版本小标题区分归属;单版本时弹窗标题已含版本号,不重复
                if (parsed.size > 1) {
                    Text(
                        text = "v${note.version}",
                        style = AppText.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                ChangelogSections(version.sections)
            } else if (note.markdown.isNotBlank()) {
                // 历史 Release(GitHub 自动生成 body)结构化解析不出条目:轻量
                // Markdown 行渲染兜底(标题/列表/加粗/链接去符号),不裸显标记
                MarkdownishBody(note.markdown)
            }
        }
    }
}
