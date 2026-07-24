package com.peng.ainewshub.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * MD3 Type Scale — 基准字体 Inter(参考 "Synthetic Intelligence News" 设计系统)。
 *
 * Inter 是设计系统指定字体,中性几何 + 紧字距,标题加 -0.5sp 字距呼应"权威且现代"。
 * 继承 MD3 默认值的档位(display/headline)一并替换为 Inter,保证全 App 字体统一;
 * 这些继承档的字号/行高/字重保持 MD3 默认,只换字体族。
 *
 * 未在下面显式声明的档位(displayLarge 等)用 [DefaultWithInter],即 MD3 默认 metric
 * 套 Inter 字体族 —— 避免不同字体混排(如 display 仍 Roboto、title 已 Inter)。
 */
private val Default = Typography()

/** MD3 默认 metric 全档替换为 Inter,供 display/headline 等未精调档使用。 */
private val DefaultWithInter = Typography(
    displayLarge = Default.displayLarge.copy(fontFamily = InterFontFamily),
    displayMedium = Default.displayMedium.copy(fontFamily = InterFontFamily),
    displaySmall = Default.displaySmall.copy(fontFamily = InterFontFamily),
    headlineLarge = Default.headlineLarge.copy(fontFamily = InterFontFamily),
    headlineMedium = Default.headlineMedium.copy(fontFamily = InterFontFamily),
    headlineSmall = Default.headlineSmall.copy(fontFamily = InterFontFamily),
    bodySmall = Default.bodySmall.copy(fontFamily = InterFontFamily),
    labelLarge = Default.labelLarge.copy(fontFamily = InterFontFamily)
)

val AppTypography = Typography(
    displayLarge = DefaultWithInter.displayLarge,
    displayMedium = DefaultWithInter.displayMedium,
    displaySmall = DefaultWithInter.displaySmall,
    headlineLarge = DefaultWithInter.headlineLarge,
    headlineMedium = DefaultWithInter.headlineMedium,
    headlineSmall = DefaultWithInter.headlineSmall,
    titleLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp    // 紧字距,呼应设计系统 headline 紧凑现代感
    ),
    titleMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.3.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,    // 行距更舒展
        letterSpacing = 0.2.sp
    ),
    bodySmall = DefaultWithInter.bodySmall,
    labelLarge = DefaultWithInter.labelLarge,
    labelMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp  // chip 文字更精致
    ),
    labelSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
