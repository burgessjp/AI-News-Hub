package com.example.aihot.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * AI HOT 官方公开 API 客户端。
 * 文档: https://aihot.virxact.com/agent (匿名免费,无需 token)
 *
 * 重要约定:
 *  - User-Agent 必须带(默认 curl UA 会被 nginx 黑名单 403)
 *  - cursor 视作不透明 token,原样回传,不要解析/递增/跨端点复用
 *  - take 上限 100;since 必须 ISO 8601 UTC
 *  - category 5 选 1,不可多选
 */
class NewsRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val base = "https://aihot.virxact.com/api/public"
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // ===== /items =====

    /**
     * 全部 AI 动态。
     *
     * @param mode 精选(selected,默认)/ 全部(all)
     * @param category 5 类之一,可选
     * @param query 关键词搜索(2-200 字,<2 字视作不搜索)
     * @param since ISO 8601 起始时间(7 天内)
     * @param take 每页条数 1-100
     * @param cursor 上次响应的 nextCursor
     */
    suspend fun fetchItems(
        mode: Mode = Mode.SELECTED,
        category: NewsCategory? = null,
        query: String? = null,
        since: String? = null,
        take: Int = 50,
        cursor: String? = null
    ): NewsPage = withContext(Dispatchers.IO) {
        val params = buildList {
            add("mode" to mode.api)
            add("take" to take.coerceIn(1, 100).toString())
            category?.let { add("category" to it.api) }
            query?.takeIf { it.trim().length >= 2 }?.let { add("q" to enc(it)) }
            since?.takeIf { it.isNotBlank() }?.let { add("since" to it) }
            cursor?.takeIf { it.isNotBlank() }?.let { add("cursor" to enc(it)) }
        }
        val body = get("$base/items", params)
        val root = JSONObject(body)
        val arr = root.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).map { NewsItem.fromJson(arr.getJSONObject(it)) }
        NewsPage(
            items = items,
            hasNext = root.optBoolean("hasNext", false),
            nextCursor = root.optString("nextCursor").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    // ===== /daily =====

    /** 最新日报(date=null)或指定日期日报(YYYY-MM-DD)。 */
    suspend fun fetchDaily(date: String? = null): DailyReport = withContext(Dispatchers.IO) {
        val url = if (date.isNullOrBlank()) "$base/daily" else "$base/daily/${enc(date)}"
        val root = JSONObject(get(url, emptyList()))
        parseDaily(root)
    }

    /** 日报归档索引(默认 30 期,上限 180)。 */
    suspend fun fetchDailies(take: Int = 30): List<DailySummary> = withContext(Dispatchers.IO) {
        val root = JSONObject(get("$base/dailies", listOf("take" to take.coerceIn(1, 180).toString())))
        val arr = root.optJSONArray("items") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DailySummary(
                date = o.optString("date"),
                generatedAt = o.optString("generatedAt"),
                leadTitle = o.optString("leadTitle").takeIf { it.isNotBlank() }
            )
        }
    }

    // ===== 解析辅助 =====

    private fun parseDaily(root: JSONObject): DailyReport {
        val lead = root.optJSONObject("lead")?.let {
            Lead(
                title = it.optString("title"),
                leadParagraph = it.optString("leadParagraph").takeIf { s -> s.isNotBlank() }
            )
        }
        val sectionsArr = root.optJSONArray("sections") ?: JSONArray()
        val sections = (0 until sectionsArr.length()).map { i ->
            val s = sectionsArr.getJSONObject(i)
            val itemsArr = s.optJSONArray("items") ?: JSONArray()
            DailySection(
                label = s.optString("label"),
                items = (0 until itemsArr.length()).map { j -> parseDailyEntry(itemsArr.getJSONObject(j)) }
            )
        }
        val flashesArr = root.optJSONArray("flashes") ?: JSONArray()
        val flashes = (0 until flashesArr.length()).map { i ->
            val f = flashesArr.getJSONObject(i)
            Flash(
                title = f.optString("title"),
                sourceName = f.optString("sourceName"),
                sourceUrl = f.optString("sourceUrl"),
                publishedAt = f.optString("publishedAt").takeIf { it.isNotBlank() },
                permalink = f.optString("permalink").takeIf { it.isNotBlank() && it != "null" }
            )
        }
        return DailyReport(
            date = root.optString("date"),
            generatedAt = root.optString("generatedAt"),
            windowStart = root.optString("windowStart"),
            windowEnd = root.optString("windowEnd"),
            lead = lead,
            sections = sections,
            flashes = flashes
        )
    }

    private fun parseDailyEntry(o: JSONObject): DailyEntry = DailyEntry(
        title = o.optString("title"),
        summary = o.optString("summary").takeIf { it.isNotBlank() },
        sourceUrl = o.optString("sourceUrl"),
        sourceName = o.optString("sourceName"),
        permalink = o.optString("permalink").takeIf { it.isNotBlank() && it != "null" }
    )

    // ===== HTTP =====

    private fun get(path: String, params: List<Pair<String, String>>): String {
        val url = if (params.isEmpty()) path else {
            path + "?" + params.joinToString("&") { (k, v) -> "$k=$v" }
        }
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("User-Agent", userAgent)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(resp.body?.string().orEmpty()).optString("error") }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                throw RuntimeException("HTTP ${resp.code}${msg?.let { ": $it" } ?: ""}")
            }
            return resp.body?.string() ?: throw RuntimeException("空响应")
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/** /items 分页响应信封。 */
data class NewsPage(
    val items: List<NewsItem>,
    val hasNext: Boolean,
    val nextCursor: String?
)
