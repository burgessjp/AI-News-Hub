package com.example.aihot.ui.more

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 显示偏好(主题模式 + 字体族)持久化。
 *
 * 此前 [themeMode] / [fontChoice] 仅靠 rememberSaveable 存内存,App 冷启动
 * 即丢失回到默认。这里用独立 DataStore 文件 `display_prefs`(与翻译配置
 * `translation_prefs` 分开,语义清晰)持久化,枚举按 [name] 存取。
 */
private val Context.displayDataStore: DataStore<Preferences> by preferencesDataStore("display_prefs")

class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.displayDataStore

    data class DisplayPrefs(
        val themeMode: ThemeMode = ThemeMode.System,
        val fontChoice: FontChoice = FontChoice.System
    )

    val prefsFlow: Flow<DisplayPrefs> = dataStore.data.map { p ->
        DisplayPrefs(
            themeMode = p[KEY_THEME]?.let { name -> runCatching { ThemeMode.valueOf(name) }.getOrNull() }
                ?: ThemeMode.System,
            fontChoice = p[KEY_FONT]?.let { name -> runCatching { FontChoice.valueOf(name) }.getOrNull() }
                ?: FontChoice.System
        )
    }

    suspend fun updateTheme(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun updateFont(choice: FontChoice) {
        dataStore.edit { it[KEY_FONT] = choice.name }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_FONT = stringPreferencesKey("font_choice")
    }
}
