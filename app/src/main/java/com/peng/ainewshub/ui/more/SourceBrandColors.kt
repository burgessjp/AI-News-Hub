package com.peng.ainewshub.ui.more

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.peng.ainewshub.data.model.OpenAiAnthropicNews
import com.peng.ainewshub.data.model.ProductHunt

/**
 * Hub 九源品牌色 —— 更多页「浏览」组 48dp 图标块的固定品牌配色。
 *
 * 本文件是「颜色只走 colorScheme」纪律的集中例外(stormzhang 信源徽章沿用原站
 * hex 已有先例):品牌色是各源的识别记忆点,不随主题色板/动态取色变化。
 * 所有品牌色 hex 一律收口在本文件,其他代码不得再散落品牌色字面量。
 *
 * 色值出处:
 *  - HackerNews:  #FF6600 —— HN 官方品牌橙
 *  - GitHub:      浅 #24292F / 深 #E6EDF3 —— GitHub Primer 前景色,深色模式色块/图标反转
 *  - stormzhang:  #00897B —— 无官方品牌色,自定义固定色(青绿,与其余四色不撞)
 *  - HuggingFace: #FFB000 —— HuggingFace 品牌黄(深浅同值,图标用深色保对比)
 *  - ProductHunt: #DA552F —— PH 官方品牌橙红(logo 色)
 *  - TheRundownAi:#FFD400 —— beehiiv 平台主色黄(The Rundown AI 无强品牌色,取托管平台色)
 *  - OpenAiAnthropicNews: #10A37F —— OpenAI 品牌绿(合并源以 OpenAI 色为代表,两家头部 AI 厂商)
 *  - AiHot:       #003EC7 —— App 品牌 Future Blue(对齐 Color.kt BrandGradient 基色),自家源
 */

/** 单源品牌色对:[container] 图标块实底色,[icon] 块上对比图标色。 */
@Immutable
data class SourceBrandColors(val container: Color, val icon: Color)

/** GitHub 品牌 container 深浅两色 —— 抽成常量供下方 @Composable getter 引用。 */
private val GitHubContainerDay = Color(0xFF24292F)
private val GitHubContainerNight = Color(0xFFE6EDF3)

/** 八源品牌色入口:更多页「浏览」组图标块按源取色(仅 GitHub 需深色变体)。 */
object SourceBrand {
    /** HackerNews —— 品牌橙 #FF6600 + 白图标(还原 HN 标识观感)。 */
    val HackerNews = SourceBrandColors(container = Color(0xFFFF6600), icon = Color.White)

    /** GitHub —— 浅色模式深块白图标;深色模式反转为浅块深图标。 */
    val GitHub: SourceBrandColors
        @Composable get() = if (isSystemInDarkTheme()) {
            SourceBrandColors(container = GitHubContainerNight, icon = Color(0xFF24292F))
        } else {
            SourceBrandColors(container = GitHubContainerDay, icon = Color.White)
        }

    /** stormzhang —— 无官方品牌色,自定义固定色(青绿 #00897B,与其余四色不撞)+ 白图标。 */
    val Stormzhang = SourceBrandColors(container = Color(0xFF00897B), icon = Color.White)

    /** HuggingFace —— 品牌黄 #FFB000(深浅同值)+ 深色图标保对比。 */
    val HuggingFace = SourceBrandColors(container = Color(0xFFFFB000), icon = Color(0xFF1F1F1F))

    /** Product Hunt —— 官方品牌橙红 #DA552F + 白图标(logo 色)。 */
    val ProductHunt = SourceBrandColors(container = Color(0xFFDA552F), icon = Color.White)

    /** The Rundown AI —— beehiiv 主色黄 #FFD400 + 深色图标保对比(同 HuggingFace 套路)。 */
    val TheRundownAi = SourceBrandColors(container = Color(0xFFFFD400), icon = Color(0xFF1F1F1F))

    /** OpenAI x Anthropic —— 合并源,取 OpenAI 品牌绿 #10A37F + 白图标(两家头部 AI 厂商动态)。 */
    val OpenAiAnthropicNews = SourceBrandColors(container = Color(0xFF10A37F), icon = Color.White)

    /** AIHot 精选 —— 自家源,用 App 品牌 Future Blue #003EC7 + 白图标(对齐 BrandGradient 基色)。 */
    val AiHot = SourceBrandColors(container = Color(0xFF003EC7), icon = Color.White)
}
