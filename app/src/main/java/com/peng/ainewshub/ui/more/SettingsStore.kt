package com.peng.ainewshub.ui.more

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.peng.ainewshub.data.source.SourceMode
import com.peng.ainewshub.ui.i18n.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 显示偏好(主题模式 + 动态取色 + 字体族 + 字号档位 + 数据源模式 + 应用内语言)持久化;
 * 搜索历史([searchHistoryFlow],最近 10 条)同存于此文件,与显示偏好语义轻绑定。
 *
 * 此前 [themeMode] / [fontChoice] 仅靠 rememberSaveable 存内存,App 冷启动
 * 即丢失回到默认。这里用独立 DataStore 文件 `display_prefs`(与 AI 服务配置
 * `ai_prefs` 分开,语义清晰)持久化,枚举按 [name] 存取。
 *
 * [sourceMode] 控制 Hub 4 个稳定源(HackerNews / GitHub Trending / stormzhang AI /
 * HuggingFace Papers)从实时抓取还是 gitcode 归档取数;设置页入口已移除,恒定 [SourceMode.ARCHIVE]。
 *
 * [sourceOrderFlow] 持久化用户在「信息源」页拖拽自定义的 8 源顺序(默认
 * [DEFAULT_SOURCE_ORDER]),摘要 Tab 跟随该顺序;关于页固定默认顺序不跟随。
 *
 * 每日更新通知:开关存 `daily_notify` 键(进 [DisplayPrefs],同时控制通知与冷启动
 * 新数据弹窗);`last_notified_overview_at` 键存上次已感知批次的 generatedAt
 * (Worker 发通知与冷启动弹窗确认/忽略时写回,`last_notify_check_at` 键存自查链
 * 上次运行时刻 —— 后两者均为调度状态,不进 DisplayPrefs;check_at 供设置页显示
 * 「上次检查」,用于区分「链被系统后台限制拦住没跑」和「跑了但档内没新数据」)。
 */
private val Context.displayDataStore: DataStore<Preferences> by preferencesDataStore("display_prefs")

class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.displayDataStore

    data class DisplayPrefs(
        val themeMode: ThemeMode = ThemeMode.System,
        val dynamicColor: Boolean = false,
        val fontChoice: FontChoice = FontChoice.System,
        val fontScale: FontScale = FontScale.Standard,
        val sourceMode: SourceMode = SourceMode.ARCHIVE,
        val language: AppLanguage = AppLanguage.SYSTEM,
        val dailyNotify: Boolean = false
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
            // 设置页「数据源」入口已移除,默认恒定走归档:忽略 DataStore 旧值,强制 ARCHIVE。
            // (底层 LIVE 分支 / SourceMode 枚举 / fromStored 保留待恢复。)
            sourceMode = SourceMode.ARCHIVE,
            language = p[KEY_LANGUAGE]?.let { name -> runCatching { AppLanguage.valueOf(name) }.getOrNull() }
                ?: AppLanguage.SYSTEM,
            dailyNotify = p[KEY_DAILY_NOTIFY] ?: false
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

    suspend fun updateLanguage(lang: AppLanguage) {
        dataStore.edit { it[KEY_LANGUAGE] = lang.name }
    }

    // ===== 每日更新通知 =====

    /** 设置页「每日更新通知」开关;开关变化由调用方同步 WorkManager 调度(DailyNotifyScheduler.sync)。 */
    suspend fun updateDailyNotify(enabled: Boolean) {
        dataStore.edit { it[KEY_DAILY_NOTIFY] = enabled }
    }

    /**
     * 上次通知对应的 `latest_overview.generatedAt`(毫秒);0 = 从未通知。
     * 写方:DailyUpdateWorker(发通知时)与 MainActivity 冷启动新数据弹窗(确认/忽略时,
     * 与通知互补 —— 每天至多 1 条提醒,任一形式先触达即写回指纹)。不进 [DisplayPrefs]
     * (非用户偏好,是调度状态)。
     */
    suspend fun lastNotifiedOverviewAt(): Long = runCatching {
        dataStore.data.first()[KEY_LAST_NOTIFIED_OVERVIEW_AT]
    }.getOrNull() ?: 0L

    suspend fun setLastNotifiedOverviewAt(ms: Long) {
        dataStore.edit { it[KEY_LAST_NOTIFIED_OVERVIEW_AT] = ms }
    }

    /**
     * 自查链上次实际运行时刻(毫秒)流;0 = 从未运行(开关从未开过或链从未被系统放行)。
     * Worker 每次运行先记一笔再干活,设置页开关下显示「上次检查」即为可观测出口。
     */
    val lastNotifyCheckAtFlow: Flow<Long> = dataStore.data.map { p ->
        p[KEY_LAST_NOTIFY_CHECK_AT] ?: 0L
    }

    suspend fun setLastNotifyCheckAt(ms: Long) {
        dataStore.edit { it[KEY_LAST_NOTIFY_CHECK_AT] = ms }
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

    // ===== 信息源顺序 =====

    /**
     * 信息源顺序流 —— 用户在「信息源」页拖拽自定义的 8 源排列,默认 [DEFAULT_SOURCE_ORDER]。
     *
     * 存储格式:换行分隔的源 key 字符串(与搜索历史同模式,规避 stringListPreferencesKey)。
     * 读取容错:只保留 [DEFAULT_SOURCE_ORDER] 中已知 key(过滤历史脏数据/已下线源),
     * 再把缺失的 key 按默认顺序补到末尾 —— 保证旧用户升级 / 未来新增源时不丢条目、
     * 数据迁移无需写脚本。
     */
    val sourceOrderFlow: Flow<List<String>> = dataStore.data.map { p ->
        val stored = p[KEY_SOURCE_ORDER].orEmpty()
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val known = DEFAULT_SOURCE_ORDER.toSet()
        val ordered = stored.filter { it in known }
        // 去重(保留首次出现)+ 补全缺失 key 到末尾
        val seen = linkedSetOf<String>()
        ordered.forEach { seen.add(it) }
        DEFAULT_SOURCE_ORDER.forEach { if (it !in seen) seen.add(it) }
        seen.toList()
    }

    /** 持久化用户拖拽后的源顺序(8 源 key 的全排列)。 */
    suspend fun updateSourceOrder(order: List<String>) {
        dataStore.edit { p ->
            p[KEY_SOURCE_ORDER] = order.joinToString("\n")
        }
    }

    /**
     * 挂起式读取数据源模式 —— 供 ViewModel 在 init 协程里取首帧真实值
     * (替代原构造期 runBlocking 同步读:那是主线程磁盘 I/O,拖慢冷启动)。
     * 读取失败(理论上不会,DataStore 容错)回退 [SourceMode.LIVE]。
     */
    suspend fun currentSourceMode(): SourceMode = runCatching {
        prefsFlow.first().sourceMode
    }.getOrDefault(SourceMode.ARCHIVE)

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_FONT = stringPreferencesKey("font_choice")
        val KEY_FONT_SCALE = stringPreferencesKey("font_scale")
        val KEY_SOURCE_MODE = stringPreferencesKey("source_mode")
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_SEARCH_HISTORY = stringPreferencesKey("search_history")
        val KEY_SOURCE_ORDER = stringPreferencesKey("source_order")
        val KEY_DAILY_NOTIFY = booleanPreferencesKey("daily_notify")
        val KEY_LAST_NOTIFIED_OVERVIEW_AT = longPreferencesKey("last_notified_overview_at")
        val KEY_LAST_NOTIFY_CHECK_AT = longPreferencesKey("last_notify_check_at")
        const val MAX_SEARCH_HISTORY = 10
    }
}
