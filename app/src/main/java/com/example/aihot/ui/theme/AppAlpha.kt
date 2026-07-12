package com.example.aihot.ui.theme

/**
 * 语义化透明度层 —— 收口全 App 散落的 `color.copy(alpha = 0.XXf)`。
 *
 * 设计原则:
 *  - 颜色仍用 MD3 colorScheme,不另建颜色语义层
 *  - 透明度按「视觉语义」归档,同语义的相近 alpha 合并(人眼无感知差)
 *  - 调用方:`cs.primary.copy(alpha = AppAlpha.primaryEmphasis)`
 *
 * 档位说明:
 *  - [primaryEmphasis]:  primary 弱化(文字、渐变终点)        0.85f
 *  - [badgeOverlay]:     徽章/药丸半透明底(primary/分档色)   0.12f
 *  - [onPrimaryOverlay]: onPrimary 半透明底(深底浅 chip)     0.18f
 */
object AppAlpha {
    /** primary 弱化 —— 用于 primary 色文字弱化、渐变终点。
     *  合并原 0.82f(渐变)与 0.85f(文字),取 0.85f(视觉无差)。 */
    const val primaryEmphasis: Float = 0.85f

    /** 徽章/药丸半透明底 —— 用于 primary/分档色(error/tertiary/secondary)做底。
     *  合并原 0.10f(状态徽章)与 0.12f(分数药丸),取 0.12f(视觉无差)。 */
    const val badgeOverlay: Float = 0.12f

    /** onPrimary 半透明底 —— 用于深色背景(onPrimary)上的浅色 chip。
     *  不与 [badgeOverlay] 合并:两者基色语义相反(深底浮浅 vs 浅底浮深)。 */
    const val onPrimaryOverlay: Float = 0.18f
}
