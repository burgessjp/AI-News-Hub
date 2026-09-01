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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.peng.ainewshub.data.prefs.AppSkin

/**
 * 当前皮肤([AiNewsHubTheme] 解析结果)—— 供词云等绕过 colorScheme 的固定色板
 * 读取(见 ui/trends/CloudWordColors.kt),非 UI 场景默认 Classic。
 */
val LocalAppSkin = staticCompositionLocalOf { AppSkin.Classic }

/**
 * App 实际明暗态([AiNewsHubTheme] 的 darkTheme 参数,用户 ThemeMode 解析结果,
 * 而非系统 uiMode)—— 旁路色板的深浅判断统一改读它,修复强制浅/深色模式下
 * `isSystemInDarkTheme()` 与界面错配的问题。
 */
val LocalAppDarkTheme = staticCompositionLocalOf { false }

/**
 * App 主题入口。
 *
 * 默认 `dynamicColor = false` —— 优先保证 Future Blue + Intelligence Purple 品牌色稳定。
 * Android 12+ 用户可在调用处显式传 `dynamicColor = true` 启用壁纸派生色。
 * 明暗跟随调用方传入的 darkTheme(主入口由用户 ThemeMode 解析,见 AiNewsHubApp)。
 *
 * 皮肤 [skin]:Classic = 品牌双色板(默认);Mono = 黑白灰阶原型风(Color.kt 的
 * MonoLight/MonoDarkColors),明暗仍随 darkTheme。皮肤优先于动态取色 ——
 * 非 Classic 时忽略 dynamicColor,壁纸派生色让位(设置页开关同步置灰)。
 *
 * @param fontFamily 字体族覆盖。默认 null 跟随系统字体;
 *        设置页"衬线/等宽"选项传 Serif/Monospace 将全 App 文字统一切换。
 *        同时作用于语义字号层 [AppTextStyles](经 [LocalAppTextStyles] 下发)。
 * @param fontScale 字号整体缩放(设置页「字号」档位),同时作用于 [AppTextStyles]
 *        与 [AppTypography](MD3 typography)的 fontSize/lineHeight —— 只缩这两项,
 *        字重/字距不动,避免组件内部错位;此前 typography 不缩放,导致约 80 处
 *        `MaterialTheme.typography` 调用与 AppText 同屏字号层级倒挂。
 */
@Composable
fun AiNewsHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    skin: AppSkin = AppSkin.Classic,
    fontFamily: FontFamily? = null,
    fontScale: Float = 1f,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        // 皮肤优先于动态取色:非默认皮肤下壁纸派生色让位,保证皮肤观感完整
        dynamicColor && skin == AppSkin.Classic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        skin == AppSkin.Mono && darkTheme -> MonoDarkColors
        skin == AppSkin.Mono -> MonoLightColors
        darkTheme -> DarkColors
        else -> LightColors
    }

    // 字体族替换与字号缩放同源应用:fontScale = 1 时 TextStyle.scaled 原样返回,零开销
    val typography = remember(fontFamily, fontScale) {
        (if (fontFamily != null) AppTypography.withFontFamily(fontFamily) else AppTypography)
            .withFontScale(fontScale)
    }
    // 语义字号层:字体族与缩放随设置变化,与 typography 同源(fontFamily 缺省 = 跟随系统)
    val appTextStyles = remember(fontFamily, fontScale) {
        AppTextStyles(fontFamily = fontFamily, fontScale = fontScale)
    }

    CompositionLocalProvider(
        LocalAppTextStyles provides appTextStyles,
        LocalAppSkin provides skin,
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
