package com.peng.ainewshub.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 触感反馈三档封装 —— 全 App 关键「确认性时刻」共用,不散落各处自调 View。
 *
 * 走 `View.performHapticFeedback(HapticFeedbackConstants.*)` 而非 Compose 的
 * `LocalHapticFeedback`:BOM 2024.12(compose-ui 1.7.6)的 `HapticFeedbackType`
 * 只有 LongPress / TextHandleMove 两档,表达不了「轻点 / 确认 / 拾起」的分级;
 * View 常量三档齐备、自动尊重系统「触摸振动」开关、无需任何权限。
 */
class Haptics(private val view: View) {

    /** 轻反馈:下拉刷新触发、词条展开/收起等轻量确认时刻。 */
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** 中反馈:动作落定 —— 拖拽排序落位、左滑删除提交等。API 30+ 用 CONFIRM,低版本回退 CONTEXT_CLICK。 */
    fun confirm() {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        }
        view.performHapticFeedback(constant)
    }

    /** 重反馈:长按拾起(源排序拖拽起手)。 */
    fun grab() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}

/** Composable 内取触感反馈(按 View 记忆,零重组开销)。 */
@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
