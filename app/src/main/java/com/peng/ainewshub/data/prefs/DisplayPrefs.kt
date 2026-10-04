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
 * 字号档位,整体缩放语义字号层 AppTextStyles(见 ui/theme/AppText.kt)。
 * AppText 档位与 MD3 typography 在 Theme.kt 双侧同源构造、同步缩放,
 * 两侧档位数值保持一致,避免组件内外字号脱节。
 */
enum class FontScale(val scale: Float) {
    Compact(0.9f),
    Standard(1.0f),
    Large(1.15f)
}

/** 应用内语言 —— 设置页「语言」三选项;按 [name] 持久化于 display_prefs 的 language 键。 */
enum class AppLanguage { SYSTEM, ZH_CN, EN }
