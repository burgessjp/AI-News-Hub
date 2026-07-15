package com.example.aihot.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * gitcode 归档数据 HTTP 客户端 —— 4 个 Archive Repository 共用。
 *
 * 数据仓库:https://gitcode.com/peng1818/AI-News-Hub-Data
 * 分支:news-hub-data
 * 端点:gitcode 官方 REST API 的 raw 文件接口(见 [API_BASE])
 *
 * 取数流程(对齐 docs/news-hub-data-usage.md):
 *  1. GET `index.json?ref=news-hub-data` → 读 `latest.<source>` 拿最新快照路径
 *  2. 拼 `<API_BASE>/<source>/<相对路径>?ref=news-hub-data` GET 该快照 JSON
 *  3. 解析顶层 `fetched_at_ms` 与 `items[]`,交由各 Repository 做字段映射
 *
 * 归档本身是历史快照,无缓存概念:每次都直接打网络(fetch == forceRefresh)。
 * 任一步失败(HTTP 错误 / index 无该源 / items 为空)抛 RuntimeException,
 * 交由 ViewModel 显示 Error 态(归档模式明确提示,不回退实时)。
 *
 * 走 gitcode 官方 REST API(api.gitcode.com/api/v5/.../raw/)而非 raw 直链:
 * raw.gitcode.com 背后是华为云 WAF,部分网络环境(数据中心/特定地区 IP)被拦 403;
 * 官方 API 走独立服务,公开仓库匿名可读,稳定性更好(实测连发无 403)。
 *
 * 客户端配置对齐 App 其余 Repository:connect 15s / read 20s / 跟随重定向。
 * cookieJar 保留:API 端也可能下发会话 cookie,带上无害且降低潜在限流概率。
 */
object ArchiveHttpClient {

    /**
     * gitcode 官方 API 的 raw 文件端点根(分支 news-hub-data)。
     * 完整 URL 形如:
     *   <API_BASE>/index.json?ref=news-hub-data          ← 读 index
     *   <API_BASE>/<source>/<date>/<time>-data.json?ref=news-hub-data  ← 读快照
     */
    private const val API_BASE =
        "https://api.gitcode.com/api/v5/repos/peng1818/AI-News-Hub-Data/raw"

    /** 分支名(API 用 ref 查询参数指定)。 */
    private const val REF = "news-hub-data"

    private const val INDEX_URL = "$API_BASE/index.json?ref=$REF"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            // gitcode raw 背后是华为云 WAF,会下发 HWWAFSESID 等会话 cookie;不带 cookie
            // 的裸请求容易被 WAF 判为可疑流量返回 403。配 cookieJar 让客户端像浏览器一样
            // 记住并回传会话 cookie,降低被拦概率。内存存储(进程级,随 App 生命周期)。
            .cookieJar(InMemoryCookieJar())
            .build()
    }

    /**
     * 拉某源的归档快照,返回解析后的顶层 JSON(含 fetched_at_ms / items 等)。
     *
     * @param source 源标识,对应 index.json 的 latest 键与目录名
     *               (hackernews / github-trending / stormzhang-ai / huggingface-papers)
     * @return 该源最新快照的 JSON 对象;index 无该源或快照缺失抛 RuntimeException
     */
    suspend fun fetchLatestSnapshot(source: String): JSONObject = withContext(Dispatchers.IO) {
        // 1) 读 index.json 拿最新路径
        val indexText = getRaw(INDEX_URL, "读取归档索引失败")
        val index = runCatching { JSONObject(indexText) }
            .getOrElse { throw RuntimeException("归档 index.json 解析失败") }
        val latest = index.optJSONObject("latest")
            ?: throw RuntimeException("归档 index.json 无 latest 字段")
        val relPath = latest.optString(source).takeIf { it.isNotBlank() }
            ?: throw RuntimeException("归档暂无 $source 数据(可能该源从未归档成功)")

        // 2) 拉该源最新快照(API raw 端点,带 ref 指定分支)
        val snapshotUrl = "$API_BASE/$source/$relPath?ref=$REF"
        val snapshotText = getRaw(snapshotUrl, "读取归档快照失败")
        val snapshot = runCatching { JSONObject(snapshotText) }
            .getOrElse { throw RuntimeException("归档快照 JSON 解析失败:$source") }

        // 3) items 为空视为无数据
        val items = snapshot.optJSONArray("items")
        if (items == null || items.length() == 0) {
            throw RuntimeException("归档暂无 $source 数据(快照 items 为空)")
        }
        snapshot
    }

    /** GET 一个 URL,返回响应正文;非 2xx 或空响应抛带 [hint] 的 RuntimeException。 */
    private fun getRaw(url: String, hint: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json,text/plain,*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("$hint:HTTP ${resp.code}")
            }
            return resp.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw RuntimeException("$hint:空响应")
        }
    }
}

/**
 * 进程级内存 CookieJar —— 记住 gitcode WAF 下发的会话 cookie 并在后续请求回传。
 *
 * 仅用于 [ArchiveHttpClient](gitcode raw 域名),进程内单实例,App 退出即清空。
 * 实现极简:不做过期清理(cookie 量极小,WAF 会话 cookie 短命),用 ConcurrentHashMap
 * 保证多源并发请求时的线程安全。
 *
 * 不持久化:cookie 仅用于降低 WAF 拦截概率,无登录态意义,无需跨进程保留。
 */
private class InMemoryCookieJar : CookieJar {

    private val store: MutableMap<String, MutableList<Cookie>> = ConcurrentHashMap()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val list = store.getOrPut(url.host) { mutableListOf() }
        synchronized(list) {
            // 同名 cookie 替换(更新值/有效期),新增的追加
            for (c in cookies) {
                list.removeAll { it.name == c.name }
                list.add(c)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = store[url.host] ?: return emptyList()
        return synchronized(list) {
            // 过滤掉已过期 cookie,顺带清理
            val now = System.currentTimeMillis()
            list.filter { it.expiresAt > now }.also { valid ->
                if (valid.size != list.size) list.retainAll(valid)
            }
        }
    }
}