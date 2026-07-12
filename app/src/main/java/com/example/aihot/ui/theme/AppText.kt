package com.example.aihot.ui.theme

import androidx.compose.ui.text.TextStyle
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
 * 档位说明(详见各 val 注释):
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
 * 注:纯 object 自持 sp,不引用 MaterialTheme.typography —— 因 object 不能在
 * 顶层读 CompositionLocal,且 AppText 与 MD3 15 档非 1:1 映射(合并/新增了语义档)。
 */
object AppText {

    /** 一级标题 —— 顶栏主标题、首页大标题。对齐 Type.kt titleLarge(24/30/SemiBold)。 */
    val titleHero: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp    // 紧字距,呼应设计系统 headline 紧凑现代感
    )

    /** 二级标题 —— 详情页标题、区块标题。对齐 headlineSmall 降档(20/26/SemiBold)。 */
    val titleSection: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp    // 二级标题略收紧
    )

    /** 三级标题 —— 列表项标题。对齐 Type.kt titleMedium(16/24/SemiBold)。 */
    val titleItem: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    )

    /** 紧凑标题 —— 子标题、HN 标题。对齐 Type.kt titleSmall(14/20/Medium)。
     *  用于标题场景时 fontWeight 由调用方覆盖为 SemiBold(列表项标题惯例)。 */
    val titleCompact: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )

    /** 正文 —— 对齐 Type.kt bodyMedium(14/22/Normal)。 */
    val body: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.2.sp
    )

    /** 紧凑正文 —— 快讯/评论正文,行高从 22sp 压到 21sp,密集阅读场景。
     *  独立档位,因 21sp 是产品定制的「信息密度优先」行高,不归入 body。 */
    val bodyCompact: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.2.sp
    )

    /** 行高压缩的弱化正文 —— 英文原标题等辅助文本。
     *  字重保持 Normal(不迁 titleCompact 的 Medium),因这类文本设计意图是「弱化辅助」
     *  而非标题强调;行高 20sp 比正文 22sp 更紧,贴合辅助文本的紧凑感。 */
    val bodyTight: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp
    )

    /** 辅助正文 —— 译文、摘要。对齐 Type.kt bodySmall(12/18/Normal)。
     *  Type.kt 的 bodySmall 用 Default(MD3 默认 12/16),此处显式 18sp 行高
     *  (产品决策:摘要需更舒展行距)。 */
    val bodySmall: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.4.sp
    )

    /** 极小字 —— meta、时间。对齐 Type.kt labelSmall(11/16/Medium)。 */
    val caption: TextStyle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
}
