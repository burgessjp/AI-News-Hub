package com.peng.ainewshub.data

/**
 * App 对外的统一错误异常体系。
 *
 * data 层 throw 时只抛本体系的子类(不再带中文 message);ViewModel 经
 * [com.peng.ainewshub.ui.toUiError] 统一映射成 [com.peng.ainewshub.ui.UiState.Error],
 * 原始诊断 message 进 logcat 供调试。这样用户看到的全是友好文案,
 * 开发者也能在 logcat 里看到原诊断信息。
 *
 * 与既有专用异常的关系:
 *  - [ShortContentException](翻译原文过短)走 TranslationState.TOO_SHORT,独立
 *  - 其余 data 层 throw 全部改用本 sealed hierarchy 表达分类
 */
sealed class AppException(message: String) : RuntimeException(message) {
    /**
     * 业务性「暂无内容」:今日归档未生成 / items 为空 / AI 摘要缺失 / 该源从未抓取过。
     *
     * 语义上不是"出错了"而是"今日还没数据",UI 应走空状态(EmptyState)而非错误态。
     */
    class NoData : AppException("no_data")

    /** 网络层失败:HTTP 4xx/5xx、空响应、连接失败。用户语义:网络异常。 */
    class Network : AppException("network")

    /** 服务端返回数据解析失败:JSON 解析失败、响应非预期格式、index 缺字段。用户语义:服务暂不可用。 */
    class ServerError : AppException("server")

    /** AI 服务问题:AI 接口鉴权失败、AI 输出解析失败。用户语义:AI 服务暂时不可用。 */
    class AiService : AppException("ai_service")

    /** 第三方限流/拦截:Cloudflare 挑战、反爬。用户语义:访问受限,请稍后重试。 */
    class RateLimited : AppException("rate_limited")
}
