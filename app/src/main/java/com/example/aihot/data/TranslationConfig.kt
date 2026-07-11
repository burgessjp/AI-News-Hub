package com.example.aihot.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 翻译功能配置。
 *
 * - enabled: 总开关。关闭时 UI 不渲染「译」按钮,避免打扰不需要翻译的用户。
 * - baseUrl / apiKey / model: 用户自填的 OpenAI 兼容服务配置。
 *   Key 由用户自有、用户自负,存 App 私有目录,不进 APK、不进日志。
 *
 * baseUrl 约定填到根(如 `https://api.deepseek.com`),请求时拼接 `/v1/chat/completions`。
 */
data class TranslationConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = ""
) {
    /** 三项配置齐全才算「就绪」,缺任一项时点「译」按钮引导用户去设置。 */
    val isReady: Boolean
        get() = enabled && baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/** 顶层扩展,保证整个进程对 "translation_prefs" 只有一个 DataStore 实例(DataStore 必须单例)。 */
private val Context.translationDataStore: DataStore<Preferences> by preferencesDataStore("translation_prefs")

/**
 * 翻译配置持久化。基于 DataStore Preferences。
 *
 * 异步 API:UI 层用 `collectAsStateWithLifecycle` 订阅 [configFlow],写入用 [update]。
 * 默认值:开关关、三项空串。
 */
class TranslationConfigStore(context: Context) {

    private val dataStore = context.applicationContext.translationDataStore

    val configFlow: Flow<TranslationConfig> = dataStore.data.map { prefs ->
        TranslationConfig(
            enabled = prefs[KEY_ENABLED] ?: false,
            baseUrl = prefs[KEY_BASE_URL] ?: "",
            apiKey = prefs[KEY_API_KEY] ?: "",
            model = prefs[KEY_MODEL] ?: ""
        )
    }

    suspend fun update(config: TranslationConfig) {
        dataStore.edit { prefs ->
            prefs[KEY_ENABLED] = config.enabled
            prefs[KEY_BASE_URL] = config.baseUrl.trim()
            prefs[KEY_API_KEY] = config.apiKey.trim()
            prefs[KEY_MODEL] = config.model.trim()
        }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_MODEL = stringPreferencesKey("model")
    }
}
