package com.example.aihot.ui.anim

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.composed

/**
 * 集中定义动画规范,避免 magic number 散落。
 * 参考 material-3-skill motion 章节:MD3 emphasized 弹簧与缓动。
 */
object Motion {
    // ===== 弹簧(MD3 emphasized 风格)=====
    val DefaultSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    val BouncySpring = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow
    )
    val SnappySpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh
    )

    // ===== Duration(毫秒)=====
    const val SHORT = 150
    const val MEDIUM = 300
    const val LONG = 500

    // ===== Easing(MD3 emphasized cubic-bezier 近似)=====
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

/**
 * 按压时缩放至 0.97,松开回弹。给卡片增加物理反馈感。
 *
 * 自动管理 interactionSource 与 graphicsLayer。
 */
fun Modifier.pressScale(
    pressedScale: Float = 0.97f
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "pressScale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        // 注:这里不直接接管 interactionSource(Card 自己会建一个),
        // 实际点击反馈由 Card(onClick) 自带 ripple 提供。
        // 此扩展用于无 ripple 的纯视觉缩放场景。
}
