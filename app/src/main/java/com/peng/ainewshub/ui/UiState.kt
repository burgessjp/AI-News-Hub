package com.peng.ainewshub.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.peng.ainewshub.R
import com.peng.ainewshub.data.AppException
import com.peng.ainewshub.data.ShortContentException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    AiAuth,
    RateLimited,
    Unknown
}

/**
 * Throwable → [UiState.Error] 统一映射。
 *
 * - 友好文案根据异常类型决定,经 [context] 按当前语言取词(调用方传
 *   `context.localized()` 或局部化的 Composable context,用户看不到任何技术词);
 * - 原始诊断 message(如 "HTTP 404"、"index.json 无 latest 字段")写进 logcat,
 *   开发者调试不损失信息;
 * - [ShortContentException] 不在本函数处理(走 TOO_SHORT 短路)。
 */
fun Throwable.toUiError(context: Context): UiState.Error {
    Log.w("UiError", "原始诊断: ${message ?: "(no message)"}", this)
    return when (this) {
        is AppException.NoData       -> UiState.Error(context.getString(R.string.error_no_data), ErrorKind.NoData)
        is AppException.Network      -> UiState.Error(context.getString(R.string.error_network), ErrorKind.Network)
        is AppException.ServerError  -> UiState.Error(context.getString(R.string.error_server), ErrorKind.ServerError)
        is AppException.AiService    -> UiState.Error(context.getString(R.string.error_ai_service), ErrorKind.AiService)
        is AppException.AiAuth       -> UiState.Error(context.getString(R.string.error_ai_auth), ErrorKind.AiAuth)
        is AppException.RateLimited  -> UiState.Error(context.getString(R.string.error_rate_limited), ErrorKind.RateLimited)
        is ShortContentException     -> UiState.Error(context.getString(R.string.error_too_short), ErrorKind.Unknown)
        // OkHttp/IO 层抛出的连接失败、超时、SSL 异常等,统一归 Network
        is IOException               -> UiState.Error(context.getString(R.string.error_network), ErrorKind.Network)
        else                         -> UiState.Error(context.getString(R.string.error_unknown), ErrorKind.Unknown)
    }
}

/**
 * 下拉刷新指示器最小展示时长(正常网络刷新远超此值,只兜瞬间完成的缓存命中)。
 * 此前在 5 个 ViewModel 里逐字重复,现收口于此。
 */
const val MIN_REFRESH_SPIN_MS = 600L

/**
 * 阻塞到「最小转圈时长」用满才返回:PullToRefreshBox 的指示器若同帧 true→false
 * 会卡在展示态不收起,各 ViewModel 的 forceRefresh 在 finally 复位 isRefreshing
 * 之前调用本函数兜底。入参为刷新开始时刻(SystemClock.elapsedRealtime 口径)。
 * VM 已销毁(协程被取消)时直接返回,复位已无意义。
 */
suspend fun ensureMinRefreshSpin(startedAtElapsed: Long) {
    val remaining = MIN_REFRESH_SPIN_MS - (SystemClock.elapsedRealtime() - startedAtElapsed)
    if (remaining > 0) {
        try {
            delay(remaining)
        } catch (_: CancellationException) {
            // VM 已销毁,复位无意义
        }
    }
}
