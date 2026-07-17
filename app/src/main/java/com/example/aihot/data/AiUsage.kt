package com.example.aihot.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 调用用量统计(token 消耗)持久化。
 *
 * 与 [AiConfigStore] 共用 "ai_prefs" DataStore(单例,见 [aiDataStore]),
 * `usage_json` key 存聚合 JSON:`{ "<model>@<yyyy-MM>": {"p":输入,"c":输出,"n":次数} }`。
 * 按「模型 × 月」聚合,条目极少不膨胀;读写都在 DataStore edit 事务内完成,天然串行无需额外锁。
 *
 * 费用不在此计算 —— 单价属于配置层([AiConfig.currentPricing] / [AiConfig.builtinPricingOf]),
 * UI 读取 [statsFlow] 后按当前配置估算。
 */
class AiUsageStore(context: Context) {

    private val dataStore = context.applicationContext.aiDataStore

    /** 一条聚合记录:某模型某月的输入/输出 token 与调用次数。 */
    data class Entry(
        val model: String,
        val month: String,
        val promptTokens: Long,
        val completionTokens: Long,
        val calls: Long
    ) {
        val totalTokens: Long get() = promptTokens + completionTokens
    }

    /** 用量流:聚合条目按模型名 + 月份排序。无记录时为 emptyList。 */
    val statsFlow: Flow<List<Entry>> = dataStore.data.map { prefs ->
        val root = runCatching { JSONObject(prefs[KEY_USAGE_JSON].orEmpty()) }.getOrNull()
            ?: return@map emptyList()
        root.keys().asSequence().mapNotNull { key ->
            val e = root.optJSONObject(key) ?: return@mapNotNull null
            val split = key.lastIndexOf('@')
            if (split <= 0) return@mapNotNull null
            Entry(
                model = key.substring(0, split),
                month = key.substring(split + 1),
                promptTokens = e.optLong("p"),
                completionTokens = e.optLong("c"),
                calls = e.optLong("n")
            )
        }.sortedWith(compareBy({ it.model }, { it.month })).toList()
    }

    /** 记录一次调用的 token 消耗(响应无 usage 时两侧都为 0,直接跳过)。 */
    suspend fun record(model: String, promptTokens: Int, completionTokens: Int) {
        if (promptTokens <= 0 && completionTokens <= 0) return
        val key = "$model@${monthNow()}"
        dataStore.edit { prefs ->
            val root = runCatching { JSONObject(prefs[KEY_USAGE_JSON].orEmpty()) }
                .getOrDefault(JSONObject())
            val e = root.optJSONObject(key) ?: JSONObject()
            e.put("p", e.optLong("p") + promptTokens)
            e.put("c", e.optLong("c") + completionTokens)
            e.put("n", e.optLong("n") + 1)
            root.put(key, e)
            prefs[KEY_USAGE_JSON] = root.toString()
        }
    }

    /** 清空全部用量统计。 */
    suspend fun clear() {
        dataStore.edit { it.remove(KEY_USAGE_JSON) }
    }

    companion object {
        private val KEY_USAGE_JSON = stringPreferencesKey("usage_json")

        /** 当前月份(yyyy-MM),与聚合 key 同格式,供 UI 筛「本月」。 */
        fun monthNow(): String =
            SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
    }
}
