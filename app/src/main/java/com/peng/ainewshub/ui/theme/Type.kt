package com.peng.ainewshub.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * MD3 Type Scale — 跟随系统默认字体,仅精调排版 metric。
 *
 * 不指定 fontFamily(即 null),全 App 文字走系统字体(Roboto / 厂商字体)。
 * 仅在产品语义需要处精调字号 / 行高 / 字重 / 字距,保持排版骨架稳定。
 *
 * 未在下面显式声明的档位(displayLarge 等)直接沿用 MD3 默认值 [Default]。
 */
private val Default = Typography()

val AppTypography = Typography(
    displayLarge = Default.displayLarge,
    displayMedium = Default.displayMedium,
    displaySmall = Default.displaySmall,
    headlineLarge = Default.headlineLarge,
    headlineMedium = Default.headlineMedium,
    headlineSmall = Default.headlineSmall,
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp    // 紧字距,呼应设计系统 headline 紧凑现代感
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.3.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,    // 行距更舒展
        letterSpacing = 0.2.sp
    ),
    bodySmall = Default.bodySmall,
    labelLarge = Default.labelLarge,
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp  // chip 文字更精致
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
