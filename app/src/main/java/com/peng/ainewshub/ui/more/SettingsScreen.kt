package com.peng.ainewshub.ui.more

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.peng.ainewshub.R
import com.peng.ainewshub.data.CacheManager
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.SegmentedOptionRow
import com.peng.ainewshub.ui.components.SectionHeader
import com.peng.ainewshub.ui.components.SettingsRow
import com.peng.ainewshub.ui.i18n.AppLanguage

/**
 * 主题模式 —— 由 [com.peng.ainewshub.AiNewsHubApp] 持有,设置页通过回调修改。
 */
enum class ThemeMode(@StringRes val labelRes: Int) {
    System(R.string.settings_theme_system),
    Light(R.string.settings_theme_light),
    Dark(R.string.settings_theme_dark)
}

/**
 * 字体族 —— 同样提升到 [com.peng.ainewshub.AiNewsHubApp]。
 *
 * 仅用 Compose 内置 FontFamily,无需引入外部字体资源:
 *  - System: 系统默认字体
 *  - Serif:  衬线体(阅读向)
 *  - Mono:   等宽体(代码/技术向)
 */
enum class FontChoice(@StringRes val labelRes: Int, val fontFamily: FontFamily) {
    System(R.string.settings_font_default, FontFamily.Default),
    Serif(R.string.settings_font_serif, FontFamily.Serif),
    Mono(R.string.settings_font_mono, FontFamily.Monospace)
}

/**
 * 字号档位 —— 整体缩放语义字号层 AppTextStyles(见 ui/theme/AppText.kt)。
 *
 * 只缩放 AppText 档位的 fontSize/lineHeight;MD3 typography 不动,
 * 避免 TopAppBar/Chip 等组件内部布局错位。
 */
enum class FontScale(@StringRes val labelRes: Int, val scale: Float) {
    Compact(R.string.settings_font_scale_compact, 0.9f),
    Standard(R.string.settings_font_scale_standard, 1.0f),
    Large(R.string.settings_font_scale_large, 1.15f)
}

/**
 * 设置页。
 *
 * 视觉与主列表页同构:章节条 + 扁平行;选择器为轨道式 [SegmentedOptionRow],
 * 行图标用彩色图标块([SettingsRow] 的 iconAccent,与「更多」页 IconTileRow 同语言)。
 *  - 外观:主题模式三选一(系统/亮/暗)+ 动态取色开关(Material You,Android 12+)
 *  - 字体:字体族三选一(默认/衬线/等宽)+ 字号三档(紧凑/标准/大号)
 *  - 数据源:Hub 4 源从实时抓取还是 gitcode 归档取数,横向二段式
 *  - 语言:跟随系统 / 简体中文 / English,切换后 Activity 重建生效(见 ui/i18n/AppLocale)
 *  - 通知:每日更新通知开关(WorkManager 本地调度,API 33+ 打开时请求运行时权限)
 *  - 缓存:一键清理网页缓存/Cookie/图片缓存/浏览历史/搜索历史等可恢复数据
 *
 * AI 服务配置与用量统计已拆到独立二级页 [AiServiceScreen](「更多」→「AI 服务」入口)。
 * 数据源模式与主题/字体同存于 display_prefs([com.peng.ainewshub.ui.more.SettingsStore])。
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
    language: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    dailyNotify: Boolean,
    onToggleDailyNotify: (Boolean) -> Unit,
    cacheSizeBytes: Long,
    onClearCache: () -> Unit,
    onBack: () -> Unit
) {
    val themeOptions = ThemeMode.entries.map { stringResource(it.labelRes) }
    val fontOptions = FontChoice.entries.map { stringResource(it.labelRes) }
    val fontScaleOptions = FontScale.entries.map { stringResource(it.labelRes) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.settings_title),
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
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // 外观 section —— 主题三段式(轨道式)+ 动态取色开关
            item { SectionHeader(stringResource(R.string.settings_section_appearance)) }
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
                    title = stringResource(R.string.settings_dynamic_color),
                    subtitle = if (dynamicSupported) stringResource(R.string.settings_dynamic_color_subtitle) else stringResource(R.string.settings_dynamic_color_unsupported),
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
            item { SectionHeader(stringResource(R.string.settings_section_font)) }
            item {
                GroupLabel(stringResource(R.string.settings_font_family))
                SegmentedOptionRow(
                    options = fontOptions,
                    selectedIndex = fontChoice.ordinal,
                    onSelect = { idx -> onSelectFont(FontChoice.entries[idx]) },
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                )
            }
            item {
                GroupLabel(stringResource(R.string.settings_font_scale))
                SegmentedOptionRow(
                    options = fontScaleOptions,
                    selectedIndex = fontScale.ordinal,
                    onSelect = { idx -> onSelectFontScale(FontScale.entries[idx]) },
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                )
            }

            // 语言 section —— 跟随系统 / 简体中文 / English,切换后 Activity 重建生效
            item { SectionHeader(stringResource(R.string.settings_language)) }
            item {
                val languageOptions = AppLanguage.entries.map {
                    when (it) {
                        AppLanguage.SYSTEM -> stringResource(R.string.language_follow_system)
                        AppLanguage.ZH_CN -> stringResource(R.string.language_zh)
                        AppLanguage.EN -> stringResource(R.string.language_en)
                    }
                }
                SegmentedOptionRow(
                    options = languageOptions,
                    selectedIndex = language.ordinal,
                    onSelect = { idx -> onSelectLanguage(AppLanguage.entries[idx]) },
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                )
            }

            // 通知 section —— 每日更新通知开关(WorkManager 本地调度;API 33+ 需运行时权限)
            item { SectionHeader(stringResource(R.string.settings_section_notify)) }
            item {
                DailyNotifyRow(dailyNotify = dailyNotify, onToggle = onToggleDailyNotify)
            }

            // 数据清理 section —— 一键清理已加载的网页/图片/浏览历史/搜索历史等可恢复数据
            item { SectionHeader(stringResource(R.string.settings_section_data_cleanup)) }
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
 * 每日更新通知开关行。
 *
 * Android 13+(TIRAMISU)通知需运行时权限:打开开关时请求,允许才生效;
 * 拒绝(含「不再询问」)Toast 引导去系统设置,开关保持关。已授权或 API < 33 直接生效。
 * 开关状态持久化与 WorkManager 调度同步由调用方(onToggle 回调)完成。
 */
@Composable
private fun DailyNotifyRow(dailyNotify: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onToggle(true)
        } else {
            Toast.makeText(context, R.string.settings_daily_notify_permission_denied, Toast.LENGTH_LONG).show()
        }
    }
    SettingsRow(
        icon = Icons.Filled.Notifications,
        iconAccent = MaterialTheme.colorScheme.tertiary,
        title = stringResource(R.string.settings_daily_notify),
        subtitle = stringResource(R.string.settings_daily_notify_subtitle),
        showDivider = false,
        trailing = {
            Switch(
                checked = dailyNotify,
                onCheckedChange = { enabled ->
                    val needsPermission = enabled &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    if (needsPermission) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onToggle(enabled)
                    }
                }
            )
        },
        showChevron = false
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
        title = stringResource(R.string.settings_clear_data_title),
        subtitle = stringResource(R.string.settings_clear_data_subtitle, CacheManager.formatSize(cacheSizeBytes)),
        showDivider = false,
        showChevron = false,
        onClick = { confirmClear = true }
    )
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_clear_data_title)) },
            text = {
                Text(stringResource(R.string.settings_clear_data_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    onClearCache()
                    confirmClear = false
                }) { Text(stringResource(R.string.settings_clear_action), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

