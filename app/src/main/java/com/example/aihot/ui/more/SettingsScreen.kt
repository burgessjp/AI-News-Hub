package com.example.aihot.ui.more

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aihot.data.TranslationConfig
import com.example.aihot.data.TranslationConfigStore
import com.example.aihot.data.source.SourceMode
import com.example.aihot.ui.components.AppTopBar
import com.example.aihot.ui.components.AppTopBarDefaults
import com.example.aihot.ui.components.SegmentedOption
import com.example.aihot.ui.components.SegmentedOptionRow
import com.example.aihot.ui.components.SettingsGroupHeader
import com.example.aihot.ui.components.SettingsRow
import kotlinx.coroutines.launch

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
 *  - 数据源:Hub 4 源从实时抓取还是 gitcode 归档取数,横向二段式
 *  - 语言:占位项(当前仅简体中文)
 *  - 翻译:HackerNews 标题/评论翻译开关 + 用户自填的 LLM API 配置
 *
 * 翻译配置通过 [TranslationConfigStore] 持久化(DataStore),关 App 后保留。
 * 数据源模式与主题/字体同存于 display_prefs([com.example.aihot.ui.more.SettingsStore])。
 */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onSelectTheme: (ThemeMode) -> Unit,
    fontChoice: FontChoice,
    onSelectFont: (FontChoice) -> Unit,
    sourceMode: SourceMode,
    onSelectSource: (SourceMode) -> Unit,
    configStore: TranslationConfigStore,
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
    val sourceOptions = remember {
        listOf(
            SegmentedOption(icon = Icons.Filled.Sync, label = SourceMode.LIVE.label),
            SegmentedOption(icon = Icons.Filled.CloudDownload, label = SourceMode.ARCHIVE.label)
        )
    }

    val config by configStore.configFlow.collectAsStateWithLifecycle(
        initialValue = TranslationConfig()
    )

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

            // 数据源 section —— Hub 4 个稳定源从实时抓取还是 gitcode 归档取数
            // 实时:直连第三方站点(默认,数据最新);归档:读 gitcode 历史快照(稳定不受反爬影响)
            // 切换后需重进对应页面生效(ViewModel 是 keyed 单例)。
            item { SettingsGroupHeader("数据源") }
            item {
                SegmentedOptionRow(
                    options = sourceOptions,
                    selectedIndex = sourceMode.ordinal,
                    onSelect = { idx -> onSelectSource(SourceMode.entries[idx]) }
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

            // 翻译 section —— 开关 + 三项 LLM API 配置
            item { SettingsGroupHeader("翻译") }
            item {
                TranslationSection(config = config, configStore = configStore)
            }
        }
    }
}

/**
 * 翻译配置区块:总开关 + API 地址 / API Key / 模型 三项(点按弹输入对话框)。
 *
 * - 开关关时三项配置仍可填写(填好后再打开开关即可用);
 * - API Key 对话框默认密码态输入,带显隐切换(避免肩窥)。
 */
@Composable
private fun TranslationSection(
    config: TranslationConfig,
    configStore: TranslationConfigStore
) {
    val scope = rememberCoroutineScope()
    // 当前正在编辑的字段:null=未弹窗;非空=对应字段对话框打开
    var editingField by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsRow(
        icon = Icons.Filled.Translate,
        title = "启用翻译",
        subtitle = "翻译 HackerNews 的标题与评论",
        showDivider = true,
        trailing = {
            Switch(
                checked = config.enabled,
                onCheckedChange = { enabled ->
                    scope.launch { configStore.update(config.copy(enabled = enabled)) }
                }
            )
        },
        showChevron = false
    )

    SettingsRow(
        icon = Icons.Filled.Language,
        title = "API 地址",
        subtitle = config.baseUrl.ifBlank { "未设置" },
        onClick = { editingField = "base" }
    )
    SettingsRow(
        icon = Icons.Filled.Key,
        title = "API Key",
        subtitle = if (config.apiKey.isBlank()) "未设置" else "已设置",
        onClick = { editingField = "key" }
    )
    SettingsRow(
        icon = Icons.Filled.Memory,
        title = "模型",
        subtitle = config.model.ifBlank { "未设置" },
        showDivider = false,
        onClick = { editingField = "model" }
    )

    editingField?.let { field ->
        val (label, initial, isSecret) = when (field) {
            "base" -> Triple("API 地址", config.baseUrl, false)
            "key" -> Triple("API Key", config.apiKey, true)
            else -> Triple("模型", config.model, false)
        }
        EditDialog(
            title = label,
            initial = initial,
            isSecret = isSecret,
            onDismiss = { editingField = null },
            onConfirm = { newValue ->
                val updated = when (field) {
                    "base" -> config.copy(baseUrl = newValue)
                    "key" -> config.copy(apiKey = newValue)
                    else -> config.copy(model = newValue)
                }
                scope.launch { configStore.update(updated) }
                editingField = null
            }
        )
    }
}

/** 单行文本输入对话框。保存时回写 [TranslationConfigStore]。 */
@Composable
private fun EditDialog(
    title: String,
    initial: String,
    isSecret: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var visible by rememberSaveable { mutableStateOf(!isSecret) }
    // 每次重新打开时把内容重置为当前已保存值(rememberSaveable 会跨重组保留,
    // 故用 LaunchedEffect(title, initial) 在初值变化时同步)
    LaunchedEffect(initial) { text = initial }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                visualTransformation = if (isSecret && !visible) PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
                trailingIcon = if (isSecret) {
                    {
                        TextButton(onClick = { visible = !visible }) {
                            Text(if (visible) "隐藏" else "显示")
                        }
                    }
                } else null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isSecret) KeyboardType.Password else KeyboardType.Uri
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
