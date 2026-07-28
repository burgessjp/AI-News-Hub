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
 *    派生实例与 base 共享连接池/线程池,仅覆盖差异项。
 */
object HttpClients {

    /** 共享 base client:connect 15s / read 20s / 跟随重定向。 */
    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
