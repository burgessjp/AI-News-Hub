package com.peng.ainewshub.data

/**
 * App 对外的统一错误异常体系。
 *
 * data 层 throw 时只抛本体系的子类;ViewModel 经
 * [com.peng.ainewshub.ui.toUiError] 统一映射成 [com.peng.ainewshub.ui.UiState.Error],
 * 用户看到的全是友好文案。
 *
 * 诊断信息约定:子类在 throw 点可携带 detail(如 "HTTP 403 · 读取归档索引失败")
 * 与 cause(传输层原始异常)—— 两者只进 logcat 与 DiagnosticsLog 诊断报告,
 * 不进 UI 文案;设置 → 诊断信息由此可直接读到「哪个文件、为什么失败」,
 * 而不是一个无从区分的 "network"。
 *
 * 与既有专用异常的关系:
 *  - [ShortContentException](翻译原文过短)走 TranslationState.TOO_SHORT,独立
 *  - 其余 data 层 throw 全部改用本 sealed hierarchy 表达分类
 */
sealed class AppException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    /**
     * 业务性「暂无内容」:今日归档未生成 / items 为空 / AI 摘要缺失 / 该源从未抓取过。
     *
     * 语义上不是"出错了"而是"今日还没数据",UI 应走空状态(EmptyState)而非错误态。
     */
    class NoData : AppException("no_data")

    /**
     * 网络层失败:HTTP 4xx/5xx、空响应、连接失败。用户语义:网络异常。
     * [detail] 携带 HTTP 状态码与场景(哪个数据文件),[cause] 保留传输层原始异常
     * (UnknownHostException / SocketTimeoutException 等)供诊断报告打印原因链。
     */
    class Network(detail: String? = null, cause: Throwable? = null) :
        AppException(detail ?: "network", cause)

    /** 服务端返回数据解析失败:JSON 解析失败、响应非预期格式、index 缺字段。用户语义:服务暂不可用。[detail] 携带解析位置/文件标识。 */
    class ServerError(detail: String? = null) : AppException(detail ?: "server")

    /** AI 服务问题:AI 接口服务端故障、AI 输出解析失败。用户语义:AI 服务暂时不可用。 */
    class AiService : AppException("ai_service")

    /**
     * AI 服务鉴权失败:HTTP 401/403,API Key 无效 / 欠费 / 无权限。
     * 用户自填 key 场景下必须与 [AiService] 区分 —— 否则 key 填错会被误读为服务方
     * 故障,用户不知道该去检查「AI 服务」配置。
     */
    class AiAuth : AppException("ai_auth")

    /** 第三方限流/拦截:Cloudflare 挑战、反爬。用户语义:访问受限,请稍后重试。 */
    class RateLimited : AppException("rate_limited")
}
