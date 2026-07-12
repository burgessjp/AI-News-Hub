package com.example.aihot.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.example.aihot.R

/**
 * Inter 字体族 — "Synthetic Intelligence News" 设计系统的指定字体。
 *
 * Inter 是 SIL Open Font License(SIL OFL 1.1)开源字体,可自由分发与嵌入,
 * 许可证全文见 res/font/inter_font_license.txt。本地 4 个静态字重:
 *  Regular / Medium / SemiBold / Bold,覆盖全 App 所需权重。
 *
 * 设计意图:Inter 字符几何中性、紧凑字距适合大标题(-0.5em~-1em),
 * x-height 较高保证长文阅读舒适,符合设计系统"权威且可访问"的品牌人格。
 */
val InterFontFamily: FontFamily = FontFamily(
    Font(R.font.inter_regular, weight = androidx.compose.ui.text.font.FontWeight.Normal),
    Font(R.font.inter_medium, weight = androidx.compose.ui.text.font.FontWeight.Medium),
    Font(R.font.inter_semi_bold, weight = androidx.compose.ui.text.font.FontWeight.SemiBold),
    Font(R.font.inter_bold, weight = androidx.compose.ui.text.font.FontWeight.Bold)
)
