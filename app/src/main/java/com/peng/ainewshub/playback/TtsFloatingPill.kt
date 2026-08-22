package com.peng.ainewshub.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.anim.Motion
import com.peng.ainewshub.ui.components.BottomBarPillHeight
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 浮窗与屏幕边缘的最小留白(停泊/吸附的边距,兼作拖动 clamp 的四周边界)。 */
private val PillEdgePadding = 16.dp

/** 吸附缩小后的悬浮球尺寸。 */
private val DockBallSize = 48.dp

/** 浮窗与药丸底栏的间隙(停泊位与底栏顶缘的恒定间距,不随导航栏 inset 变化)。 */
private val PillDockGap = 12.dp

/**
 * 浮窗默认停泊位距系统导航栏顶的距离(不含 inset,与底栏定位同基准):
 * 底栏自身 margin 16dp + 药丸高 [BottomBarPillHeight] + [PillDockGap]。
 * 与底栏的「navigationBarsPadding + 16dp」对齐后,手势/三键导航下都不会压到底栏。
 */
private val PillDockBottomPadding = 16.dp + BottomBarPillHeight + PillDockGap

/** 文本区(进度 + 标题)的最大宽度:限制药丸总宽,小屏不溢出。 */
private val PillTextMaxWidth = 128.dp

/** 药丸整体最大宽度兜底(文本 + 四个 36dp 按钮 + 内边距)。 */
private val PillMaxWidth = 340.dp

/** [Offset] 的 rememberSaveable Saver(屏幕绝对位置跨页面转场/进程死亡恢复)。 */
private val OffsetSaver = Saver<Offset, ArrayList<Any>>(
    save = { arrayListOf(it.x, it.y) },
    restore = { Offset(it[0] as Float, it[1] as Float) }
)

/**
 * 语音速报应用内浮窗(迷你播放条 + 边缘吸附悬浮球) —— 播放期间悬浮于任意页面
 * (含二级页/WebView 页)之上,展示「第 N/M 条 + 标题」并提供上一条/播放暂停/
 * 下一条/停止控制,与通知栏 action 同一通路([TtsPlaybackService] companion 便捷方法)。
 *
 * 定位采用屏幕绝对坐标(调用方传 `Modifier.align(Alignment.TopStart)`,组件自身
 * offset 即左上角位置),默认停在屏幕右下、药丸底栏上方(与底栏同走 navigationBars
 * 基准,任何导航栏模式下都恒定留 [PillDockGap] 间隙):
 *  - 拖动:整条可拖,clamp 在屏幕内(顶部让开状态栏、四边留 [PillEdgePadding]);
 *  - 吸附:松手后按浮窗中心落点判断左右半屏,原地收拢成 [DockBallSize] 悬浮球
 *    并滑动贴边(emphasized 减速滑动);吸附态点击球展开、拖动球也直接展开;
 *  - 展开:球变条时若右缘出屏,自动滑回屏幕内。
 *
 * 状态来源:[TtsPlaybackService.state] —— active=false(停止/服务被杀)时向下滑出。
 * 视觉与浮动药丸底栏同款悬浮语言:近实底 + 3dp 浮起阴影 + 玻璃边缘描边。
 *
 * @param modifier 调用方定位用(须含 BoxScope 的 align(TopStart))
 */
