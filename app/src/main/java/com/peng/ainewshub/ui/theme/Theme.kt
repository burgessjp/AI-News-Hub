package com.peng.ainewshub.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * App 主题入口。
 *
 * 默认 `dynamicColor = false` —— 优先保证 Future Blue + Intelligence Purple 品牌色稳定。
 * Android 12+ 用户可在调用处显式传 `dynamicColor = true` 启用壁纸派生色。
 * 始终跟随系统深/浅色设置。
 *
 * @param fontFamily 字体族覆盖。默认 null 沿用 [AppTypography] 的 Inter;
 *        设置页"衬线/等宽"选项传 Serif/Monospace 将全 App 文字统一切换。
 *        同时作用于语义字号层 [AppTextStyles](经 [LocalAppTextStyles] 下发)。
 * @param fontScale 字号整体缩放(设置页「字号」档位),只作用于 [AppTextStyles]
 *        的 fontSize/lineHeight;MD3 typography 不缩放,避免组件内部错位。
 */
@Composable
fun AiNewsHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    fontFamily: FontFamily? = null,
    fontScale: Float = 1f,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val typography = if (fontFamily != null) AppTypography.withFontFamily(fontFamily) else AppTypography
    // 语义字号层:字体族与缩放随设置变化,与 typography 同源(fontFamily 缺省 = Inter)
    val appTextStyles = remember(fontFamily, fontScale) {
        AppTextStyles(fontFamily = fontFamily ?: InterFontFamily, fontScale = fontScale)
    }

    CompositionLocalProvider(LocalAppTextStyles provides appTextStyles) {
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
 * 字号 / 行高 / 字重 / 字距全部保留,只换字体族 —— 这样「字体设置」只影响字形,
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
