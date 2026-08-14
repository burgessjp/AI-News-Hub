package com.peng.ainewshub.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 骨架屏组件 — 加载时的 shimmer 占位,提升感知性能。
 *
 * shimmer 效果:base 色块上滑过高光渐变带。
 *
 * 动画实例约束:一屏骨架往往含几十个 ShimmerBox,若每盒各自创建无限动画,
 * 加载首帧会同时跑几十个 transition。故扫动进度经 [LocalShimmerProgress] 单点
 * 共享:骨架列表封装与整屏骨架外层包 [ShimmerHost],子树内所有盒子复用同一动画;
 * 未包宿主的散用盒子自动回退自建。进度值在 draw 阶段读取,动画逐帧只重绘不重组。
 */

/** shimmer 扫动进度,由 [ShimmerHost] 提供;null 表示无宿主,ShimmerBox 需自建动画。 */
private val LocalShimmerProgress = staticCompositionLocalOf<State<Float>?> { null }

/**
 * 骨架屏共享宿主:整个子树只创建一个 shimmer 无限动画。
 * 一屏内多处骨架应包在同一个宿主里(所有盒子同相位扫动,与原先各自建时的视觉一致)。
 */
@Composable
fun ShimmerHost(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalShimmerProgress provides rememberShimmerProgress()) {
        content()
    }
}

/** shimmer 扫动动画(-2f→2f 循环),宿主共享与散用兜底共用同一套参数。 */
@Composable
private fun rememberShimmerProgress(): State<Float> {
    val transition = rememberInfiniteTransition(label = "shimmer")
    return transition.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )
}

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 6.dp
) {
    val progress = LocalShimmerProgress.current ?: rememberShimmerProgress()
    val cs = MaterialTheme.colorScheme
    val colors = listOf(cs.surfaceContainerHigh, cs.surfaceContainerHighest, cs.surfaceContainerHigh)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .drawBehind {
                // 进度在 draw 阶段读取:共享动画逐帧只触发重绘,不产生重组
                val p = progress.value
                drawRect(
                    brush = Brush.linearGradient(
                        colors = colors,
                        start = Offset(p * 300f - 300f, 0f),
                        end = Offset(p * 300f, 0f)
                    )
                )
            }
    )
}

/**
 * 新闻列表行骨架 —— 匹配 NewsRow 扁平布局(左时间 + 右标题/摘要/来源)。
 */
@Composable
fun NewsCardSkeleton(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Top
    ) {
        // 左栏:时间
        ShimmerBox(modifier = Modifier.size(40.dp, 14.dp), cornerRadius = 4.dp)
        // 右栏
        Column(modifier = Modifier.weight(1f)) {
            // 标题(2 行)
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.95f).height(16.dp))
            Spacer(Modifier.height(6.dp))
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.65f).height(16.dp))
            Spacer(Modifier.height(8.dp))
            // 摘要(2 行)
            ShimmerBox(modifier = Modifier.fillMaxWidth().height(12.dp))
            Spacer(Modifier.height(4.dp))
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.85f).height(12.dp))
            Spacer(Modifier.height(10.dp))
            // 来源行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ShimmerBox(modifier = Modifier.size(140.dp, 12.dp), cornerRadius = 4.dp)
                ShimmerBox(modifier = Modifier.size(20.dp, 14.dp), cornerRadius = 4.dp)
            }
        }
    }
}

/** 一组卡片骨架,通常用于列表加载占位。整组共享一个 shimmer 动画(见 [ShimmerHost])。 */
@Composable
fun NewsCardSkeletonList(
    count: Int = 4,
    modifier: Modifier = Modifier
) {
    ShimmerHost {
        Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            repeat(count) {
                NewsCardSkeleton()
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/**
 * 排名行骨架 —— 匹配 Hub 四源屏(HN/GitHub/stormzhang/HF)的真实行结构:
 * 左 24dp 排名徽章 + 右侧标题两行 + meta 统计行(padding 18h/14v、间距 12dp 对齐
 * 各屏真实行),避免加载→内容切换时的结构跳变。
 */
@Composable
fun RankRowSkeleton(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Top
    ) {
        // 左栏:24dp 排名徽章(extraSmall 圆角)
        ShimmerBox(modifier = Modifier.size(24.dp), cornerRadius = 6.dp)
        // 右栏:标题两行 + meta 行(三个统计小块,占位 StatBadge 位)
        Column(modifier = Modifier.weight(1f)) {
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.95f).height(16.dp))
            Spacer(Modifier.height(6.dp))
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.65f).height(16.dp))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShimmerBox(modifier = Modifier.size(48.dp, 12.dp), cornerRadius = 4.dp)
                ShimmerBox(modifier = Modifier.size(40.dp, 12.dp), cornerRadius = 4.dp)
                ShimmerBox(modifier = Modifier.size(56.dp, 12.dp), cornerRadius = 4.dp)
            }
        }
    }
}

/**
 * 一组排名行骨架 —— Hub 五源屏等「徽章行」列表的加载占位。
 * 行间不再额外留空:真实行靠行内 vertical padding 呼吸,骨架保持同一节奏。
 * 整组共享一个 shimmer 动画(见 [ShimmerHost])。
 */
@Composable
fun RankRowSkeletonList(
    count: Int = 8,
    modifier: Modifier = Modifier
) {
    ShimmerHost {
        Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            repeat(count) {
                RankRowSkeleton()
            }
        }
    }
}
