package com.peng.ainewshub.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 品牌字标("AI NEWS HUB")—— 根页顶栏共用。
 *
 * v1.4.0 纸墨日报改版:由四套矢量 drawable(皮肤 × 明暗)改为**排版字标**——
 * 衬线(FontFamily.Serif,系统 NotoSerifCJK;个别 ROM 缺 CJK 衬线时优雅降级
 * 无衬线,版式不塌)+ 宽字距 + onSurface 墨色,随 colorScheme 自动适配明暗,
 * 不再需要按皮肤/明暗挑资源。保留组件名与签名,调用方零改动。
 */
@Composable
fun BrandWordmark(modifier: Modifier = Modifier) {
    // 固定 20sp(不随字号档位缩放:字标是版面元素,不是正文)
    Text(
        text = "AI NEWS HUB",
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Serif,
        letterSpacing = 2.5.sp,
        modifier = modifier
    )
}
