package com.peng.ainewshub.data.prefs

/**
 * display_prefs 持久化词汇表 —— [SettingsStore] 存取的枚举纯值。
 *
 * 刻意不含任何 UI 属性(labelRes / FontFamily 等):data 层不依赖 Compose,
 * 展示侧映射(ui.more.SettingsScreen 的 labelRes / fontFamily 扩展)留在 UI。
 */

/**
 * 主题模式(系统 / 亮 / 暗),按 [name] 持久化于 display_prefs 的 theme_mode 键。
 */
enum class ThemeMode {
    System, Light, Dark
}

/**
 * 皮肤(配色方案)—— 设置页「皮肤」选项,按 [name] 持久化于 display_prefs 的 skin 键。
 * Classic = 品牌蓝紫色板(默认);Mono = 黑白灰阶原型风,明暗仍跟随 [ThemeMode]
 * (浅色白底黑字 / 深色黑底白字,两套灰阶板见 ui/theme/Color.kt)。
 * 皮肤优先于动态取色:非 Classic 时壁纸派生色让位(见 ui/theme/Theme.kt)。
 */
enum class AppSkin {
    Classic, Mono
}

/**
 * 字体族(系统默认 / 衬线 / 等宽),按 [name] 持久化。
 * 仅用 Compose 内置 FontFamily,无需引入外部字体资源。
 */
enum class FontChoice {
    System, Serif, Mono
}

/**
 * 字号档位,整体缩放语义字号层 AppTextStyles(见 ui/theme/AppText.kt)。
 * 只缩放 AppText 档位的 fontSize/lineHeight;MD3 typography 不动,
 * 避免 TopAppBar/Chip 等组件内部布局错位。
 */
enum class FontScale(val scale: Float) {
    Compact(0.9f),
    Standard(1.0f),
    Large(1.15f)
}

/** 应用内语言 —— 设置页「语言」三选项;按 [name] 持久化于 display_prefs 的 language 键。 */
enum class AppLanguage { SYSTEM, ZH_CN, EN }
