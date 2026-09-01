package com.peng.ainewshub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 配色方案 — "Synthetic Intelligence News" 设计系统(参考 stitch_ai_news_hub)。
 *
 * 品牌由两色锚定:
 *  - Future Blue (primary #003ec7):信任、权威、链接
 *  - Intelligence Purple (secondary #6b38d4):AI、智能、洞察
 *
 * 蓝→紫渐变保留给 AI 特性(翻译、热点聚合等),不滥用。
 * 调性:冷调淡蓝白背景 + 深炭文字,Modern Corporate 精度 + 大留白。
 *
 * Light 令牌直接取自设计系统 frontmatter;Dark 由 fixed-dim / fixed-variant
 * 及 on-surface 系反推,保证深色下蓝紫主色依然可辨。
 */

// ===== Light(淡蓝白背景 #f9f9ff + Future Blue primary)=====

val LightPrimary = Color(0xFF003EC7)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFF0052FF)
val LightOnPrimaryContainer = Color(0xFFDFE3FF)

val LightSecondary = Color(0xFF6B38D4)            // Intelligence Purple — 独立副色,不再复用 primary
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFF8455EF)
val LightOnSecondaryContainer = Color(0xFFFFFBFF)

val LightTertiary = Color(0xFF952200)             // 暖色第三色,火焰分数/热度强调
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFBF3003)
val LightOnTertiaryContainer = Color(0xFFFFDDD5)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF93000A)

val LightBackground = Color(0xFFF9F9FF)            // 淡蓝白,设计系统基准背景
val LightOnBackground = Color(0xFF141B2B)
val LightSurface = Color(0xFFF9F9FF)
val LightOnSurface = Color(0xFF141B2B)
val LightSurfaceVariant = Color(0xFFDCE2F7)
val LightOnSurfaceVariant = Color(0xFF434656)
val LightOutline = Color(0xFF737688)
val LightOutlineVariant = Color(0xFFC3C5D9)        // 卡片描边

val LightSurfaceDim = Color(0xFFD3DAEF)
val LightSurfaceBright = Color(0xFFF9F9FF)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF1F3FF)
val LightSurfaceContainer = Color(0xFFE9EDFF)
val LightSurfaceContainerHigh = Color(0xFFE1E8FD)
val LightSurfaceContainerHighest = Color(0xFFDCE2F7)

val LightInverseSurface = Color(0xFF293040)
val LightInverseOnSurface = Color(0xFFEDF0FF)
val LightInversePrimary = Color(0xFFB7C4FF)

// ===== Dark(深炭背景 + 蓝/紫主色变亮以保证对比)=====

val DarkPrimary = Color(0xFFB7C4FF)                // primary-fixed-dim:深色下蓝主色
val DarkOnPrimary = Color(0xFF002C9A)
val DarkPrimaryContainer = Color(0xFF003EC7)
val DarkOnPrimaryContainer = Color(0xFFDFE3FF)

val DarkSecondary = Color(0xFFD0BCFF)              // secondary-fixed-dim:深色下紫主色
val DarkOnSecondary = Color(0xFF3C1A8E)
val DarkSecondaryContainer = Color(0xFF5516BE)
val DarkOnSecondaryContainer = Color(0xFFE9DDFF)

val DarkTertiary = Color(0xFFFFB4A1)               // tertiary-fixed-dim
val DarkOnTertiary = Color(0xFF5D1800)
val DarkTertiaryContainer = Color(0xFF891E00)
val DarkOnTertiaryContainer = Color(0xFFFFDBD2)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF11132A)             // 深炭蓝,避免 OLED 纯黑
val DarkOnBackground = Color(0xFFEDF0FF)
val DarkSurface = Color(0xFF11132A)
val DarkOnSurface = Color(0xFFEDF0FF)
val DarkSurfaceVariant = Color(0xFF434656)
val DarkOnSurfaceVariant = Color(0xFFC3C5D9)
val DarkOutline = Color(0xFF8D92AB)
val DarkOutlineVariant = Color(0xFF434656)         // 卡片描边

val DarkSurfaceDim = Color(0xFF11132A)
val DarkSurfaceBright = Color(0xFF373A55)
val DarkSurfaceContainerLowest = Color(0xFF0C0E22)
val DarkSurfaceContainerLow = Color(0xFF191C36)
val DarkSurfaceContainer = Color(0xFF1D2040)
val DarkSurfaceContainerHigh = Color(0xFF282B4A)
val DarkSurfaceContainerHighest = Color(0xFF33365A)

val DarkInverseSurface = Color(0xFFEDF0FF)
val DarkInverseOnSurface = Color(0xFF293040)
val DarkInversePrimary = Color(0xFF003EC7)

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

