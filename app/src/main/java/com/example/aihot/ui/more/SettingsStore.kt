package com.example.aihot.ui.more

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.aihot.data.source.SourceMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * 显示偏好(主题模式 + 动态取色 + 字体族 + 字号档位 + 数据源模式)持久化;
 * 搜索历史([searchHistoryFlow],最近 10 条)同存于此文件,与显示偏好语义轻绑定。
 *
 * 此前 [themeMode] / [fontChoice] 仅靠 rememberSaveable 存内存,App 冷启动
 * 即丢失回到默认。这里用独立 DataStore 文件 `display_prefs`(与 AI 服务配置
 * `ai_prefs` 分开,语义清晰)持久化,枚举按 [name] 存取。
 *
 * [sourceMode] 控制 Hub 4 个稳定源(HackerNews / GitHub Trending / stormzhang AI /
 * HuggingFace Papers)从实时抓取还是 gitcode 归档取数,默认 [SourceMode.LIVE]。
 */
private val Context.displayDataStore: DataStore<Preferences> by preferencesDataStore("display_prefs")

class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.displayDataStore

    data class DisplayPrefs(
        val themeMode: ThemeMode = ThemeMode.System,
        val dynamicColor: Boolean = false,
        val fontChoice: FontChoice = FontChoice.System,
        val fontScale: FontScale = FontScale.Standard,
        val sourceMode: SourceMode = SourceMode.LIVE
    )

    val prefsFlow: Flow<DisplayPrefs> = dataStore.data.map { p ->
        DisplayPrefs(
            themeMode = p[KEY_THEME]?.let { name -> runCatching { ThemeMode.valueOf(name) }.getOrNull() }
                ?: ThemeMode.System,
            dynamicColor = p[KEY_DYNAMIC_COLOR] ?: false,
            fontChoice = p[KEY_FONT]?.let { name -> runCatching { FontChoice.valueOf(name) }.getOrNull() }
                ?: FontChoice.System,
            fontScale = p[KEY_FONT_SCALE]?.let { name -> runCatching { FontScale.valueOf(name) }.getOrNull() }
                ?: FontScale.Standard,
            sourceMode = SourceMode.fromStored(p[KEY_SOURCE_MODE])
        )
    }

    suspend fun updateTheme(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun updateDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    suspend fun updateFont(choice: FontChoice) {
        dataStore.edit { it[KEY_FONT] = choice.name }
    }

    suspend fun updateFontScale(scale: FontScale) {
        dataStore.edit { it[KEY_FONT_SCALE] = scale.name }
    }

    suspend fun updateSourceMode(mode: SourceMode) {
        dataStore.edit { it[KEY_SOURCE_MODE] = mode.name }
    }

    // ===== 搜索历史 =====

    /**
     * 搜索历史流 —— 最新在前,最多 [MAX_SEARCH_HISTORY] 条。
     *
     * 存储格式:换行分隔的纯字符串(搜索框为单行输入,词条不可能含换行),
     * 读取时 trim + 过滤空串,对历史脏数据容错。
     */
    val searchHistoryFlow: Flow<List<String>> = dataStore.data.map { p ->
        p[KEY_SEARCH_HISTORY].orEmpty()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_SEARCH_HISTORY)
    }

    /**
     * 记录一条搜索历史:去首尾空白、空串不存、大小写敏感去重(已存在则移到最前)、
     * 只保留最近 [MAX_SEARCH_HISTORY] 条。
     */
    suspend fun addSearchHistory(term: String) {
        val t = term.trim()
        if (t.isEmpty()) return
        dataStore.edit { p ->
            val old = p[KEY_SEARCH_HISTORY].orEmpty()
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val new = (listOf(t) + old.filter { it != t }).take(MAX_SEARCH_HISTORY)
            p[KEY_SEARCH_HISTORY] = new.joinToString("\n")
        }
    }

    /** 清空搜索历史(条目少,调用方直接清空,无需二次确认)。 */
    suspend fun clearSearchHistory() {
        dataStore.edit { it.remove(KEY_SEARCH_HISTORY) }
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
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_FONT = stringPreferencesKey("font_choice")
        val KEY_FONT_SCALE = stringPreferencesKey("font_scale")
        val KEY_SOURCE_MODE = stringPreferencesKey("source_mode")
        val KEY_SEARCH_HISTORY = stringPreferencesKey("search_history")
        const val MAX_SEARCH_HISTORY = 10
    }
}
