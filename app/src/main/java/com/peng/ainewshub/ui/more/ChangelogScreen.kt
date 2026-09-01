package com.peng.ainewshub.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.HairlineDivider
import com.peng.ainewshub.ui.components.SectionHeader
import com.peng.ainewshub.ui.theme.AppText

/**
 * 更新日志页 —— 展示仓库根 CHANGELOG.md 各版本的新增 / 修复 / 改进。
 *
 * 数据链路：CHANGELOG.md 构建时经 syncChangelogAssets（app/build.gradle.kts）拷入
 * assets → [parseChangelog] 解析为版本列表。纯静态页无 ViewModel（同关于页先例），
 * 文件仅数 KB，remember 内同步读取解析即可。列表滚动状态由导航层经 [listState]
 * 下传（AnimatedContent 换页会销毁屏内 remember，见 docs/agents/navigation.md）。
 *
 * i18n：更新日志正文属内容而非 UI 文案，恒中文随 CHANGELOG.md；仅页面标题、
 * 分类标签（新增/修复/改进等经映射表）与「当前」徽章为双语。
 */
@Composable
fun ChangelogScreen(
    onBack: () -> Unit,
    listState: LazyListState
) {
    val context = LocalContext.current
    // 版本号取自包信息（对齐关于页），与 CHANGELOG 节名比对判定「当前」徽章
    val versionName = remember {
        @Suppress("DEPRECATION")
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }
    val versions = remember {
        runCatching {
            context.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
        }.getOrNull().orEmpty().let(::parseChangelog)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.changelog_title),
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        if (versions.isEmpty()) {
            // assets 缺失 / 解析为空（理论上有构建接线兜底，仅防御性空态）
            EmptyState(
                title = stringResource(R.string.changelog_empty),
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            versions.forEachIndexed { index, version ->
                item(key = version.version) {
                    VersionBlock(
                        version = version,
                        isCurrent = version.version == versionName
                    )
                    // 版本块之间 hairline 分节；末块不留
                    if (index != versions.lastIndex) {
                        HairlineDivider(startIndent = 18.dp)
                    }
                }
            }
        }
    }
}

/**
 * 单个版本块：章节条（版本号 + 右侧日期/「当前」徽章）→ 分类小节（共享渲染
 * [ChangelogSections]，见 ChangelogBlocks.kt）。
 */
@Composable
private fun VersionBlock(version: ChangelogVersion, isCurrent: Boolean) {
    SectionHeader(
        title = "v${version.version}",
        accent = MaterialTheme.colorScheme.primary,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isCurrent) CurrentBadge()
                if (version.date != null) {
                    Text(
                        text = version.date,
                        style = AppText.caption,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    )
    ChangelogSections(version.sections, Modifier.padding(horizontal = 18.dp))
}

/** 「当前」徽章 —— primaryContainer 实底胶囊，标记当前安装版本。 */
@Composable
private fun CurrentBadge() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            text = stringResource(R.string.changelog_current_badge),
            style = AppText.caption,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
