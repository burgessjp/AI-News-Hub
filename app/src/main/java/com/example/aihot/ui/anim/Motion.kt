package com.example.aihot.ui.anim

import androidx.activity.BackEventCompat
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * 集中定义动画规范,避免 magic number 散落。
 * 参考 material-3-skill motion 章节:MD3 emphasized 缓动。
 *
 * 缓动体系:常规转场只用 [tween] + MD3 emphasized,不用 spring;
 * 唯一例外是预测返回手势([predictivePopTransition])——为与手指 1:1 跟手
 * 必须用 [LinearEasing],并引入 scaleOut(对齐官方 Navigation3 默认规格)。
 * 常规页面转场一律位移 + 淡入淡出组合(表达导航纵深),不用 scale。
 */
object Motion {
    // ===== Duration(毫秒)=====
    const val SHORT = 150
    const val MEDIUM = 300

    /** 页面级转场(全屏推入/退出位移)时长。 */
    const val LONG = 350

    // ===== Easing(MD3 emphasized cubic-bezier 近似)=====
    // Decel 用于"进入"(快起慢收);Accel 用于"退出"(慢起快收)。
    val EmphasizedDecel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

// ===== 页面转场内淡化时长(仅转场工厂使用)=====
// 入场淡化快、退场/显露淡化慢:新页面先显形,下层页面留余韵,叠加出纵深。
private const val FADE_FAST_MS = 200
private const val FADE_SLOW_MS = 250

/**
 * 页面转场风格。每个页面在 Page 上声明一种风格,
 * [pageTransition] / [predictivePopTransition] 据此统一产出进/出 [ContentTransform]。
 *
 * 添加新页面时默认即 [PUSH](横向推入),绝大多数二级页无需单独声明。
 *
 * @property PUSH 普通二级页(Search / Settings / Detail 等):横向位移 + 淡化。
 * @property FADE 含 AndroidView 的页(仅 Web):纯淡入淡出,永不位移、不缩放。
 *  WebView 对 translation/scale 撕裂,但 alpha 安全。只要它参与转场(进或出),
 *  整个转场走 fade,从根源杜绝撕裂。
 * @property NONE tab 根页之间切换:带 stagger 的 crossfade(防中间变暗)。
 */
enum class PageNavStyle { PUSH, FADE, NONE }

/**
 * 统一的页面转场工厂(常规导航:push / 返回键 / 顶栏返回 / 切 tab)。
 * 预测返回手势不走这里,见 [predictivePopTransition]。
 *
 * 调用方告知 [enter]/[exit] 两个页面的 [PageNavStyle] 与是否为返回([back]),
 * 由此函数集中产出进/出动画对,实现"一处配置,处处生效"。
 *
 * ## 决策规则(按优先级,first match wins)
 *
 * 1. **任意一边是 FADE → 双向 fade。**
 *    仅 Web 含 AndroidView,位移/缩放会撕裂 WebView,但 alpha 安全。
 *
 * 2. **两边都是 NONE(tab↔tab)→ 带 stagger 的 crossfade。**
 *    旧页先退(150ms Accel)、新页稍迟后进(300ms Decel)——错峰避免两者在
 *    ~50% 透明度时叠加成"中间变暗"。纯 fade,无位移。
 *
 * 3. **其余(NONE↔PUSH / PUSH↔PUSH)→ 横向推入 + 淡化,方向由 [back] 决定。**
 *    前进:新页从右滑入并快速淡入,旧页左移 1/3 视差并慢淡出(下层余韵);
 *    返回:当前页(退出)在最上层整体滑出右缘并快速淡出,上一级页面从左侧
 *    1/4 处慢淡入被"显露"(targetContentZIndex = -1f 保证退出页在上层)。
 *    淡化提供纵深线索,避免两个同色 surface 纯位移时"一块平板挪动"的观感。
 *
 * @param back 是否为返回(pop)。仅影响 PUSH 的位移方向与层级;
 *  FADE 与 NONE 自身对称,但 stagger 的进退时长不同(见规则 2)。
 */
fun pageTransition(enter: PageNavStyle, exit: PageNavStyle, back: Boolean = false): ContentTransform = when {
    // ===== 规则 1:任意一边是 FADE → 双向 fade(WebView 安全)=====
    enter == PageNavStyle.FADE || exit == PageNavStyle.FADE ->
        fadeIn(tween(FADE_SLOW_MS, easing = Motion.EmphasizedDecel)) togetherWith
            fadeOut(tween(FADE_SLOW_MS, easing = Motion.EmphasizedAccel))

    // ===== 规则 2:tab↔tab → 带 stagger 的 crossfade =====
    enter == PageNavStyle.NONE && exit == PageNavStyle.NONE ->
        // 新页稍迟进入(避免与旧页同时半透明),用 startDelay 错峰。
        fadeIn(tween(Motion.MEDIUM, delayMillis = 40, easing = Motion.EmphasizedDecel)) togetherWith
            fadeOut(tween(Motion.SHORT, easing = Motion.EmphasizedAccel))

    // ===== 规则 3:PUSH 类横向推入 + 淡化,方向由 back 决定 =====
    // 返回:退出页在上层滑出右缘并快速淡出;上一级页面从左侧 1/4 处慢淡入被显露。
    back -> ContentTransform(
        targetContentEnter = slideInHorizontally(
            initialOffsetX = { -it / 4 },
            animationSpec = tween(Motion.LONG, easing = Motion.EmphasizedDecel)
        ) + fadeIn(tween(FADE_SLOW_MS, easing = Motion.EmphasizedDecel)),
        initialContentExit = slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(Motion.LONG, easing = Motion.EmphasizedDecel)
        ) + fadeOut(tween(FADE_FAST_MS, easing = Motion.EmphasizedAccel)),
        targetContentZIndex = -1f
    )
    // 前进:新页从右滑入并快速淡入;旧页左移 1/3 视差并慢淡出。
    else -> slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = tween(Motion.LONG, easing = Motion.EmphasizedDecel)
    ) + fadeIn(tween(FADE_FAST_MS, easing = Motion.EmphasizedDecel)) togetherWith
        slideOutHorizontally(
            targetOffsetX = { -it / 3 },
            animationSpec = tween(Motion.LONG, easing = Motion.EmphasizedDecel)
        ) + fadeOut(tween(FADE_SLOW_MS, easing = Motion.EmphasizedDecel))
}

