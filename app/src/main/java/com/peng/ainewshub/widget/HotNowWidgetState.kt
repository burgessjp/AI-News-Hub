package com.peng.ainewshub.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「今日热点」小组件的展示状态 —— 经 SharedPreferences 持久化(进程死亡后仍可渲染旧数据)。
 *
 * 不用 Glance 的 PreferencesGlanceStateDefinition(其状态按 glanceId 隔离):同一份总览
 * 数据要服务多个小组件实例,App 级单文件 SharedPreferences 天然共享,避免逐实例写入
 * 不一致。序列化用 org.json(项目约定,不引 Gson)。
 */
data class HotNowWidgetState(
    val items: List<Item> = emptyList(),
    /** 流水线生成时刻(毫秒),0 = 未知 */
    val generatedAt: Long = 0L,
    /** 「数据截至」时刻(输入快照最新 fetched_at_ms),0 = 未知 */
    val dataFetchedAt: Long = 0L,
    /** 上次拉取成功时刻,provideGlance 据此判断数据是否过期 */
    val lastSuccessAt: Long = 0L,
    /** 上次拉取尝试时刻(无论成败),刷新节流用 */
    val lastAttemptAt: Long = 0L
) {
    /** 小组件列表单条(只存渲染必需字段)。 */
    data class Item(
        val source: String,
        val title: String,
        val url: String,
        val breaking: Boolean
    )

    val hasData: Boolean get() = items.isNotEmpty()
}

internal object HotNowWidgetStore {

    private const val PREFS_NAME = "hot_now_widget"
    private const val KEY_ITEMS = "items_json"
    private const val KEY_GENERATED_AT = "generated_at"
    private const val KEY_DATA_FETCHED_AT = "data_fetched_at"
    private const val KEY_LAST_SUCCESS_AT = "last_success_at"
    private const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读当前状态;从未写入时返回默认空态。 */
    fun read(context: Context): HotNowWidgetState {
        val p = prefs(context)
        return HotNowWidgetState(
            items = parseItems(p.getString(KEY_ITEMS, null)),
            generatedAt = p.getLong(KEY_GENERATED_AT, 0L),
            dataFetchedAt = p.getLong(KEY_DATA_FETCHED_AT, 0L),
            lastSuccessAt = p.getLong(KEY_LAST_SUCCESS_AT, 0L),
            lastAttemptAt = p.getLong(KEY_LAST_ATTEMPT_AT, 0L)
        )
    }

    /** 写入一次拉取成功的结果(同时刷新 lastSuccessAt;lastAttemptAt 由 [markAttempt] 维护)。 */
    fun write(
        context: Context,
        items: List<HotNowWidgetState.Item>,
        generatedAt: Long,
        dataFetchedAt: Long,
        successAt: Long
    ) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("source", item.source)
                put("title", item.title)
                put("url", item.url)
                put("breaking", item.breaking)
            })
        }
        prefs(context).edit()
            .putString(KEY_ITEMS, arr.toString())
            .putLong(KEY_GENERATED_AT, generatedAt)
            .putLong(KEY_DATA_FETCHED_AT, dataFetchedAt)
            .putLong(KEY_LAST_SUCCESS_AT, successAt)
            .apply()
    }

    /** 记录一次拉取尝试时刻(无论成败,节流见 HotNowWidgetUpdater)。 */
    fun markAttempt(context: Context, atMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_ATTEMPT_AT, atMs).apply()
    }

    private fun parseItems(json: String?): List<HotNowWidgetState.Item> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            HotNowWidgetState.Item(
                source = o.optString("source"),
                title = o.optString("title"),
                url = o.optString("url"),
                breaking = o.optBoolean("breaking")
            ).takeIf { it.title.isNotBlank() && it.url.isNotBlank() }
        }
    }
}
