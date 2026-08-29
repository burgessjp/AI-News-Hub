package com.peng.ainewshub.ui.more

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.theme.AppText

/**
 * 关于 · 开源依赖二级页 —— 从关于页「资源」组进入。
 *
 * 列出 App 依赖的开源组件([ossDeps]:名称 + license + 项目主页),行尾 license 走
 * 圆角描边 Badge([LicenseBadge]);点击行经内置 WebView 打开项目主页(走唯一入口
 * onOpenUrl,计入浏览历史)。
 *
 * 纯静态页无 ViewModel(同关于页先例);列表滚动状态由导航层经 [listState] 下传。
 */
@Composable
fun AboutOssScreen(
    onBack: () -> Unit,
    /** 打开依赖项目主页(url + 标题),走全局 openUrl 唯一入口。 */
    onOpenUrl: (String, String) -> Unit,
    listState: LazyListState
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.about_section_oss),
                subtitle = stringResource(R.string.about_oss_page_hint),
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
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(padding),
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            ossDeps.forEachIndexed { idx, dep ->
                item(key = dep.name) {
                    InfoRow(
                        title = dep.name,
                        trailing = { LicenseBadge(dep.license) },
                        onClick = { onOpenUrl(dep.url, dep.name) },
                        showDivider = idx != ossDeps.lastIndex
                    )
                }
            }
        }
    }
}

/** 开源依赖条目 —— 名称、license 标识、项目主页(点击行经内置 WebView 打开)。 */
internal data class OssDep(val name: String, val license: String, val url: String)

/** 开源依赖清单(与 build.gradle.kts 依赖对应;关于页入口行的数量副标题也读它)。 */
internal val ossDeps = listOf(
    OssDep("Jetpack Compose & Material 3", "Apache-2.0", "https://developer.android.com/jetpack/compose"),
    OssDep("Kotlin Coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    OssDep("OkHttp", "Apache-2.0", "https://square.github.io/okhttp/"),
    OssDep("Coil", "Apache-2.0", "https://coil-kt.org"),
    OssDep("Room", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/room"),
    OssDep("Reorderable", "Apache-2.0", "https://github.com/burnoutcrew/reorderable"),
    OssDep("Jetpack Glance", "Apache-2.0", "https://developer.android.com/develop/ui/compose/glance"),
    OssDep("AndroidX WebKit", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/webkit"),
    OssDep("AndroidX DataStore", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/datastore")
)

/**
 * 轻量信息行 —— 关于域内容行,弱化字号与颜色以平衡章节标题(labelLarge)。
 *
 * 视觉对齐全 App「扁平行 + hairline 分隔线」语言,但不走 [com.peng.ainewshub.ui.components.SettingsRow]
 * 的 titleMedium/onSurface(16sp SemiBold + 满色)—— 清单型内容行靠字重弱一档与
 * 入口行区分层级。本实现随开源依赖清单从关于主页迁入(原关于页 InfoRow):
 *  - 标题 [AppText.body] 14sp + onSurfaceVariant
 *  - 可选 [trailing](license Badge 等,有则不显示默认 chevron)
 *  - hairline 分隔线左缩进 18dp 起平
 *  - [onClick] 为空时不挂 clickable(纯展示行)
 *
 * @param showDivider 行底是否绘 hairline 分隔线(组内除末行外都传 true)
 * @param trailing 行尾自定义内容(与默认 chevron 二选一)
 */
@Composable
private fun InfoRow(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
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
            Text(
                text = title,
                style = AppText.body,
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
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
