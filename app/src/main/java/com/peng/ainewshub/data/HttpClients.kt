package com.peng.ainewshub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 全 App 共享的 OkHttpClient 入口。
 *
 * 此前 9 处 Repository 各自 `OkHttpClient.Builder().build()`,每个实例独立的
 * 连接池与调度线程,keep-alive 连接完全无法复用。统一从这里取:
 *  - 配置无定制:直接用 [base](配置即此前各 Repository 的逐字现状);
 *  - 需要定制(如 ArchiveHttpClient 的 WAF cookieJar):`base.newBuilder()` 派生,
 *    派生实例与 base 共享连接池/线程池,仅覆盖差异项;
 *  - 长输出 AI 场景(输出可达数千 token)用 [longRead](放宽 read 超时到 120s),
 *    避免每次按超时值 newBuilder 建一次性 client。
 *
 * 三档 client 共享同一连接池与 Dispatcher(均从 [base] 派生或即 [base] 本身),
 * keep-alive 跨档复用,连接数有界。
 *
 * 超时口径(connect/read/call):
 *  - connect 15s:TCP+TLS 握手上限,第三方源普遍够用;
 *  - read 20s:单次 socket read 间隙上限,覆盖反爬慢响应;
 *  - call 30s:整个请求(含重定向、重连、read 累积)的总兜底上限,防极端卡死
 *    长时间占用协程(尤其 HN 评论树并发、长 HTML 抓取无单次 read 卡死但整体拖延)。
 *
 * retryOnConnectionFailure 显式 true:OkHttp 默认即为 true,这里写明以记录意图——
 * 对偶发的连接抖动(RST/超时)做一次自动重连;第三方反爬限流通常在 HTTP 层(429/403)
 * 而非连接层,不会因此加剧限流。
 */
object HttpClients {

    /** 共享 base client:connect 15s / read 20s / call 30s / 跟随重定向。 */
    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 长输出 client:read 放宽到 120s(其余同 [base])。
     *
     * 用于 AI chat completions 等输出可达数千 token 的场景(如今日总览综合分析),
     * [base] 的 20s read 会撞穿推理型模型的输出耗时。预建常驻,复用连接池,避免
     * 每次按动态超时值 newBuilder 建一次性 client。
     */
    val longRead: OkHttpClient by lazy {
        base.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 统一的浏览器 User-Agent。
     *
     * 第三方站点(GitHub Trending / HuggingFace / stormzhang / The Rundown / aihot API /
     * gitcode 归档)对默认 OkHttp UA 偶尔差异对待(裁剪条目 / 限流 / WAF 拦截),统一带
     * 浏览器 UA。UA 会随时间老化(Chrome 版本号),集中一处便于同步更新。
     */
    const val DEFAULT_BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * 发起一个同步 GET 请求,返回响应正文。在 [Dispatchers.IO] 上执行(阻塞调用)。
     *
     * 收敛此前各 Repository 各自重复的 `newCall().execute().use { if(!successful)
     * throw Network; body?.string() ?: throw Network }` 样板。
     *
     * @param url 完整请求 URL
     * @param headers 请求头键值对(通常含 User-Agent / Accept 等)
     * @param requireNonBlank true(默认)时空白正文视为网络错误抛 [AppException.Network];
     *                        false 时返回空串(供个别需要区分空响应的场景)
     * @throws AppException.Network HTTP 非 2xx、响应体为 null 或(默认)空白
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        requireNonBlank: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        base.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw AppException.Network()
            }
            val body = resp.body?.string()
            when {
                body == null -> throw AppException.Network()
                requireNonBlank && body.isBlank() -> throw AppException.Network()
                else -> body
            }
        }
    }
}
