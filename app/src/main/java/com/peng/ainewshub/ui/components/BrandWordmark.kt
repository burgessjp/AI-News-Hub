package com.peng.ainewshub.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.peng.ainewshub.R
import com.peng.ainewshub.data.prefs.AppSkin
import com.peng.ainewshub.ui.theme.LocalAppDarkTheme
import com.peng.ainewshub.ui.theme.LocalAppSkin

/**
 * 品牌 wordmark("AI NEWS HUB" 字标)—— 总览/更多页顶栏共用。
 *
 * 皮肤 × 明暗共四套矢量(drawable/ic_wordmark{,_dark,_mono,_mono_dark}.xml,
 * 由 scripts/gen_wordmark.py 生成):classic 走品牌蓝紫渐变,mono 走灰阶渐变
 * (与 MonoLight/MonoDarkColors 同套色值)。皮肤与明暗读
 * [LocalAppSkin]/[LocalAppDarkTheme](AiNewsHubTheme 按「设置页自选主题模式 + 皮肤」
 * 解析后下发),而非 values-night 资源限定符(它只跟随系统 night mode)。
 */
@Composable
fun BrandWordmark(modifier: Modifier = Modifier) {
    val dark = LocalAppDarkTheme.current
    val res = when (LocalAppSkin.current) {
        AppSkin.Classic -> if (dark) R.drawable.ic_wordmark_dark else R.drawable.ic_wordmark
        AppSkin.Mono -> if (dark) R.drawable.ic_wordmark_mono_dark else R.drawable.ic_wordmark_mono
    }
    Image(
        painter = painterResource(res),
        contentDescription = "AI News Hub",
        modifier = modifier
    )
}
