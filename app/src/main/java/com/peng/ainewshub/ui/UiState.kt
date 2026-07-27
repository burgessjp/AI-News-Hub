package com.peng.ainewshub.ui

import android.util.Log
import com.peng.ainewshub.data.AppException
import com.peng.ainewshub.data.ShortContentException
import java.io.IOException

/** 通用 UI 状态密封接口,所有屏幕共用。 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(
        val message: String,
        val kind: ErrorKind = ErrorKind.Unknown
    ) : UiState<Nothing>
}

/** 错误类别,UI 可据此切换文案/图标。 */
enum class ErrorKind {
    NoData,
    Network,
    ServerError,
    AiService,
    RateLimited,
    Unknown
}

/**
 * Throwable → [UiState.Error] 统一映射。
 *
 * - 友好文案根据异常类型决定(用户看不到任何技术词);
 * - 原始诊断 message(如 "HTTP 404"、"index.json 无 latest 字段")写进 logcat,
 *   开发者调试不损失信息;
 * - [ShortContentException] 不在本函数处理(走 TOO_SHORT 短路)。
 */
fun Throwable.toUiError(): UiState.Error {
    Log.w("UiError", "原始诊断: ${message ?: "(no message)"}", this)
    return when (this) {
        is AppException.NoData       -> UiState.Error("今日内容尚未更新,请稍后再试", ErrorKind.NoData)
        is AppException.Network      -> UiState.Error("网络异常,请检查连接后重试", ErrorKind.Network)
        is AppException.ServerError  -> UiState.Error("服务暂不可用,请稍后重试", ErrorKind.ServerError)
        is AppException.AiService    -> UiState.Error("AI 服务暂时不可用,请稍后重试", ErrorKind.AiService)
        is AppException.RateLimited  -> UiState.Error("访问受限,请稍后重试", ErrorKind.RateLimited)
        is ShortContentException     -> UiState.Error("内容过短", ErrorKind.Unknown)
        // OkHttp/IO 层抛出的连接失败、超时、SSL 异常等,统一归 Network
        is IOException               -> UiState.Error("网络异常,请检查连接后重试", ErrorKind.Network)
        else                         -> UiState.Error("加载失败,请稍后重试", ErrorKind.Unknown)
    }
}
