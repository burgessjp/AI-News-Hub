package com.example.aihot.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 骨架屏组件 — 加载时的 shimmer 占位,提升感知性能。
 *
 * shimmer 效果:base 色块上滑过高光渐变带。
 */

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 6.dp
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val cs = MaterialTheme.colorScheme
    val baseColor = cs.surfaceContainerHigh
    val highlightColor = cs.surfaceContainerHighest

    val brush = Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(translateAnim * 300f - 300f, 0f),
        end = Offset(translateAnim * 300f, 0f)
    )

    Box(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius))
            .background(brush)
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

/** 一组卡片骨架,通常用于列表加载占位。 */
@Composable
fun NewsCardSkeletonList(
    count: Int = 4,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        repeat(count) {
            NewsCardSkeleton()
            Spacer(Modifier.height(12.dp))
        }
    }
}
