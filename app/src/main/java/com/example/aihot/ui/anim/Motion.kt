package com.example.aihot.ui.anim

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * 集中定义动画规范,避免 magic number 散落。
 * 参考 material-3-skill motion 章节:MD3 emphasized 缓动。
 *
 * 本文件刻意只保留 [tween] 缓动体系——不用 spring,不用 scale。
 * 页面转场一律位移或淡入淡出,风格统一、干脆、可预期。
 */
object Motion {
    // ===== Duration(毫秒)=====
    const val SHORT = 150
    const val MEDIUM = 300

    // ===== Easing(MD3 emphasized cubic-bezier 近似)=====
    // Decel 用于"进入"(快起慢收);Accel 用于"退出"(慢起快收)。
    val EmphasizedDecel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

/**
 * 页面转场风格。每个页面在 [PageNavStyle] 枚举里声明一种风格,
 * [pageTransition] 据此统一产出一对进/出 [ContentTransform]。
 *
 * 添加新页面时默认即 [PUSH](横向推入),绝大多数二级页无需单独声明。
 *
 * @property PUSH 普通二级页(Search / Settings / Detail 等):纯横向位移推入,
 *  无 fade、无 scale——iOS 式实心推入,清晰干脆。
 * @property FADE 含 AndroidView 的页(仅 Web):纯淡入淡出,永不位移。
 *  WebView 对 translation 撕裂,但 alpha 安全。只要它参与转场(进或出),
 *  整个转场走 fade,从根源杜绝撕裂。
 * @property NONE tab 根页之间切换:带 stagger 的 crossfade(防中间变暗)。
 */
enum class PageNavStyle { PUSH, FADE, NONE }

/**
 * 统一的页面转场工厂。
 *
 * 调用方告知 [enter]/[exit] 两个页面的 [PageNavStyle] 与是否为返回([back]),
 * 由此函数集中产出进/出动画对,实现"一处配置,处处生效"。
 *
 * ## 决策规则(按优先级,first match wins)
 *
 * 1. **任意一边是 FADE → 双向 fade。**
 *    仅 Web 含 AndroidView,位移会撕裂 WebView,但 alpha 安全。
 *    进/出皆用 tween 缓动,不位移、不缩放——视觉上像一层纱揭起,而非滑动。
 *    - 前进:新页 fade in,旧页 fade out 同步。
 *    - 返回:同上(fade 自身对称)。
 *
 * 2. **两边都是 NONE(tab↔tab)→ 带 stagger 的 crossfade。**
 *    旧页先退(160ms Accel)、新页稍迟后进(220ms Decel)——错峰避免两者在
 *    ~50% 透明度时叠加成"中间变暗"。纯 fade,无位移。
 *
 * 3. **其余(NONE↔PUSH / PUSH↔PUSH)→ 横向推入,方向由 [back] 决定。**
 *    前进:新页从右进、旧页左移 1/3 视差;返回方向镜像。
 *    纯位移,无 fade、无 scale——实心推入,所见即所得。
 *
 * @param back 是否为返回(pop)。仅影响 PUSH 的位移方向;
 *  FADE 与 NONE 自身对称,但 stagger 的进退时长不同(见规则 2)。
 */
fun pageTransition(enter: PageNavStyle, exit: PageNavStyle, back: Boolean = false): ContentTransform = when {
    // ===== 规则 1:任意一边是 FADE → 双向 fade(WebView 安全)=====
    enter == PageNavStyle.FADE || exit == PageNavStyle.FADE ->
        fadeIn(tween(Motion.MEDIUM, easing = Motion.EmphasizedDecel)) togetherWith
            fadeOut(tween(Motion.MEDIUM, easing = Motion.EmphasizedAccel))

    // ===== 规则 2:tab↔tab → 带 stagger 的 crossfade =====
    enter == PageNavStyle.NONE && exit == PageNavStyle.NONE ->
        // 新页稍迟进入(避免与旧页同时半透明),用 startDelay 错峰。
        fadeIn(tween(Motion.MEDIUM, delayMillis = 40, easing = Motion.EmphasizedDecel)) togetherWith
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
