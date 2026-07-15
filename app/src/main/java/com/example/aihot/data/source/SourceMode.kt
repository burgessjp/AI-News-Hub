package com.example.aihot.data.source

/**
 * Hub 数据源模式 —— 全局开关,决定 4 个稳定源(HackerNews / GitHub Trending /
 * stormzhang AI / HuggingFace Papers)从哪里取数。
 *
 * - [LIVE]:实时抓取(各源自有 Repository 直连第三方站点),App 原有默认行为。
 * - [ARCHIVE]:从 gitcode 数据仓库(AI-News-Hub-Data,news-hub-data 分支)读取
 *   GitHub Action 每天定时归档的历史快照。数据非实时,但稳定不受第三方站点
 *   反爬(如 Cloudflare)影响。
 *
 * LinuxDo 不参与切换:它 CI 上归档拿不到(CF 拦截),始终走实时抓取。
 *
 * 持久化:存于 display_prefs 的 source_mode 键,按 [name] 存取;未知值回退 [LIVE]。
 */
enum class SourceMode(val label: String) {
    LIVE("实时抓取"),
    ARCHIVE("Gitcode 归档");

    companion object {
        /** 从 DataStore 读出的字符串安全解析;未知/空值回退 [LIVE]。 */
        fun fromStored(name: String?): SourceMode =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: LIVE
    }
}
