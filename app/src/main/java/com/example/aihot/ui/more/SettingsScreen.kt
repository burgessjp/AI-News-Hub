package com.example.aihot.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.aihot.data.CacheManager
import com.example.aihot.data.source.SourceMode
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.AppTopBarDefaults
import com.example.aihot.ui.components.SegmentedOptionRow
import com.example.aihot.ui.components.SectionHeader
import com.example.aihot.ui.components.SettingsRow

/**
 * 主题模式 —— 由 [com.example.aihot.AIHotApp] 持有,设置页通过回调修改。
 */
enum class ThemeMode(val label: String) {
    System("跟随系统"),
    Light("亮色"),
    Dark("暗色")
}

/**
 * 字体族 —— 同样提升到 [com.example.aihot.AIHotApp]。
 *
 * 仅用 Compose 内置 FontFamily,无需引入外部字体资源:
 *  - System: SansSerif(系统默认无衬线,与 Type.kt 一致)
 *  - Serif:  衬线体(阅读向)
 *  - Mono:   等宽体(代码/技术向)
 */
enum class FontChoice(val label: String, val fontFamily: FontFamily) {
    System("默认", FontFamily.SansSerif),
    Serif("衬线", FontFamily.Serif),
    Mono("等宽", FontFamily.Monospace)
}

/**
 * 字号档位 —— 整体缩放语义字号层 AppTextStyles(见 ui/theme/AppText.kt)。
 *
 * 只缩放 AppText 档位的 fontSize/lineHeight;MD3 typography 不动,
 * 避免 TopAppBar/Chip 等组件内部布局错位。
 */
enum class FontScale(val label: String, val scale: Float) {
    Compact("紧凑", 0.9f),
    Standard("标准", 1.0f),
    Large("大号", 1.15f)
}

