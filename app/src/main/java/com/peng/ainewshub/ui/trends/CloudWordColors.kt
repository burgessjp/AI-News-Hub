package com.peng.ainewshub.ui.trends

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.peng.ainewshub.ui.theme.LocalAppDarkTheme

/**
 * 趋势词云专用调色板 —— 词云页(螺旋 / 圆形气泡两种形态)词条的固定配色。
 *
 * 本文件是「颜色只走 colorScheme」纪律的集中例外(先例见 ui/more/SourceBrandColors.kt):
 * 词云是装饰性数据可视化,色彩承担「丰富观感 + 档位语义」双重职责,主题色板
 * 拿不出这么多高区分度色相,故定制固定色。词云相关色值 hex 一律收口在本文件,
 * 词云代码不得再散落色值字面量。
 *
 * v1.4.0 纸墨日报改版:由「六色相家族」改为**报纸四色阶梯** —— 墨(头部大词,
 * 最重)→ 报纸红 → 纸金 → 暖灰阶(尾部小词),去多彩、贴版面气质;深色模式
 * 整体提亮保对比。
 *
 * 深浅判断读 [LocalAppDarkTheme](用户 ThemeMode 解析结果)而非 isSystemInDarkTheme():
 * 用户强制浅/深色时系统 uiMode 与界面不一致,跟随后者才不与 colorScheme 错配。
 */

/** 浅色模式色表(纸白底):[字号档][明暗变体],墨 → 红 → 金 → 灰阶。 */
private val CloudPaletteDay = listOf(
    listOf(Color(0xFF1B1A17), Color(0xFF33302A)),  // 墨黑 —— 头部大词
    listOf(Color(0xFFB93B1D), Color(0xFFC9522F)),  // 报纸红
    listOf(Color(0xFF8A6430), Color(0xFF9C7A42)),  // 纸金
    listOf(Color(0xFF57534A), Color(0xFF6B675F)),  // 暖墨灰
    listOf(Color(0xFF78746A), Color(0xFF8A857A)),  // 纸灰
    listOf(Color(0xFF9A958A), Color(0xFFA8A294))   // 浅纸灰 —— 最小词
)

/** 深色模式色表(墨底,整体提亮保对比):[字号档][明暗变体]。 */
private val CloudPaletteNight = listOf(
    listOf(Color(0xFFE8E4DA), Color(0xFFDDD8CB)),  // 纸白 —— 头部大词
    listOf(Color(0xFFE07A56), Color(0xFFE89573)),  // 报纸红提亮
    listOf(Color(0xFFD2AC72), Color(0xFFDDBD8E)),  // 纸金提亮
    listOf(Color(0xFFA8A294), Color(0xFFB5AFA1)),  // 暖灰提亮
    listOf(Color(0xFF8F8A7E), Color(0xFF9A958A)),
    listOf(Color(0xFF7A7568), Color(0xFF85806F))   // 尾档 —— 最小词
)

/**
 * 词云词条配色表(6 字号档 × 2 明暗变体):词条按 [tier][shade] 取色,
 * 随用户主题模式(明暗)切换。
 */
@Composable
fun cloudTierColors(): List<List<Color>> =
    if (LocalAppDarkTheme.current) CloudPaletteNight else CloudPaletteDay
