package com.peng.ainewshub.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.ui.anim.Motion
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText
import kotlinx.coroutines.delay

/**
 * 单条轻提示的内容规格([NoticePillState.show] 入参/读取单元)。
 *
 * @property message 展示文案(调用方拼装好的最终字符串)
 * @property icon 前置装饰图标
 * @property durationMs 展示时长,到点自动淡出;重复 show 会以新的一条重置计时
 * @property tag 可选标识(如离线提示 "offline"),供 [NoticePillState.clearIfTag] 条件撤下
 * @property id 由 [NoticePillState] 自增分配,驱动宿主按「每条新提示重置计时器」
 */
data class ActiveNotice(
    val message: String,
    val icon: ImageVector,
    val durationMs: Long,
    val tag: String?,
    val id: Long
)

/**
 * 顶部轻提示胶囊的状态容器:[show] 换入新内容(旧内容保留在 [notice]),
 * 显隐由 [visible] 单独控制 —— 这样淡出动画期间内容仍在组合,不会瞬间掏空。
 *
 * 注意:[notice]/[visible] 对外只读,变更一律经 [show]/[dismiss]/[clearIfTag]。
 */
class NoticePillState {
    var notice by mutableStateOf<ActiveNotice?>(null)
        private set
    var visible by mutableStateOf(false)
        private set

    private var nextId = 0L

    /**
     * 立即展示一条轻提示:顶掉正在显示的上一条并整体重置消失计时。
     * 连续两条相同文案也会因 id 递增被识别为新的一条。
     */
    fun show(message: String, icon: ImageVector, durationMs: Long, tag: String? = null) {
        notice = ActiveNotice(message, icon, durationMs, tag, nextId++)
        visible = true
    }

    /** 立即淡出(内容保留供退场动画渲染;下一次 [show] 直接覆盖)。 */
    fun dismiss() {
        visible = false
    }

    /** 仅当当前正在展示的是带 [tag] 标识的提示时才淡出(他人先弹出时不误伤)。 */
    fun clearIfTag(tag: String) {
        if (visible && notice?.tag == tag) dismiss()
    }
}

@Composable
fun rememberNoticePillState(): NoticePillState = remember { NoticePillState() }

/**
 * 顶部居中轻提示胶囊 —— 项目内轻量瞬时通知的统一形态(「已是最新批次」/
 * 离线兜底共用),替代 Material 默认 Snackbar。
 *
 * 视觉与浮动药丸家族(底栏 / TtsFloatingPill / WebView 回读提示)同款悬浮语言:
 * CircleShape 近实底 + 3dp 浮起阴影 + 玻璃边缘描边;进出场自上方滑入滑出
 * (enter 减速 / exit 加速,时长走 [Motion] 令牌)。
 *
 * 定位由调用方传入(惯例:`align(TopCenter) + statusBarsPadding`),
 * 组件自身只负责内容与计时:每条提示按 [ActiveNotice.durationMs] 到点自动淡出。
 *
 * @param state 经 [rememberNoticePillState] 创建的事件入口(各处可持有引用调 show)
 * @param modifier 调用方定位用(BoxScope 内 align 等)
 */
@Composable
fun NoticePillHost(
    state: NoticePillState,
    modifier: Modifier = Modifier
) {
    // 到点自动淡出:key 同时含 id 与 visible —— show() 换新条目即重启整段延时;
    // clearIfTag 提前淡出(visible→false)也即时作废残留计时,不存在陈旧回调。
    LaunchedEffect(state.notice?.id, state.visible) {
        val notice = state.notice
        if (!state.visible || notice == null) return@LaunchedEffect
        delay(notice.durationMs)
        state.dismiss()
    }

    AnimatedVisibility(
        visible = state.visible,
        enter = fadeIn(tween(Motion.SHORT, easing = Motion.EmphasizedDecel)) +
            slideInVertically(
                initialOffsetY = { -it / 2 },
                animationSpec = tween(Motion.MEDIUM, easing = Motion.EmphasizedDecel)
            ),
        exit = fadeOut(tween(Motion.SHORT, easing = Motion.EmphasizedAccel)) +
            slideOutVertically(
                targetOffsetY = { -it / 2 },
                animationSpec = tween(Motion.SHORT, easing = Motion.EmphasizedAccel)
            ),
        modifier = modifier
    ) {
        val notice = state.notice ?: return@AnimatedVisibility
        val cs = MaterialTheme.colorScheme
        Surface(
            shape = CircleShape,
            color = cs.surfaceContainer.copy(alpha = AppAlpha.bottomBarSurface),
            shadowElevation = 3.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = AppAlpha.glassEdge))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = notice.icon,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = notice.message,
                    style = AppText.caption,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