/**
 * 设置页。
 *
 * 视觉与主列表页同构:章节条 + 扁平行;选择器为轨道式 [SegmentedOptionRow],
 * 行图标用彩色图标块([SettingsRow] 的 iconAccent,与「更多」页 IconTileRow 同语言)。
 *  - 外观:主题模式三选一(系统/亮/暗)+ 动态取色开关(Material You,Android 12+)
 *  - 字体:字体族三选一(默认/衬线/等宽)+ 字号三档(紧凑/标准/大号)
 *  - 数据源:Hub 4 源从实时抓取还是 gitcode 归档取数,横向二段式
 *  - 语言:占位项(当前仅简体中文)
 *  - 缓存:一键清理网页缓存/Cookie/图片缓存/浏览历史/搜索历史等可恢复数据
 *
 * AI 服务配置与用量统计已拆到独立二级页 [AiServiceScreen](「更多」→「AI 服务」入口)。
 * 数据源模式与主题/字体同存于 display_prefs([com.example.aihot.ui.more.SettingsStore])。
 */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onSelectTheme: (ThemeMode) -> Unit,
    dynamicColor: Boolean,
    onToggleDynamicColor: (Boolean) -> Unit,
    fontChoice: FontChoice,
    onSelectFont: (FontChoice) -> Unit,
    fontScale: FontScale,
    onSelectFontScale: (FontScale) -> Unit,
    sourceMode: SourceMode,
    onSelectSource: (SourceMode) -> Unit,
    cacheSizeBytes: Long,
    onClearCache: () -> Unit,
    onBack: () -> Unit
) {
    val themeOptions = remember { ThemeMode.entries.map { it.label } }
    val fontOptions = remember { FontChoice.entries.map { it.label } }
    val fontScaleOptions = remember { FontScale.entries.map { it.label } }
    val sourceOptions = remember { SourceMode.entries.map { it.label } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "设置",
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
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // 外观 section —— 主题三段式(轨道式)+ 动态取色开关
            item { SectionHeader("外观") }
            item {
                SegmentedOptionRow(
                    options = themeOptions,
                    selectedIndex = themeMode.ordinal,
                    onSelect = { idx -> onSelectTheme(ThemeMode.entries[idx]) },
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                )
            }
            item {
                // Material You 动态取色:壁纸派生色,覆盖品牌双色板;Android 12+ 才可用
                val dynamicSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                SettingsRow(
                    icon = Icons.Filled.Palette,
                    iconAccent = MaterialTheme.colorScheme.secondary,
                    title = "动态取色",
                    subtitle = if (dynamicSupported) "跟随壁纸生成配色,覆盖品牌色" else "需 Android 12 及以上",
                    showDivider = false,
                    trailing = {
                        Switch(
                            checked = dynamicColor && dynamicSupported,
                            enabled = dynamicSupported,
                            onCheckedChange = { onToggleDynamicColor(it) }
                        )
                    },
                    showChevron = false
                )
            }

            // 字体 section —— 字体族 + 字号两组轨道式选择器,各带小节标签
            item { SectionHeader("字体") }
            item {
                GroupLabel("字体族")
                SegmentedOptionRow(
                    options = fontOptions,
                    selectedIndex = fontChoice.ordinal,
                    onSelect = { idx -> onSelectFont(FontChoice.entries[idx]) },
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                )
            }
            item {
                GroupLabel("字号")
                SegmentedOptionRow(
                    options = fontScaleOptions,
                    selectedIndex = fontScale.ordinal,
                    onSelect = { idx -> onSelectFontScale(FontScale.entries[idx]) },
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                )
            }

            // 数据源 section —— Hub 4 个稳定源从实时抓取还是 gitcode 归档取数
            // 实时:直连第三方站点(默认,数据最新);归档:读 gitcode 历史快照(稳定不受反爬影响)
            // 切换后下拉刷新即用新源,无需重进页面(ViewModel 订阅 prefsFlow 动态选 repo)。
            item { SectionHeader("数据源") }
            item {
                SegmentedOptionRow(
                    options = sourceOptions,
                    selectedIndex = sourceMode.ordinal,
                    onSelect = { idx -> onSelectSource(SourceMode.entries[idx]) },
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                )
            }

            // 语言 section(占位)
            item { SectionHeader("语言") }
            item {
                SettingsRow(
                    icon = Icons.Filled.Language,
                    iconAccent = MaterialTheme.colorScheme.primary,
                    title = "简体中文",
                    showDivider = false,
                    trailing = {
                        Text(
                            text = "仅",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    showChevron = false
                )
            }

            // 数据清理 section —— 一键清理已加载的网页/图片/浏览历史/搜索历史等可恢复数据
            item { SectionHeader("数据清理") }
            item {
                CacheSection(cacheSizeBytes = cacheSizeBytes, onClearCache = onClearCache)
            }
        }
    }
}

/** 小节标签(如「字体族」「字号」),引导下方选择器。 */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, top = 6.dp)
    )
}

/**
 * 缓存清理区块。
 *
 * 副标题显示当前 cacheDir 占用([CacheManager.formatSize]);点击弹 AlertDialog 二次确认,
 * 确认后回调 [onClearCache](由调用方执行 [CacheManager.clear] 并刷新占用)。
 * 确认钮沿用「清空统计」的 error 红色范式。
 */
@Composable
private fun CacheSection(cacheSizeBytes: Long, onClearCache: () -> Unit) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    SettingsRow(
        icon = Icons.Filled.CleaningServices,
        iconAccent = MaterialTheme.colorScheme.primary,
        title = "清理浏览数据",
        subtitle = "当前占用 ${CacheManager.formatSize(cacheSizeBytes)}",
        showDivider = false,
        showChevron = false,
        onClick = { confirmClear = true }
    )
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清理浏览数据") },
            text = {
                Text("将清理已加载的网页、图片、浏览历史与搜索历史等可恢复数据,不影响你的设置和 AI 配置。")
            },
            confirmButton = {
                TextButton(onClick = {
                    onClearCache()
                    confirmClear = false
                }) { Text("清理", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