/**
 * 预测返回手势专用转场工厂(对齐官方 Navigation3 默认预测返回规格)。
 *
 * 与 [pageTransition] 的关键差异:
 *
 * - **全程 [LinearEasing]**:`SeekableTransitionState.seekTo(fraction)` 把 fraction
 *   映射为动画播放时间,播放值再过缓动曲线——若用 emphasized,手指拖 10% 页面
 *   已跑约 30%(橡皮筋感)。线性缓动保证动画进度与手指严格 1:1。
 * - **方向感知**:按 [swipeEdge] 把退出页滑向手势起始一侧边缘(左缘手势向右滑出,
 *   右缘手势向左滑出),避免"手指向左、页面却向右"的违和。
 * - **scaleOut**:退出页滑出的同时缩至 0.9 并淡出,下级页面淡入——"剥走当前页"
 *   的观感。比 Nav3 默认的 0.7 收敛(0.7 更像退回桌面,页面级导航偏夸张)。
 * - 松手完成:`animateTo(to)` 沿同一段 transition 播完剩余(行程短,线性可接受);
 *   中途取消:`animateTo(from)` 原路倒放(天然回弹,无需另写回弹动画)。
 *
 * @param swipeEdge [BackEventCompat.EDGE_LEFT] / [BackEventCompat.EDGE_RIGHT]。
 */
fun predictivePopTransition(
    enter: PageNavStyle,
    exit: PageNavStyle,
    swipeEdge: Int
): ContentTransform {
    // ===== 规则 1:任意一边是 FADE → 双向线性 fade(WebView 安全且跟手)=====
    if (enter == PageNavStyle.FADE || exit == PageNavStyle.FADE) {
        return fadeIn(tween(Motion.MEDIUM, easing = LinearEasing)) togetherWith
            fadeOut(tween(Motion.MEDIUM, easing = LinearEasing))
    }
    // ===== 规则 2:退出页滑向手势一侧边缘,缩小+淡出;下级页面从对侧 1/4 处淡入 =====
    // direction = 1:左缘手势,退出页向右滑出;-1:右缘手势,退出页向左滑出。
    val direction = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1 else 1
    return ContentTransform(
        targetContentEnter = slideInHorizontally(
            initialOffsetX = { -direction * it / 4 },
            animationSpec = tween(Motion.MEDIUM, easing = LinearEasing)
        ) + fadeIn(tween(Motion.MEDIUM, easing = LinearEasing)),
        initialContentExit = slideOutHorizontally(
            targetOffsetX = { direction * it },
            animationSpec = tween(Motion.MEDIUM, easing = LinearEasing)
        ) + scaleOut(
            targetScale = 0.9f,
            animationSpec = tween(Motion.MEDIUM, easing = LinearEasing)
        ) + fadeOut(tween(Motion.MEDIUM, easing = LinearEasing)),
        targetContentZIndex = -1f
    )
}
