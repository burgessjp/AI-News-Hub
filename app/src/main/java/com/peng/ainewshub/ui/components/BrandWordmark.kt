package com.peng.ainewshub.ui.components

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import com.peng.ainewshub.R

/**
 * 品牌 wordmark("AI NEWS HUB" 字标)—— 总览/更多页顶栏共用。
 *
 * 深/浅两套矢量(drawable/ic_wordmark[.dark].xml,由 scripts/gen_wordmark.py 生成)
 * 按当前 colorScheme 选择:colorScheme 已由 AiNewsHubTheme 按「设置页自选主题」解析,
 * 用 surface 亮度判定即跟随用户设置,而非系统 night 资源限定符。
 */
@Composable
fun BrandWordmark(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Image(
        painter = painterResource(if (dark) R.drawable.ic_wordmark_dark else R.drawable.ic_wordmark),
        contentDescription = "AI News Hub",
        modifier = modifier
    )
}