@Composable
fun TtsFloatingPill(modifier: Modifier = Modifier) {
    val playback by TtsPlaybackService.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    // 边缘吸附缩小态(悬浮球):点击展开、拖动展开
    var docked by rememberSaveable { mutableStateOf(false) }
    // 浮窗左上角的屏幕绝对位置(px);Animatable 支撑拖动 snap 与吸附/回弹 animateTo,
    // 实时回写 savedPos 供进程死亡恢复(rememberSaveable 挂在根 overlay 层,跨页面转场存活;
    // 轻量 UI 偏好不进 DataStore)。初始值兜底 (0,0):Unspecified(NaN) 会让布局期
    // offset{} 的 roundToInt 抛「Cannot round NaN」,首帧位置由下方初始化 effect 落位
    // (enter 淡入首帧透明,不会闪现在左上角)
    var savedPos by rememberSaveable(stateSaver = OffsetSaver) { mutableStateOf(Offset.Unspecified) }
    val pos = remember {
        Animatable(if (savedPos.isSpecified) savedPos else Offset.Zero, Offset.VectorConverter)
    }
    // 位置是否已初始化(首次显示落默认停泊位;进程恢复时跳过)
    var initialized by rememberSaveable { mutableStateOf(false) }
    // 吸附/展开修正动画的 job:新手势开始时取消,避免动画与拖动抢位置
    var animJob by remember { mutableStateOf<Job?>(null) }
    // 当前形态的实测尺寸(条↔球切换后随布局更新)
    var pillSize by remember { mutableStateOf(IntSize.Zero) }
    // 球展开为条后的一次性边界修正标记(条更宽,吸右时右缘可能出屏)
    var expandAdjustPending by remember { mutableStateOf(false) }

    // 初始化完成后回写位置(供 saver 恢复);初始化前不写,避免默认停泊位被 (0,0) 覆盖
    LaunchedEffect(Unit) {
        snapshotFlow { pos.value }.collect { if (initialized) savedPos = it }
    }

    AnimatedVisibility(
        visible = playback.active,
        enter = fadeIn(tween(Motion.SHORT, easing = Motion.EmphasizedDecel)) +
            slideInVertically(
                initialOffsetY = { it / 2 },
                animationSpec = tween(Motion.MEDIUM, easing = Motion.EmphasizedDecel)
            ),
        exit = fadeOut(tween(Motion.SHORT, easing = Motion.EmphasizedAccel)) +
            slideOutVertically(
                targetOffsetY = { it / 2 },
                animationSpec = tween(Motion.SHORT, easing = Motion.EmphasizedAccel)
            ),
        modifier = modifier
            .offset { IntOffset(pos.value.x.roundToInt(), pos.value.y.roundToInt()) }
            .onGloballyPositioned { pillSize = it.size }
    ) {
        BoxWithConstraints {
            // 根 Box fillMaxSize,此处约束即全屏尺寸;旋转后以尺寸为 key 重启手势处理
            val screenW = constraints.maxWidth.toFloat()
            val screenH = constraints.maxHeight.toFloat()
            val marginPx = with(density) { PillEdgePadding.toPx() }
            val ballPx = with(density) { DockBallSize.toPx() }
            val bottomInsetPx = with(density) {
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().toPx()
            }
            val topLimitPx = with(density) {
                WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx() + PillEdgePadding.toPx()
            }

            // 首次显示:落默认停泊位(右下、底栏上方)。首帧组件透明(enter 淡入),
            // 布局尺寸就绪后再定位,不会闪现在 (0,0)
            LaunchedEffect(pillSize) {
                if (!initialized && pillSize != IntSize.Zero) {
                    initialized = true
                    if (savedPos.isUnspecified) {
                        pos.snapTo(
                            Offset(
                                screenW - marginPx - pillSize.width,
                                screenH - bottomInsetPx -
                                    with(density) { PillDockBottomPadding.toPx() } - pillSize.height
                            )
                        )
                    }
                }
            }

            // 屏幕尺寸变化(旋转/分屏)后,记住的绝对坐标可能落到屏幕外(不可见也不可触):
            // 约束变化时校验一次,只救「真出屏」的硬伤,不干预正常拖动/吸附动画
            LaunchedEffect(screenW, screenH, pillSize) {
                if (!initialized || pillSize == IntSize.Zero) return@LaunchedEffect
                val x = pos.value.x.coerceIn(0f, (screenW - pillSize.width).coerceAtLeast(0f))
                val y = pos.value.y.coerceIn(
                    topLimitPx.coerceAtMost((screenH - pillSize.height).coerceAtLeast(0f)),
                    (screenH - pillSize.height).coerceAtLeast(0f)
                )
                if (x != pos.value.x || y != pos.value.y) pos.snapTo(Offset(x, y))
            }

            // 球展开为条后的一次性修正:条比球宽,吸右展开时右缘出屏 → 滑回屏幕内
            LaunchedEffect(pillSize, expandAdjustPending) {
                if (!expandAdjustPending || docked || pillSize == IntSize.Zero) return@LaunchedEffect
                expandAdjustPending = false
                val maxX = screenW - marginPx - pillSize.width
                if (pos.value.x > maxX) {
                    animJob?.cancel()
                    animJob = scope.launch {
                        pos.animateTo(
                            Offset(maxX.coerceAtLeast(marginPx), pos.value.y),
                            tween(Motion.SHORT, easing = Motion.EmphasizedDecel)
                        )
                    }
                }
            }

            val widgetCd = stringResource(R.string.tts_widget_cd)
            Surface(
                modifier = Modifier
                    .widthIn(max = PillMaxWidth)
                    .semantics { contentDescription = widgetCd }
                    // 整条/整球可拖:与内部按钮的 click 靠 touch slop 自然区分
                    // (轻点落在按钮上,移动超 slop 才进入拖动)
                    .pointerInput(screenW, screenH) {
                        detectDragGestures(
                            onDragStart = {
                                // 新手势优先:取消进行中的吸附/修正动画
                                animJob?.cancel()
                                // 从吸附态拖动 = 直接展开(条向右伸展),松手后再按落点吸附
                                if (docked) {
                                    docked = false
                                    expandAdjustPending = false
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val w = pillSize.width
                                val h = pillSize.height
                                if (w == 0 || h == 0) return@detectDragGestures
                                scope.launch {
                                    pos.snapTo(
                                        Offset(
                                            x = (pos.value.x + dragAmount.x)
                                                .coerceIn(marginPx, screenW - marginPx - w),
                                            y = (pos.value.y + dragAmount.y)
                                                .coerceIn(topLimitPx, screenH - marginPx - h)
                                        )
                                    )
                                }
                            },
                            onDragEnd = {
                                // 按浮窗中心落点判断吸附侧;原地收拢成球后滑动贴边
                                val toLeft = pos.value.x + pillSize.width / 2f < screenW / 2f
                                val collapseShift = pillSize.width - ballPx
                                animJob = scope.launch {
                                    if (!docked) {
                                        docked = true
                                        // 原地收拢:球落在原条的右端(左上角右移宽度差)
                                        pos.snapTo(Offset(pos.value.x + collapseShift, pos.value.y))
                                    }
                                    val targetX = if (toLeft) marginPx else screenW - marginPx - ballPx
                                    val targetY = pos.value.y
                                        .coerceIn(topLimitPx, screenH - marginPx - ballPx)
                                    pos.animateTo(
                                        Offset(targetX, targetY),
                                        tween(Motion.MEDIUM, easing = Motion.EmphasizedDecel)
                                    )
                                }
                            },
                            // 手势被取消(罕见):保持原地,不吸附
                            onDragCancel = { }
                        )
                    },
                shape = CircleShape,
                color = cs.surfaceContainer.copy(alpha = AppAlpha.bottomBarSurface),
                shadowElevation = 3.dp,
                border = BorderStroke(1.dp, Color.White.copy(alpha = AppAlpha.glassEdge))
            ) {
                if (docked) {
                    // 吸附态悬浮球:点击展开为完整控制条
                    val expandCd = stringResource(R.string.tts_widget_expand)
                    Box(
                        modifier = Modifier
                            .size(DockBallSize)
                            .clip(CircleShape)
                            .clickable {
                                docked = false
                                expandAdjustPending = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = expandCd,
                            tint = cs.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 5.dp, bottom = 5.dp)
                    ) {
                        Column(modifier = Modifier.widthIn(max = PillTextMaxWidth)) {
                            // 单条播放(总览单段音频)没有序号与上一条/下一条概念,只显标题
                            if (playback.total > 1) {
                                Text(
                                    text = stringResource(R.string.tts_playing_index, playback.index + 1, playback.total),
                                    style = AppText.caption,
                                    color = cs.onSurfaceVariant
                                )
                            }
                            Text(
                                text = playback.title.ifBlank { stringResource(R.string.tts_notification_title) },
                                style = AppText.bodyCompact,
                                color = cs.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (playback.total > 1) {
                            PillIconButton(
                                icon = R.drawable.ic_tts_prev,
                                label = stringResource(R.string.tts_action_prev),
                                tint = cs.onSurfaceVariant
                            ) { TtsPlaybackService.prev(context) }
                        }
                        PillIconButton(
                            icon = if (playback.paused) R.drawable.ic_tts_play else R.drawable.ic_tts_pause,
                            label = stringResource(
                                if (playback.paused) R.string.tts_action_play else R.string.tts_action_pause
                            ),
                            tint = cs.onSurface
                        ) { TtsPlaybackService.playPause(context) }
                        if (playback.total > 1) {
                            PillIconButton(
                                icon = R.drawable.ic_tts_next,
                                label = stringResource(R.string.tts_action_next),
                                tint = cs.onSurfaceVariant
                            ) { TtsPlaybackService.next(context) }
                        }
                        PillIconButton(
                            icon = R.drawable.ic_tts_stop,
                            label = stringResource(R.string.tts_widget_close),
                            tint = cs.onSurfaceVariant
                        ) { TtsPlaybackService.stop(context) }
                    }
                }
            }
        }
    }
}

/** 药丸内紧凑控制按钮:36dp 触控位 + 18dp 图标(迷你播放条惯例,小于标准 48dp)。 */
@Composable
private fun PillIconButton(
    icon: Int,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}
