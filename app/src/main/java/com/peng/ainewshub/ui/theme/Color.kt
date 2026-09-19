package com.peng.ainewshub.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 配色方案 — 「纸墨日报」设计系统(v1.4.0 日刊化改版)。
 *
 * 报纸语言三要素:
 *  - 纸白 / 墨黑:正文与版面只用近中性墨色,不做彩色底衬
 *  - 报纸红(primary):唯一强调色 —— 突发标签、1-2 名排名数字、链接、选中态
 *  - 纸金(tertiary):次级强调(热度 / 新上榜),避免红字满屏
 *
 * 分隔线体系(组件层约定,颜色取自本表):
 *  - 双细线(报头/大节)与单粗线(分源区块起头)= onSurface / inverseSurface
 *  - 条目发丝线 = outlineVariant
 *
 * 不做动态取色与多皮肤:编辑色板是固定品牌资产,壁纸派生色会破坏纸墨身份。
 * 深色为「墨底」:背景近黑暖灰,文字纸白,报纸红提亮保证对比(AA)。
 */

// ===== Light(纸白 #FBFAF7 + 墨黑 + 报纸红)=====

val LightPrimary = Color(0xFFB93B1D)              // 报纸红:唯一强调色
val LightOnPrimary = Color(0xFFFFF8F4)
val LightPrimaryContainer = Color(0xFFD9633F)     // 红 chip / 标签底
val LightOnPrimaryContainer = Color(0xFFFFF8F4)

val LightSecondary = Color(0xFF57534A)            // 墨灰:选中态 / 次强调(深于 onSurfaceVariant)
val LightOnSecondary = Color(0xFFFBFAF7)
val LightSecondaryContainer = Color(0xFF78746A)
val LightOnSecondaryContainer = Color(0xFFFBFAF7)

val LightTertiary = Color(0xFF9C6B2F)             // 纸金:热度 / 涨跌第三色
val LightOnTertiary = Color(0xFFFFF8F4)
val LightTertiaryContainer = Color(0xFFB98A4A)
val LightOnTertiaryContainer = Color(0xFFFFF8F4)

val LightError = Color(0xFFB3261E)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFF9DECD)
val LightOnErrorContainer = Color(0xFF410E0B)

val LightBackground = Color(0xFFFBFAF7)           // 暖纸白
val LightOnBackground = Color(0xFF1B1A17)         // 墨黑
val LightSurface = Color(0xFFFBFAF7)
val LightOnSurface = Color(0xFF1B1A17)
val LightSurfaceVariant = Color(0xFFECE8DE)
val LightOnSurfaceVariant = Color(0xFF57534A)     // 次级文字:暖墨灰
val LightOutline = Color(0xFFB5B0A3)
val LightOutlineVariant = Color(0xFFE4E0D6)       // 发丝线 / 卡片描边

val LightSurfaceDim = Color(0xFFE6E1D4)
val LightSurfaceBright = Color(0xFFFBFAF7)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF5F2EA)
val LightSurfaceContainer = Color(0xFFF1EDE3)
val LightSurfaceContainerHigh = Color(0xFFECE8DE)
val LightSurfaceContainerHighest = Color(0xFFE6E1D4)

val LightInverseSurface = Color(0xFF2E2B26)
val LightInverseOnSurface = Color(0xFFF3F0E8)
val LightInversePrimary = Color(0xFFE07A56)

// ===== Dark(墨底 #1B1917 + 纸白文字 + 报纸红提亮)=====

val DarkPrimary = Color(0xFFE07A56)               // 报纸红提亮(墨底 AA)
val DarkOnPrimary = Color(0xFF2A0F06)
val DarkPrimaryContainer = Color(0xFF8F3A22)
val DarkOnPrimaryContainer = Color(0xFFF9E2D9)

val DarkSecondary = Color(0xFFC9C4B8)             // 纸灰:选中态(亮于 onSurfaceVariant)
val DarkOnSecondary = Color(0xFF26231E)
val DarkSecondaryContainer = Color(0xFF57534A)
val DarkOnSecondaryContainer = Color(0xFFF3F0E8)

val DarkTertiary = Color(0xFFD2AC72)              // 纸金提亮
val DarkOnTertiary = Color(0xFF2A2007)
val DarkTertiaryContainer = Color(0xFF7A5A2A)
val DarkOnTertiaryContainer = Color(0xFFF5E2C2)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF1B1917)            // 墨底,避免 OLED 纯黑
val DarkOnBackground = Color(0xFFE8E4DA)          // 纸白
val DarkSurface = Color(0xFF1B1917)
val DarkOnSurface = Color(0xFFE8E4DA)
val DarkSurfaceVariant = Color(0xFF2E2B26)
val DarkOnSurfaceVariant = Color(0xFFA8A294)
val DarkOutline = Color(0xFF8F8A7E)
val DarkOutlineVariant = Color(0xFF2E2B26)        // 发丝线(墨底上的暗线)

val DarkSurfaceDim = Color(0xFF1B1917)
val DarkSurfaceBright = Color(0xFF3A362F)
val DarkSurfaceContainerLowest = Color(0xFF141210)
val DarkSurfaceContainerLow = Color(0xFF201D19)
val DarkSurfaceContainer = Color(0xFF242119)
val DarkSurfaceContainerHigh = Color(0xFF2A2721)
val DarkSurfaceContainerHighest = Color(0xFF33302A)

val DarkInverseSurface = Color(0xFFE8E4DA)
val DarkInverseOnSurface = Color(0xFF2E2B26)
val DarkInversePrimary = Color(0xFFB93B1D)

val LightColors = lightColorScheme(
    primary = LightPrimary, onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer, onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary, onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer, onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary, onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer, onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError, onError = LightOnError,
    errorContainer = LightErrorContainer, onErrorContainer = LightOnErrorContainer,
    background = LightBackground, onBackground = LightOnBackground,
    surface = LightSurface, onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant, onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline, outlineVariant = LightOutlineVariant,
    surfaceDim = LightSurfaceDim, surfaceBright = LightSurfaceBright,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    inverseSurface = LightInverseSurface, inverseOnSurface = LightInverseOnSurface,
    inversePrimary = LightInversePrimary
)

val DarkColors = darkColorScheme(
    primary = DarkPrimary, onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer, onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary, onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer, onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary, onTertiary = DarkTertiary,
    tertiaryContainer = DarkTertiaryContainer, onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError, onError = DarkOnError,
    errorContainer = DarkErrorContainer, onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground, onBackground = DarkOnBackground,
    surface = DarkSurface, onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant, onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline, outlineVariant = DarkOutlineVariant,
    surfaceDim = DarkSurfaceDim, surfaceBright = DarkSurfaceBright,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    inverseSurface = DarkInverseSurface, inverseOnSurface = DarkInverseOnSurface,
    inversePrimary = DarkInversePrimary
)
