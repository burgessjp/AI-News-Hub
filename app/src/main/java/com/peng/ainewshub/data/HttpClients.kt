package com.peng.ainewshub.data

import okhttp3.OkHttpClient
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
}
