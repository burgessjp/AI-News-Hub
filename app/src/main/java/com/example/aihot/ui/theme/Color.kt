package com.example.aihot.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 配色方案 — 对齐 aihot.virxact.com 网站的视觉风格。
 *
 * 主色板提取自网站 CSS:
 *  - 强调色 accent: #22d3ee (cyan-400) — 网站主要 accent
 *  - 浅色背景: #fafbfc / #f8fafc
 *  - 深色背景: #060814 (近黑)
 *
 * 经 Material Theme Builder 用 cyan 种子色生成完整 MD3 色调阶梯,
 * 严格遵循 tonal pairing(on-X 与对应容器色成对使用)。
 */

// ===== Light(网站浅色版,#fafbfc 背景 + cyan accent)=====

val LightPrimary = Color(0xFF006A6B)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFF6FF7F8)
val LightOnPrimaryContainer = Color(0xFF002020)

val LightSecondary = Color(0xFF4A6367)        // slate 偏冷中性
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFCDE8EC)
val LightOnSecondaryContainer = Color(0xFF051F23)

val LightTertiary = Color(0xFF006A6B)         // 与 primary 同源,用于"精选"等强调
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFF6FF7F8)
val LightOnTertiaryContainer = Color(0xFF002020)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

val LightBackground = Color(0xFFFAFBFC)        // 对齐网站
val LightOnBackground = Color(0xFF191C1D)
val LightSurface = Color(0xFFFAFBFC)
val LightOnSurface = Color(0xFF191C1D)
val LightSurfaceVariant = Color(0xFFDAE4E5)
val LightOnSurfaceVariant = Color(0xFF3F494A)
val LightOutline = Color(0xFF6F797A)
val LightOutlineVariant = Color(0xFFE5E7EB)         // 卡片描边(github light 边框色)

val LightSurfaceDim = Color(0xFFD9DBDC)
val LightSurfaceBright = Color(0xFFFAFBFC)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFFFFFFF)        // 卡片白底(靠描边分层)
val LightSurfaceContainer = Color(0xFFF1F5F9)           // slate-100
val LightSurfaceContainerHigh = Color(0xFFE8EDF1)
val LightSurfaceContainerHighest = Color(0xFFDDE3E8)

val LightInverseSurface = Color(0xFF2D3132)
val LightInverseOnSurface = Color(0xFFEFF1F1)
val LightInversePrimary = Color(0xFF4ADADA)

// ===== Dark(网站深色版,#060814 背景 + cyan accent)=====

val DarkPrimary = Color(0xFF4ADADA)            // cyan accent 主色
val DarkOnPrimary = Color(0xFF003738)
val DarkPrimaryContainer = Color(0xFF004F50)
val DarkOnPrimaryContainer = Color(0xFF6FF7F8)

val DarkSecondary = Color(0xFFB1CCD0)
val DarkOnSecondary = Color(0xFF1B3438)
val DarkSecondaryContainer = Color(0xFF324B4E)
val DarkOnSecondaryContainer = Color(0xFFCDE8EC)

val DarkTertiary = Color(0xFF4ADADA)
val DarkOnTertiary = Color(0xFF003738)
val DarkTertiaryContainer = Color(0xFF004F50)
val DarkOnTertiaryContainer = Color(0xFF6FF7F8)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF0E1117)         // 抬高亮度,避免 OLED 纯黑洞
val DarkOnBackground = Color(0xFFE6EDF3)       // github 文字色,更亮
val DarkSurface = Color(0xFF0E1117)
val DarkOnSurface = Color(0xFFE6EDF3)
val DarkSurfaceVariant = Color(0xFF3F494A)
val DarkOnSurfaceVariant = Color(0xFFB1BAC4)   // 次级文字更易读
val DarkOutline = Color(0xFF8B949E)
val DarkOutlineVariant = Color(0xFF30363D)        // 卡片描边(github dark 边框色)

val DarkSurfaceDim = Color(0xFF0E1117)
val DarkSurfaceBright = Color(0xFF2C3031)
val DarkSurfaceContainerLowest = Color(0xFF0E1117)
val DarkSurfaceContainerLow = Color(0xFF161B22)    // 卡片层(与 bg 拉开亮度差)
val DarkSurfaceContainer = Color(0xFF1C2128)
val DarkSurfaceContainerHigh = Color(0xFF21262D)
val DarkSurfaceContainerHighest = Color(0xFF2D333B)

val DarkInverseSurface = Color(0xFFE0E3E3)
val DarkInverseOnSurface = Color(0xFF2D3132)
val DarkInversePrimary = Color(0xFF006A6B)

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
    tertiary = DarkTertiary, onTertiary = DarkOnTertiary,
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
