package com.peng.ainewshub.ui.i18n

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import com.peng.ainewshub.data.prefs.AppLanguage
import com.peng.ainewshub.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * 应用内语言切换单点。
 *
 * 机制(minSdk 24 无 AppCompat,统一一条路径):
 *  - Activity(`MainActivity` / `TranslateSelectionActivity`)override `attachBaseContext`
 *    调 [wrap],非 SYSTEM 时用 `createConfigurationContext` 注入目标 Locale;
 *  - SYSTEM 原样返回 —— 跟随系统语言,含 Android 13+ 系统 per-app locale
 *    (manifest `android:localeConfig` 已声明 zh-CN / en);
 *  - 非 Activity 场景(VM 取词、Glance 小组件)同样经 [wrap] 拿局部化 context 再 getString。
 *
 * 语言选择缓存在内存([cached]),冷启动首次 [current] 调用才阻塞读一次 DataStore
 * (attachBaseContext 是进程最早点,仅一次;后续全走缓存)。
 *
 * 已知取舍:语言切换经 `recreate()` 生效,ViewModel 存活 —— 切换瞬间已处于
 * Error/翻译错误态的旧语言文案保持原样,重试/刷新即更新(见 AGENTS.md)。
 */
object AppLocale {

    @Volatile
    private var cached: AppLanguage? = null

    /** 当前语言:命中内存缓存直接返回;冷启动首次调用阻塞读一次 DataStore 填充缓存。 */
    fun current(context: Context): AppLanguage =
        cached ?: runBlocking {
            runCatching { SettingsStore(context.applicationContext).prefsFlow.first().language }
                .getOrDefault(AppLanguage.SYSTEM)
        }.also { cached = it }

    /**
     * 按当前语言包裹 context;SYSTEM 原样返回。
     * 同时 `Locale.setDefault`,让 `SimpleDateFormat(Locale.getDefault())` 等跟随。
     */
    fun wrap(base: Context): Context {
        val locale = when (current(base)) {
            AppLanguage.SYSTEM -> return base
            AppLanguage.ZH_CN -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.EN -> Locale.ENGLISH
        }
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /** 设置页选择语言:持久化 + 更新缓存 + 重建 Activity 生效。 */
    suspend fun select(activity: Activity, store: SettingsStore, lang: AppLanguage) {
        store.updateLanguage(lang)
        cached = lang
        activity.recreate()
    }
}

/** 取局部化 context 的便捷扩展(VM / 小组件里 `context.localized().getString(...)`)。 */
fun Context.localized(): Context = AppLocale.wrap(this)
