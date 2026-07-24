package com.peng.ainewshub.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 语义化字号层 —— 产品语义到字号的映射,收口全 App 散落的 sp 字面量。
 *
 * 设计原则:
 *  - 组件不再写 `fontSize = X.sp` / `lineHeight = Y.sp`,改用 `style = AppText.xxx`
 *  - 档位覆盖全部业务场景,新增场景优先归入已有档,避免档位膨胀
 *  - fontSize/lineHeight/fontWeight 的字面值须与 [AppTypography] 对应档保持一致
 *    (Type.kt 是 MD3 规范层,AppText 是产品语义层,两者独立演进但数值对齐)
 *
 * 档位说明(字号为 fontScale = 1 基准值,详见各 val 注释):
 *  - [titleHero]:     一级标题(顶栏主标题、首页大标题)       24sp
 *  - [titleSection]:  二级标题(详情页标题、区块标题)         20sp
 *  - [titleItem]:     三级标题(列表项标题)                   16sp
 *  - [titleCompact]:  紧凑标题(子标题、HN 标题)              14sp
 *  - [body]:          正文                                    14sp
 *  - [bodyCompact]:   紧凑正文(快讯/评论,21sp 密集阅读行高) 14sp
 *  - [bodyTight]:     行高压缩的弱化正文(英文原标题等)       14sp/20sp
 *  - [bodySmall]:     辅助正文(译文、摘要)                   12sp
 *  - [caption]:       极小字(meta、时间)                     11sp
 *
 * 与 MD3 typography 的关系:AppText 不取代 typography。已正确用
 * `MaterialTheme.typography.xxx` 且无散落的组件保持不动;AppText 只接管
 * 需要语义命名的场景(详情页标题、紧凑正文、弱化正文等)。
 *
 * 实例化设计(不再是 object):
 *  - 字体族随设置页「字体」选项切换(默认 Inter;衬线/等宽为 Compose 内置族)
 *  - 字号随设置页「字号」档位整体缩放(fontScale 只作用于 fontSize/lineHeight,
 *    字重/字距不动;letterSpacing 不缩放,避免破坏精调的字距)
 *  - 由 [AiNewsHubTheme] 构造并经 [LocalAppTextStyles] 下发;组件经顶层
 *    `@Composable val AppText` 读取,调用点写法与旧 object 完全一致
 */
@Immutable
class AppTextStyles(
    fontFamily: FontFamily = InterFontFamily,
    fontScale: Float = 1f
) {

    /** 一级标题 —— 顶栏主标题、首页大标题。对齐 Type.kt titleLarge(24/30/SemiBold)。 */
    val titleHero: TextStyle = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp * fontScale,
        lineHeight = 30.sp * fontScale,
        letterSpacing = (-0.5).sp    // 紧字距,呼应设计系统 headline 紧凑现代感
    )

    /** 二级标题 —— 详情页标题、区块标题。对齐 headlineSmall 降档(20/26/SemiBold)。 */
    val titleSection: TextStyle = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp * fontScale,
        lineHeight = 26.sp * fontScale,
        letterSpacing = (-0.3).sp    // 二级标题略收紧
    )

    /** 三级标题 —— 列表项标题。对齐 Type.kt titleMedium(16/24/SemiBold)。 */
    val titleItem: TextStyle = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp * fontScale,
        lineHeight = 24.sp * fontScale,
        letterSpacing = 0.1.sp
    )

    /** 紧凑标题 —— 子标题、HN 标题。对齐 Type.kt titleSmall(14/20/Medium)。
     *  用于标题场景时 fontWeight 由调用方覆盖为 SemiBold(列表项标题惯例)。 */
    val titleCompact: TextStyle = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp * fontScale,
        lineHeight = 20.sp * fontScale,
        letterSpacing = 0.1.sp
    )

    /** 正文 —— 对齐 Type.kt bodyMedium(14/22/Normal)。 */
    val body: TextStyle = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp * fontScale,
        lineHeight = 22.sp * fontScale,
        letterSpacing = 0.2.sp
    )

    /** 紧凑正文 —— 快讯/评论正文,行高从 22sp 压到 21sp,密集阅读场景。
     *  独立档位,因 21sp 是产品定制的「信息密度优先」行高,不归入 body。 */
    val bodyCompact: TextStyle = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp * fontScale,
        lineHeight = 21.sp * fontScale,
        letterSpacing = 0.2.sp
    )

    /** 行高压缩的弱化正文 —— 英文原标题等辅助文本。
     *  字重保持 Normal(不迁 titleCompact 的 Medium),因这类文本设计意图是「弱化辅助」
     *  而非标题强调;行高 20sp 比正文 22sp 更紧,贴合辅助文本的紧凑感。 */
    val bodyTight: TextStyle = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp * fontScale,
        lineHeight = 20.sp * fontScale,
        letterSpacing = 0.2.sp
    )

    /** 辅助正文 —— 译文、摘要。对齐 Type.kt bodySmall(12/18/Normal)。
     *  Type.kt 的 bodySmall 用 Default(MD3 默认 12/16),此处显式 18sp 行高
     *  (产品决策:摘要需更舒展行距)。 */
    val bodySmall: TextStyle = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp * fontScale,
        lineHeight = 18.sp * fontScale,
        letterSpacing = 0.4.sp
    )

    /** 极小字 —— meta、时间。对齐 Type.kt labelSmall(11/16/Medium)。 */
    val caption: TextStyle = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp * fontScale,
        lineHeight = 16.sp * fontScale,
        letterSpacing = 0.5.sp
    )
}

/**
 * 当前主题的 [AppTextStyles],由 [AiNewsHubTheme] 提供。
 *
 * 用 staticCompositionLocalOf:字体/字号只在设置页低频变更,变更时整树重组
 * 即可(与 MaterialTheme 切换同级),不值得为它引入细粒度订阅。
 */
val LocalAppTextStyles = staticCompositionLocalOf { AppTextStyles() }

/**
 * 语义化字号层入口 —— 组件内 `style = AppText.xxx` 读取当前主题实例。
 *
 * 顶层 @Composable 属性,代理 [LocalAppTextStyles.current];调用点写法与
 * 旧 `object AppText` 完全一致(只能在 Composable 上下文使用)。
 */
val AppText: AppTextStyles
    @Composable get() = LocalAppTextStyles.current

/** 章节条专用字距 —— 全宽分组标题(labelLarge/Bold)的精调字距。
 *  labelLarge 全 App 有 3 种字距(本值/[TrackingWide]/MD3 默认),不收敛进字号档以免误伤。 */
val TrackingSection = 0.5.sp

/** 宽字距 —— 日报日期标签等小字大写感 label 的精调字距。 */
val TrackingWide = 1.sp
