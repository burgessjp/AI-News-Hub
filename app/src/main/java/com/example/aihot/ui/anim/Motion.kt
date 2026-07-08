package com.example.aihot.ui.anim

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

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
 * 页面转场风格。每个页面在 [PageNavStyle] 枚举里声明一种风格,
 * [pageTransition] 据此统一产出一对进/出 [ContentTransform]。
 *
 * 这样添加新页面时只需标注风格,无需在 transitionSpec 里写 when 分支。
 *
 * @property OVERLAY 覆盖型全屏页(Detail / Web 等):scale + fade 浮现。
 *  对含 AndroidView 的页面(WebView)安全——位移会撕裂,缩放不会。
 * @property PUSH 普通二级页(Search / Settings 等):纯横向位移推入,无 fade 无重影。
 *  作为 [Page.navStyle] 的默认值,新增二级页无需声明即可获得此风格。
 * @property NONE tab 根页之间切换。
 */
enum class PageNavStyle { OVERLAY, PUSH, NONE }

/**
 * 统一的页面转场工厂。
 *
 * 调用方告知 [enter]/[exit] 两个页面的 [PageNavStyle] 与是否为返回([back]),
 * 由此函数集中产出进/出动画对,实现"一处配置,处处生效"。
 *
 * ## 决策规则(按优先级,first match wins)
 *
 * 1. **任意一边是 OVERLAY → scale + fade,永不位移。**
 *    OVERLAY 页(Detail/Web,Web 含 AndroidView)对横向位移不安全——WebView
 *    会被撕裂。只要它参与转场(进或出),整个转场改用 scale/fade,从根源杜绝。
 *    - 进入 OVERLAY:新页从 0.96 scale up 浮现,下层页轻微缩放(被压感)但不消失。
 *    - 离开 OVERLAY:上层 OVERLAY scale down 退场,下层页 fade in 揭示。
 *    进入/离开各自独立判断,不受 [back] 干扰——OVERLAY 的进退形态本就不同。
 *
 * 2. **两边都是 NONE(tab↔tab)→ crossfade。** 与栈内容无关,行为确定。
 *
 * 3. **其余(NONE↔PUSH / PUSH↔PUSH)→ 横向推入,方向由 [back] 决定。**
 *    前进新页从右进、旧页左移 1/3 视差;返回方向镜像。
 *
 * @param back 是否为返回(pop)。仅影响 PUSH 的位移方向;
 *  OVERLAY 与 NONE(crossfade)自身对称,但 OVERLAY 的进/出形态不同(见规则 1)。
 */
fun pageTransition(enter: PageNavStyle, exit: PageNavStyle, back: Boolean = false): ContentTransform = when {
    // ===== 规则 1:任意一边是 OVERLAY → scale/fade,绝不 slide =====
    // 进入 OVERLAY:新页 scale up 浮现;下层页保持可见(仅极轻缩放"被压"),
    // 不 fade——避免 Bug(下层页消失留空白闪烁)。
    enter == PageNavStyle.OVERLAY ->
        (fadeIn(tween(Motion.MEDIUM, easing = Motion.EmphasizedDecel)) +
            scaleIn(initialScale = 0.96f, animationSpec = Motion.DefaultSpring)) togetherWith
            scaleOut(targetScale = 0.98f, animationSpec = tween(Motion.MEDIUM, easing = Motion.EmphasizedAccel))
    // 离开 OVERLAY(无论返回到 root 还是 PUSH 页)。上层 OVERLAY scale down 退场,
    // 下层页 fade in 揭示。解决 Bug(Web→PUSH 页返回时 WebView 被 slide 撕裂)。
    exit == PageNavStyle.OVERLAY ->
        fadeIn(tween(Motion.SHORT, easing = Motion.EmphasizedDecel)) togetherWith
            (fadeOut(tween(Motion.MEDIUM, easing = Motion.EmphasizedAccel)) +
                scaleOut(targetScale = 0.96f, animationSpec = Motion.DefaultSpring))

    // ===== 规则 2:tab↔tab → crossfade =====
    enter == PageNavStyle.NONE && exit == PageNavStyle.NONE ->
        fadeIn(tween(Motion.SHORT, easing = Motion.EmphasizedDecel)) togetherWith
            fadeOut(tween(Motion.SHORT, easing = Motion.EmphasizedAccel))

    // ===== 规则 3:PUSH 类横向推入,方向由 back 决定 =====
    // 返回:新页(上级)从左滑入,旧页右移 1/3 视差。
    back -> slideInHorizontally(
        initialOffsetX = { -it },
        animationSpec = tween(Motion.MEDIUM, easing = Motion.EmphasizedDecel)
    ) togetherWith slideOutHorizontally(
        targetOffsetX = { it / 3 },
        animationSpec = tween(Motion.MEDIUM, easing = Motion.EmphasizedDecel)
    )
    // 前进:新页从右滑入,旧页左移 1/3 视差。
    else -> slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = tween(Motion.MEDIUM, easing = Motion.EmphasizedDecel)
    ) togetherWith slideOutHorizontally(
        targetOffsetX = { -it / 3 },
        animationSpec = tween(Motion.MEDIUM, easing = Motion.EmphasizedDecel)
    )
}
