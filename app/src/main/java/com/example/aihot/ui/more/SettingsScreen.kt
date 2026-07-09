package com.example.aihot.ui.more

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.SegmentedOption
import com.example.aihot.ui.components.SegmentedOptionRow
import com.example.aihot.ui.components.SettingsGroupHeader
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
 * 设置页。
 *
 * 视觉与主列表页同构:章节条 + 扁平行 / 横向三段选择器。
 *  - 外观:主题模式三选一(系统/亮/暗),横向三段式(图标 + 文字)
 *  - 字体:字体族三选一(默认/衬线/等宽),横向三段式
 *  - 语言:占位项(当前仅简体中文)
 */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onSelectTheme: (ThemeMode) -> Unit,
    fontChoice: FontChoice,
    onSelectFont: (FontChoice) -> Unit,
    onBack: () -> Unit
) {
    val themeOptions = remember {
        listOf(
            SegmentedOption(icon = Icons.Filled.BrightnessAuto, label = ThemeMode.System.label),
            SegmentedOption(icon = Icons.Filled.LightMode, label = ThemeMode.Light.label),
            SegmentedOption(icon = Icons.Filled.DarkMode, label = ThemeMode.Dark.label)
        )
    }
    val fontOptions = remember {
        listOf(
            SegmentedOption(icon = Icons.Filled.Title, label = FontChoice.System.label),
            SegmentedOption(icon = Icons.Filled.TextFields, label = FontChoice.Serif.label),
            SegmentedOption(icon = Icons.Filled.Code, label = FontChoice.Mono.label)
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = "设置",
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
            // 外观 section —— 横向三段式主题选择器
            item { SettingsGroupHeader("外观") }
            item {
                SegmentedOptionRow(
                    options = themeOptions,
                    selectedIndex = themeMode.ordinal,
                    onSelect = { idx -> onSelectTheme(ThemeMode.entries[idx]) }
                )
            }

            // 字体 section —— 横向三段式字体族选择器
            item { SettingsGroupHeader("字体") }
            item {
                SegmentedOptionRow(
                    options = fontOptions,
                    selectedIndex = fontChoice.ordinal,
                    onSelect = { idx -> onSelectFont(FontChoice.entries[idx]) }
                )
            }

            // 语言 section(占位)
            item { SettingsGroupHeader("语言") }
            item {
                SettingsRow(
                    icon = Icons.Filled.Language,
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
        }
    }
}