// ===== Mono 皮肤(「纸与墨」黑白原型风)=====
//
// 设计意图:纸面原型观感 —— 暖调纸白(浅色)/ 暖调墨黑(深色)打底,去色相、
// 靠明度阶梯分层;primary 即墨色(浅色=墨黑 / 深色=纸白),按钮/选中 chip 呈
// 实心黑(白)胶囊的「贴纸」感,outlineVariant 压得比常规深让卡片描边可见
// (原型风的细黑描边语言)。整体带一丝暖灰(黄灰相),不是中性冷灰。
// AppAlpha 系洗色(cs.primary.copy(alpha))随之自然变灰调。
// error 特意保留经典红:错误语义不随皮肤消失。结构与上方 Classic 两套逐槽对应,
// 选中逻辑见 ui/theme/Theme.kt(明暗仍跟随用户 ThemeMode)。

/** Mono · 浅色 —— 纸面:暖纸白底 + 墨黑 primary,白卡片浮层,描边可见。 */
val MonoLightColors = lightColorScheme(
    primary = Color(0xFF161613), onPrimary = Color(0xFFFBFAF6),
    primaryContainer = Color(0xFF2A2A26), onPrimaryContainer = Color(0xFFF0EFE8),
    secondary = Color(0xFF5F5E55), onSecondary = Color(0xFFFBFAF6),
    secondaryContainer = Color(0xFF7E7D72), onSecondaryContainer = Color(0xFFF6F5EE),
    tertiary = Color(0xFF45443E), onTertiary = Color(0xFFFBFAF6),
    tertiaryContainer = Color(0xFF616058), onTertiaryContainer = Color(0xFFF0EFE8),
    error = LightError, onError = LightOnError,
    errorContainer = LightErrorContainer, onErrorContainer = LightOnErrorContainer,
    background = Color(0xFFF4F3EE), onBackground = Color(0xFF191917),
    surface = Color(0xFFF4F3EE), onSurface = Color(0xFF191917),
    surfaceVariant = Color(0xFFE3E2DB), onSurfaceVariant = Color(0xFF4A4A45),
    outline = Color(0xFF8A897F), outlineVariant = Color(0xFFCFCEC5),
    surfaceDim = Color(0xFFDBDAD3), surfaceBright = Color(0xFFFDFCFA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFECEBE5),
    surfaceContainer = Color(0xFFE7E6E0),
    surfaceContainerHigh = Color(0xFFE1E0DA),
    surfaceContainerHighest = Color(0xFFDBDAD4),
    inverseSurface = Color(0xFF2A2A26), inverseOnSurface = Color(0xFFF4F3EC),
    inversePrimary = Color(0xFFE6E5DC)
)

/** Mono · 深色 —— 墨面:暖调墨黑底 + 纸白 primary,灰阶容器阶梯避 OLED 纯黑。 */
val MonoDarkColors = darkColorScheme(
    primary = Color(0xFFF0EFE6), onPrimary = Color(0xFF181814),
    primaryContainer = Color(0xFF3A3934), onPrimaryContainer = Color(0xFFE9E8DF),
    secondary = Color(0xFFC0BFB3), onSecondary = Color(0xFF2B2A24),
    secondaryContainer = Color(0xFF5D5C52), onSecondaryContainer = Color(0xFFECEBE1),
    tertiary = Color(0xFFD2D1C5), onTertiary = Color(0xFF2F2E28),
    tertiaryContainer = Color(0xFF5E5D53), onTertiaryContainer = Color(0xFFEFEEE4),
    error = DarkError, onError = DarkOnError,
    errorContainer = DarkErrorContainer, onErrorContainer = DarkOnErrorContainer,
    background = Color(0xFF121210), onBackground = Color(0xFFEDECE4),
    surface = Color(0xFF121210), onSurface = Color(0xFFEDECE4),
    surfaceVariant = Color(0xFF2E2D28), onSurfaceVariant = Color(0xFFCBCAC0),
    outline = Color(0xFF96958A), outlineVariant = Color(0xFF4A4943),
    surfaceDim = Color(0xFF121210), surfaceBright = Color(0xFF3C3B36),
    surfaceContainerLowest = Color(0xFF0C0C0A),
    surfaceContainerLow = Color(0xFF1A1A17),
    surfaceContainer = Color(0xFF201F1C),
    surfaceContainerHigh = Color(0xFF2A2925),
    surfaceContainerHighest = Color(0xFF353430),
    inverseSurface = Color(0xFFEDECE4), inverseOnSurface = Color(0xFF2A2A26),
    inversePrimary = Color(0xFF181814)
)

// ===== 品牌渐变(AI 特性专用)=====

/**
 * 品牌蓝→紫渐变 —— Future Blue(primary)→ Intelligence Purple(secondary)。
 *
 * 设计系统纪律:渐变只用于 AI 特性(今日热点聚合标题栏、总览页 digest Hero),不扩散到普通界面。
 * 颜色取自 colorScheme 而非固定字面值:深/浅两套色板各自保证渐变上 onPrimary
 * 文字的对比度(浅色=深渐变+白字,深色=浅渐变+深字),调用方无需模式判断。
 * 单一来源:今日热点(HotTopicsSection)与总览 digest Hero(OverviewScreen)共用。
 */
val BrandGradient: Brush
    @Composable
    get() = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.secondary
        )
    )
