package com.peng.ainewshub.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * 内置 AI 模型:API ID + 估算单价(元/百万 token)。
 *
 * 价格为 2026-07 核对的官方基础档(DeepSeek 取缓存未命中档;GLM 取短输入/短输出最低档,
 * 长输入/长输出有阶梯价),仅用于用量费用估算,UI 一律标注「估算」。
 */
data class AiModel(
    val id: String,
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double
)

/**
 * AI 服务商预设。
 *
 * - DEEPSEEK / GLM: 内置 baseUrl 与模型列表,模型可从列表选也可自填;
 * - CUSTOM: 完全自定义的 OpenAI 兼容服务,baseUrl 需含版本段(如 `.../v1`)。
 */
enum class AiProvider(val label: String, val baseUrl: String, val models: List<AiModel>) {
    DEEPSEEK(
        "DeepSeek",
        "https://api.deepseek.com/v1",
        listOf(
            AiModel("deepseek-v4-flash", 1.0, 2.0),
            AiModel("deepseek-v4-pro", 3.0, 6.0)
        )
    ),
    GLM(
        "智谱 GLM",
        "https://open.bigmodel.cn/api/paas/v4",
        listOf(
            AiModel("glm-5.2", 8.0, 28.0),
            AiModel("glm-5-turbo", 5.0, 22.0),
            AiModel("glm-4.7", 2.0, 8.0)
        )
    ),
    CUSTOM("自定义", "", emptyList());

    companion object {
        /** 按 baseUrl 域名反查 provider(用于旧翻译配置迁移)。 */
        fun detect(baseUrl: String): AiProvider = when {
            baseUrl.contains("deepseek.com") -> DEEPSEEK
            baseUrl.contains("bigmodel.cn") -> GLM
            else -> CUSTOM
        }
    }
}

/**
 * AI 服务全局配置 —— App 内所有端侧 AI 调用(目前为翻译,后续新功能同理)统一走这套配置。
 *
 * - provider: 服务商预设(DEEPSEEK/GLM/CUSTOM),决定 baseUrl 与可选模型列表;
 * - baseUrl: OpenAI 兼容服务根(含版本段),请求时拼接 `/chat/completions`;
 *   预设 provider 下留空时回落到 [AiProvider.baseUrl](见 [effectiveBaseUrl]);
 * - apiKey: 用户自有、用户自负,存 App 私有目录,不进 APK、不进日志;
 * - model: 模型 API ID,可选内置列表也可自填;
 * - customInputPrice / customOutputPrice: 仅 CUSTOM 的自定义模型用,元/百万 token,
 *   留空则该模型只统计 token 不估算费用;
 * - translateEnabled: 翻译功能开关。关闭时 UI 不渲染「译」按钮,避免打扰不需要翻译的用户。
 */
data class AiConfig(
    val provider: AiProvider = AiProvider.DEEPSEEK,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val customInputPrice: String = "",
    val customOutputPrice: String = "",
    val translateEnabled: Boolean = false
) {
    /** 实际生效的 baseUrl:预设 provider 下用户未填(或未存)时用预设地址。 */
    val effectiveBaseUrl: String
        get() = baseUrl.ifBlank { provider.baseUrl }

    /** 三项齐全才算「就绪」,缺任一项时点「译」按钮引导用户去设置。 */
    val isReady: Boolean
        get() = effectiveBaseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /**
     * 当前配置模型的估算单价(输入/输出,元/百万 token)。
     * 内置模型查表;CUSTOM 读用户手填(缺一返回 null,该模型只统计 token)。
     */
    fun currentPricing(): Pair<Double, Double>? = when (provider) {
        AiProvider.CUSTOM -> {
            val input = customInputPrice.trim().toDoubleOrNull()
            val output = customOutputPrice.trim().toDoubleOrNull()
            if (input != null && output != null) input to output else null
        }
        else -> builtinPricingOf(model)
    }

    companion object {
        /** 全内置模型表按 API ID 查价;未命中(自定义模型名)返回 null。 */
        fun builtinPricingOf(modelId: String): Pair<Double, Double>? =
            AiProvider.entries.asSequence()
                .flatMap { it.models.asSequence() }
                .firstOrNull { it.id == modelId }
                ?.let { it.inputPricePerMillion to it.outputPricePerMillion }
    }
}

/** 顶层扩展,保证整个进程对 "ai_prefs" 只有一个 DataStore 实例(DataStore 必须单例)。 */
internal val Context.aiDataStore: DataStore<Preferences> by preferencesDataStore("ai_prefs")

/** 旧版翻译配置(DataStore "translation_prefs"),仅用于一次性迁移读取。 */
private val Context.legacyTranslationDataStore: DataStore<Preferences> by preferencesDataStore("translation_prefs")

