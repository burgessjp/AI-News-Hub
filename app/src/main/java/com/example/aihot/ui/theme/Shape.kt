package com.example.aihot.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * MD3 Shape Scale — 略大圆润,贴合"精致低对比"现代风。
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),       // chip / pill / 小按钮
    medium = RoundedCornerShape(18.dp),      // 卡片(略大圆润)
    large = RoundedCornerShape(24.dp),       // 大卡片 / FAB
    extraLarge = RoundedCornerShape(32.dp)
)
