package com.example.aihot.ui.more

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.aihot.data.source.SourceMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * 显示偏好(主题模式 + 字体族 + 数据源模式)持久化。
 *
 * 此前 [themeMode] / [fontChoice] 仅靠 rememberSaveable 存内存,App 冷启动
 * 即丢失回到默认。这里用独立 DataStore 文件 `display_prefs`(与翻译配置
 * `translation_prefs` 分开,语义清晰)持久化,枚举按 [name] 存取。
 *
 * [sourceMode] 控制 Hub 4 个稳定源(HackerNews / GitHub Trending / stormzhang AI /
 * HuggingFace Papers)从实时抓取还是 gitcode 归档取数,默认 [SourceMode.LIVE]。
 */
private val Context.displayDataStore: DataStore<Preferences> by preferencesDataStore("display_prefs")

class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.displayDataStore

    data class DisplayPrefs(
        val themeMode: ThemeMode = ThemeMode.System,
        val fontChoice: FontChoice = FontChoice.System,
        val sourceMode: SourceMode = SourceMode.LIVE
    )

    val prefsFlow: Flow<DisplayPrefs> = dataStore.data.map { p ->
        DisplayPrefs(
            themeMode = p[KEY_THEME]?.let { name -> runCatching { ThemeMode.valueOf(name) }.getOrNull() }
                ?: ThemeMode.System,
            fontChoice = p[KEY_FONT]?.let { name -> runCatching { FontChoice.valueOf(name) }.getOrNull() }
                ?: FontChoice.System,
            sourceMode = SourceMode.fromStored(p[KEY_SOURCE_MODE])
        )
    }

    suspend fun updateTheme(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun updateFont(choice: FontChoice) {
        dataStore.edit { it[KEY_FONT] = choice.name }
    }

    suspend fun updateSourceMode(mode: SourceMode) {
        dataStore.edit { it[KEY_SOURCE_MODE] = mode.name }
    }

    /**
     * 同步读取数据源模式 —— 供 ViewModel 在构造期(init 属性)非协程上下文取值。
     *
     * 用 [runBlocking] 阻塞读一次 DataStore 文件(DataStore 的单次读很快,仅打开一个
     * 小文件)。仅用于 ViewModel 构造期的冷启动取值,不要在热路径(如 Composable、
     * onClick)调用。读取失败(理论上不会,DataStore 容错)回退 [SourceMode.LIVE]。
     */
    fun currentSourceModeSync(): SourceMode = runCatching {
        runBlocking { prefsFlow.first().sourceMode }
    }.getOrDefault(SourceMode.LIVE)

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_FONT = stringPreferencesKey("font_choice")
        val KEY_SOURCE_MODE = stringPreferencesKey("source_mode")
    }
}