/**
 * AI 服务配置持久化。基于 DataStore Preferences("ai_prefs")。
 *
 * 取代旧 [translation_prefs](仅翻译场景):首次读取时若本 store 未初始化而旧 store
 * 有数据,一次性迁移(provider 按域名识别;旧 baseUrl 约定填根路径拼 `/v1/...`,
 * 新约定含版本段,迁移时自动补 `/v1`),写完打 `migrated` 标记,旧数据不再读取。
 *
 * 异步 API:UI 层用 `collectAsStateWithLifecycle` 订阅 [configFlow],写入用 [update]。
 */
class AiConfigStore(context: Context) {

    private val appContext = context.applicationContext
    private val dataStore = appContext.aiDataStore

    /**
     * 配置流。首次订阅时先执行一次性迁移(见类注释),再转发持久化数据。
     * 迁移不能写在 map 变换里(DataStore 不允许在自身 data 流的变换中 edit,会死锁)。
     */
    val configFlow: Flow<AiConfig> = flow {
        migrateLegacyIfNeeded()
        emitAll(
            dataStore.data.map { prefs ->
                AiConfig(
                    provider = prefs[KEY_PROVIDER]?.let { name ->
                        AiProvider.entries.firstOrNull { it.name == name }
                    } ?: AiProvider.DEEPSEEK,
                    baseUrl = prefs[KEY_BASE_URL] ?: "",
                    apiKey = prefs[KEY_API_KEY] ?: "",
                    model = prefs[KEY_MODEL] ?: "",
                    customInputPrice = prefs[KEY_CUSTOM_INPUT_PRICE] ?: "",
                    customOutputPrice = prefs[KEY_CUSTOM_OUTPUT_PRICE] ?: "",
                    translateEnabled = prefs[KEY_TRANSLATE_ENABLED] ?: false
                )
            }
        )
    }

    suspend fun update(config: AiConfig) {
        dataStore.edit { prefs ->
            prefs[KEY_PROVIDER] = config.provider.name
            prefs[KEY_BASE_URL] = config.baseUrl.trim()
            prefs[KEY_API_KEY] = config.apiKey.trim()
            prefs[KEY_MODEL] = config.model.trim()
            prefs[KEY_CUSTOM_INPUT_PRICE] = config.customInputPrice.trim()
            prefs[KEY_CUSTOM_OUTPUT_PRICE] = config.customOutputPrice.trim()
            prefs[KEY_TRANSLATE_ENABLED] = config.translateEnabled
        }
    }

    /** 旧翻译配置一次性迁移:仅当本 store 未迁移过且旧配置有内容时带入。 */
    private suspend fun migrateLegacyIfNeeded() {
        if (dataStore.data.first()[KEY_MIGRATED] == true) return
        val legacy = appContext.legacyTranslationDataStore.data.first()
        val legacyBase = normalizeBaseUrl(legacy[LEGACY_KEY_BASE_URL].orEmpty())
        val legacyKey = legacy[LEGACY_KEY_API_KEY].orEmpty()
        val legacyModel = legacy[LEGACY_KEY_MODEL].orEmpty()
        val legacyEnabled = legacy[LEGACY_KEY_ENABLED] ?: false
        dataStore.edit { prefs ->
            prefs[KEY_MIGRATED] = true
            if (legacyBase.isNotBlank() || legacyKey.isNotBlank() || legacyModel.isNotBlank()) {
                prefs[KEY_PROVIDER] = AiProvider.detect(legacyBase).name
                prefs[KEY_BASE_URL] = legacyBase
                prefs[KEY_API_KEY] = legacyKey
                prefs[KEY_MODEL] = legacyModel
                prefs[KEY_TRANSLATE_ENABLED] = legacyEnabled
            }
        }
    }

    private companion object {
        val KEY_PROVIDER = stringPreferencesKey("provider")
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_MODEL = stringPreferencesKey("model")
        val KEY_CUSTOM_INPUT_PRICE = stringPreferencesKey("custom_input_price")
        val KEY_CUSTOM_OUTPUT_PRICE = stringPreferencesKey("custom_output_price")
        val KEY_TRANSLATE_ENABLED = booleanPreferencesKey("translate_enabled")
        val KEY_MIGRATED = booleanPreferencesKey("migrated")

        // 旧 translation_prefs 的 key(仅迁移读取)
        val LEGACY_KEY_ENABLED = booleanPreferencesKey("enabled")
        val LEGACY_KEY_BASE_URL = stringPreferencesKey("base_url")
        val LEGACY_KEY_API_KEY = stringPreferencesKey("api_key")
        val LEGACY_KEY_MODEL = stringPreferencesKey("model")

        /** 旧约定 baseUrl 填根路径(拼 `/v1/chat/completions`);新约定含版本段,此处自动补 `/v1`。 */
        fun normalizeBaseUrl(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            if (trimmed.isEmpty()) return trimmed
            return if (Regex("/v\\d+$").containsMatchIn(trimmed)) trimmed else "$trimmed/v1"
        }
    }
}
