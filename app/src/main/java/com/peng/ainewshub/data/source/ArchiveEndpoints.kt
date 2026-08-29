package com.peng.ainewshub.data.source

/**
 * gitcode 归档端点拼接 —— 基址唯一可变点。
 *
 * 生产恒为 [DEFAULT_API_BASE];单测经 [ArchiveHttpClient.reconfigureForTest] 把基址
 * 指向本地 MockWebServer,故 URL 一律按需拼接(不预拼接为 const)。
 *
 * 走 gitcode 官方 REST API(api.gitcode.com/api/v5/.../raw/)而非 raw 直链:
 * raw.gitcode.com 背后是华为云 WAF,部分网络环境(数据中心/特定地区 IP)被拦 403;
 * 官方 API 走独立服务,公开仓库匿名可读,稳定性更好(实测连发无 403)。
 */
internal object ArchiveEndpoints {

    /**
     * gitcode 官方 API 的 raw 文件端点根(分支 news-hub-data)。
     * 完整 URL 形如:
     *   <API_BASE>/index.json?ref=news-hub-data          ← 读 index
     *   <API_BASE>/<source>/<date>/<time>-data.json?ref=news-hub-data  ← 读快照
     */
    private const val DEFAULT_API_BASE =
        "https://api.gitcode.com/api/v5/repos/peng1818/AI-News-Hub-Data/raw"

    /** 分支名(API 用 ref 查询参数指定)。 */
    private const val REF = "news-hub-data"

    /** 当前生效的 API 基址:生产恒为默认值;仅 [ArchiveHttpClient.reconfigureForTest] 会改写。 */
    @Volatile
    internal var apiBase: String = DEFAULT_API_BASE

    /** 根级文件(index / 历史索引 / 趋势)的完整 URL。 */
    internal fun rootUrl(fileName: String): String = "$apiBase/$fileName?ref=$REF"

    /** 源快照 / 归档文件的完整 URL。 */
    internal fun fileUrl(source: String, relPath: String): String = "$apiBase/$source/$relPath?ref=$REF"

    /** 重置为生产基址(仅测试重置入口调用)。 */
    internal fun resetForTest() {
        apiBase = DEFAULT_API_BASE
    }
}
