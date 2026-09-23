package com.peng.ainewshub.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * App 实际明暗态([AiNewsHubTheme] 的 darkTheme 参数,用户 ThemeMode 解析结果,
 * 而非系统 uiMode)—— 旁路色板的深浅判断统一改读它,修复强制浅/深色模式下
 * `isSystemInDarkTheme()` 与界面错配的问题。
 */
val LocalAppDarkTheme = staticCompositionLocalOf { false }

/**
 * App 主题入口 —— 「纸墨日报」单一风格(Color.kt 亮/暗双 scheme)。
 *
 * 不做动态取色与多皮肤:编辑色板是固定品牌资产,壁纸派生色会破坏纸墨身份
 * (v1.4.0 随日刊化改版移除,决策记录见 CHANGELOG)。字体恒纸墨宋体
 * ([FontFamily.Serif],中文回退 Noto Serif CJK);字体族设置已删,不可切换。
 *
 * @param fontScale 字号整体缩放(设置页「字号」档位),同时作用于 [AppTextStyles]
 *        与 [AppTypography](MD3 typography)的 fontSize/lineHeight —— 只缩这两项,
 *        字重/字距不动,避免组件内部错位;此前 typography 不缩放,导致约 80 处
 *        `MaterialTheme.typography` 调用与 AppText 同屏字号层级倒挂。
 */
@Composable
fun AiNewsHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    // 纸墨宋体 + 字号缩放同源应用:fontScale = 1 时 TextStyle.scaled 原样返回,零开销
    val typography = remember(fontScale) {
        AppTypography.withFontFamily(FontFamily.Serif).withFontScale(fontScale)
    }
    // 语义字号层:与 typography 同源,同用纸墨宋体
    val appTextStyles = remember(fontScale) {
        AppTextStyles(fontScale = fontScale)
    }

    CompositionLocalProvider(
        LocalAppTextStyles provides appTextStyles,
        LocalAppDarkTheme provides darkTheme
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = AppShapes,
            content = content
        )
    }
}

/**
 * 将一个 [Typography] 里每个 [TextStyle] 的 fontFamily 统一替换为 [family]。
 *
 * 字号 / 行高 / 字重 / 字距全部保留,只换字体族 —— 纸墨宋体全局应用只改字形,
 * 不破坏 Type.kt 里精调的排版参数。
 */
private fun Typography.withFontFamily(family: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family)
)

/**
 * 将一个 [Typography] 里每个 [TextStyle] 的 fontSize/lineHeight 乘以 [scale]。
 *
 * 设置页「字号」档位的 MD3 typography 侧实现,与 [AppTextStyles] 的缩放规则一致:
 * 只缩 fontSize/lineHeight,字重/字距不动。scale = 1(默认档)时原样返回,不产生
 * 新 Typography 实例。
 */
private fun Typography.withFontScale(scale: Float): Typography = copy(
    displayLarge = displayLarge.scaled(scale),
    displayMedium = displayMedium.scaled(scale),
    displaySmall = displaySmall.scaled(scale),
    headlineLarge = headlineLarge.scaled(scale),
    headlineMedium = headlineMedium.scaled(scale),
    headlineSmall = headlineSmall.scaled(scale),
    titleLarge = titleLarge.scaled(scale),
    titleMedium = titleMedium.scaled(scale),
    titleSmall = titleSmall.scaled(scale),
    bodyLarge = bodyLarge.scaled(scale),
    bodyMedium = bodyMedium.scaled(scale),
    bodySmall = bodySmall.scaled(scale),
    labelLarge = labelLarge.scaled(scale),
    labelMedium = labelMedium.scaled(scale),
    labelSmall = labelSmall.scaled(scale)
)

/** fontSize/lineHeight 按 [scale] 缩放;scale = 1 时返回自身(默认档零开销)。 */
private fun TextStyle.scaled(scale: Float): TextStyle =
    if (scale == 1f) this else copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)
