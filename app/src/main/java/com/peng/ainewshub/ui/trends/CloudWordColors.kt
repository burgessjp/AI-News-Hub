package com.peng.ainewshub.ui.trends

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.peng.ainewshub.data.prefs.AppSkin
import com.peng.ainewshub.ui.theme.LocalAppDarkTheme
import com.peng.ainewshub.ui.theme.LocalAppSkin

/**
 * 趋势词云专用调色板 —— 词云页(螺旋 / 圆形气泡两种形态)词条的固定配色。
 *
 * 本文件是「颜色只走 colorScheme」纪律的集中例外(先例见 ui/more/SourceBrandColors.kt):
 * 词云是装饰性数据可视化,色彩承担「丰富观感 + 档位语义」双重职责,主题色板
 * 拿不出这么多高区分度色相,故定制固定色,不随主题色板/动态取色变化。
 * 词云相关色值 hex 一律收口在本文件,词云代码不得再散落色值字面量。
 *
 * 配色结构:六个字号档各一个色相(蓝 / 橙 / 绿 / 紫 / 玫红 / 青灰,大词→小词,
 * 与 App 品牌蓝同族起头),每档两个明暗变体按落位序号交替 —— 同档有家族感
 * 又不呆板;浅色模式用深饱和色、深色模式用浅亮色,各自保证 surface 上对比度。
 *
 * 黑白皮肤(Mono)另有灰阶色表:去色相、靠明度阶梯保档位区分(头档最深/最亮 →
 * 尾档最浅/最暗),与皮肤观感一致。
 *
 * 深浅判断读 [LocalAppDarkTheme](用户 ThemeMode 解析结果)而非 isSystemInDarkTheme():
 * 用户强制浅/深色时系统 uiMode 与界面不一致,跟随后者才不与 colorScheme 错配。
 */

/** 浅色模式色表:[字号档][明暗变体]。 */
private val CloudPaletteDay = listOf(
    listOf(Color(0xFF1D6FE0), Color(0xFF4A93E8)),  // 蓝 —— 头部大词
    listOf(Color(0xFFDF6215), Color(0xFFEC8A48)),  // 橙
    listOf(Color(0xFF1F9E5F), Color(0xFF40B67F)),  // 绿
    listOf(Color(0xFF7C4BD8), Color(0xFF9E7BE4)),  // 紫
    listOf(Color(0xFFD23A6B), Color(0xFFE06992)),  // 玫红
    listOf(Color(0xFF54687D), Color(0xFF74879B))   // 青灰 —— 最小词
)

/** 深色模式色表(同色相提亮保对比):[字号档][明暗变体]。 */
private val CloudPaletteNight = listOf(
    listOf(Color(0xFF6FA8F5), Color(0xFF93C0F8)),  // 蓝
    listOf(Color(0xFFF59A5C), Color(0xFFF8B586)),  // 橙
    listOf(Color(0xFF5BC98C), Color(0xFF85D9AB)),  // 绿
    listOf(Color(0xFFB79BF0), Color(0xFFCCB9F5)),  // 紫
    listOf(Color(0xFFF0769B), Color(0xFFF59AB6)),  // 玫红
    listOf(Color(0xFF93A5B9), Color(0xFFAFBECE))   // 青灰
)

/** 黑白皮肤 · 浅色色表:深灰阶梯(头档近黑 → 尾档中灰),白底上保对比。 */
private val CloudPaletteMonoDay = listOf(
    listOf(Color(0xFF15151A), Color(0xFF242429)),  // 近黑 —— 头部大词
    listOf(Color(0xFF2C2C32), Color(0xFF38383E)),
    listOf(Color(0xFF414148), Color(0xFF4B4B52)),
    listOf(Color(0xFF505057), Color(0xFF58585F)),
    listOf(Color(0xFF5C5C63), Color(0xFF64646B)),
    listOf(Color(0xFF66666D), Color(0xFF6E6E75))   // 中灰 —— 最小词
)

/** 黑白皮肤 · 深色色表:浅灰阶梯(头档纸白 → 尾档中浅灰),黑底上保对比。 */
private val CloudPaletteMonoNight = listOf(
    listOf(Color(0xFFF2F2F5), Color(0xFFE4E4E8)),  // 纸白 —— 头部大词
    listOf(Color(0xFFD8D8DC), Color(0xFFCCCCD1)),
    listOf(Color(0xFFC0C0C6), Color(0xFFB5B5BB)),
    listOf(Color(0xFFABABB2), Color(0xFFA1A1A8)),
    listOf(Color(0xFF97979E), Color(0xFF8E8E95)),
    listOf(Color(0xFF86868D), Color(0xFF7E7E85))   // 中浅灰 —— 最小词
)

/**
 * 词云词条配色表(6 字号档 × 2 明暗变体):词条按 [tier][shade] 取色,
 * 随用户主题模式 + 皮肤切换(黑白皮肤用灰阶色表)。
 */
@Composable
fun cloudTierColors(): List<List<Color>> {
    val dark = LocalAppDarkTheme.current
    return when (LocalAppSkin.current) {
        AppSkin.Classic -> if (dark) CloudPaletteNight else CloudPaletteDay
        AppSkin.Mono -> if (dark) CloudPaletteMonoNight else CloudPaletteMonoDay
    }
}
