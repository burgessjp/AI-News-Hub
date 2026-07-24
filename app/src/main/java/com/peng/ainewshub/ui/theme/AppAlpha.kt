package com.peng.ainewshub.ui.theme

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
 *  - [badgeOverlayStrong]: 卡内子区域强调底(比 badgeOverlay 略深,同色系层次) 0.16f
 *  - [onPrimaryOverlay]: onPrimary 半透明底(深底浅 chip)     0.18f
 *  - [barOverlay]:       顶栏毛玻璃半透明底                 0.72f
 *  - [bottomBarSurface]: 底栏近实底(遮内容透出)            0.94f
 *  - [hairlineOverlay]:  发丝分隔线(顶栏下缘)                0.50f
 *  - [glassEdge]:        玻璃边缘高光描边(白色)              0.30f
 *  - [chipOverlay]:      标签 chip 弱化底                    0.60f
 *  - [badgeOutline]:     徽章同色描边                        0.20f
 *  - [neutralOverlay]:   中性灰弱化底                        0.20f
 */
object AppAlpha {
    /** primary 弱化 —— 用于 primary 色文字弱化、渐变终点。
     *  合并原 0.82f(渐变)与 0.85f(文字),取 0.85f(视觉无差)。 */
    const val primaryEmphasis: Float = 0.85f

    /** 徽章/药丸半透明底 —— 用于 primary/分档色(error/tertiary/secondary)做底。
     *  合并原 0.10f(状态徽章)与 0.12f(分数药丸),取 0.12f(视觉无差)。 */
    const val badgeOverlay: Float = 0.12f

    /** 卡内子区域强调底 —— 比 [badgeOverlay] 略深但仍属浅色层次,用于「卡中卡」内嵌面板
     *  (如 Breaking 卡内的推荐理由块):与卡底同色系,融入不突兀,又能看出是子区域。
     *  与 [badgeOverlay] 分档:卡底用浅档(0.12f),卡内子区域用本档(0.16f)显层次。 */
    const val badgeOverlayStrong: Float = 0.16f

    /** onPrimary 半透明底 —— 用于深色背景(onPrimary)上的浅色 chip。
     *  不与 [badgeOverlay] 合并:两者基色语义相反(深底浮浅 vs 浅底浮深)。 */
    const val onPrimaryOverlay: Float = 0.18f

    /** 顶栏毛玻璃半透明底 —— surface 系降透明,内容滚动时透出底层,传达"浮起"感。
     *  合并原 0.70f(底栏)与 0.72f(顶栏),取 0.72f(视觉无差);
     *  底栏后改用 [bottomBarSurface] 近实底,本档现为顶栏专用。 */
    const val barOverlay: Float = 0.72f

    /** 底栏近实底 —— 浮动药丸底栏专用:近乎不透明,遮住滚动到药丸下方的内容透出
     *  (Compose 无真模糊,半透明叠内容显脏)。与 [barOverlay] 分档:顶栏仍走毛玻璃半透明。 */
    const val bottomBarSurface: Float = 0.94f

    /** 发丝分隔线 —— 顶栏下缘 1dp 发丝线,半透明比实色更柔和,贴合"低对比分层"。 */
    const val hairlineOverlay: Float = 0.5f

    /** 玻璃边缘高光描边 —— 底栏药丸的白色半透明描边,模拟玻璃边缘反光。 */
    const val glassEdge: Float = 0.3f

    /** 标签 chip 弱化底 —— secondaryContainer 降透明做浅底小标签。 */
    const val chipOverlay: Float = 0.6f

    /** 徽章同色描边 —— 信源徽章等的 1dp 同色描边,比底色([badgeOverlay])略实以显形。
     *  与 [neutralOverlay] 同值不合档:一为描边一为底色,语义不同。 */
    const val badgeOutline: Float = 0.20f

    /** 中性灰弱化底 —— 中性灰图标块底色;灰度饱和度低,需比彩色档([badgeOverlay])
     *  更高的 alpha 才不显寡淡。与 [badgeOutline] 同值不合档(底色 vs 描边)。 */
    const val neutralOverlay: Float = 0.20f
}
