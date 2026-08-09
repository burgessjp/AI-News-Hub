package com.peng.ainewshub.ui.more

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.peng.ainewshub.R
import com.peng.ainewshub.data.SourceKeys

/**
 * Hub 八源元数据单点定义 —— 信息源页 / 摘要 Tab / 关于页三处的源顺序、图标、标题、
 * 副标题、品牌色、跳转 URL 统一收口于此,消除此前三处各自硬编码、顺序互不一致、
 * About 漏 OpenAI×Anthropic 等问题。
 *
 * - **默认顺序** [DEFAULT_SOURCE_ORDER]:HackerNews → GitHub Trending → OpenAI×Anthropic
 *   → HuggingFace Papers → Product Hunt → The Rundown AI → AIHot 精选 → stormzhang AI。
 *   此为全 App 默认顺序,信息源页可拖拽自定义(持久化于 [SettingsStore.sourceOrderFlow]),
 *   摘要 Tab 跟随用户顺序,关于页固定用此默认顺序。
 * - **源 key**:字面量集中定义于 [com.peng.ainewshub.data.SourceKeys](data 层),
 *   与归档源 key(见 [com.peng.ainewshub.data.SummaryRepository])完全一致,
 *   摘要 Tab / PagerState pageCount 均按 key 列表驱动。
 * - **品牌色**:[brand] 复用 [SourceBrand](颜色收口仍归 SourceBrandColors.kt,本文件只引用)。
 */
@Immutable
data class SourceMeta(
    val key: String,
    val icon: ImageVector,
    /** 品牌色块(SourceBrand.GitHub 为 @Composable 属性,故本字段在 Composable 上下文求值后存入)。 */
    val brand: SourceBrandColors,
    val title: String,
    val subtitle: String,
    /** 关于页「数据来源」跳转 URL(走内置 WebView)。 */
    val url: String
)

/**
 * 八源查表(key → 元数据)。未知 key 抛出,确保调用方传 key 时编译期覆盖完整。
 * 需在 Composable 上下文调用(SourceBrand.GitHub 读取深浅色主题)。
 */
@Composable
fun sourceMeta(key: String): SourceMeta = when (key) {
    SourceKeys.HACKERNEWS -> SourceMeta(
        key, Icons.Filled.Whatshot, SourceBrand.HackerNews,
        stringResource(R.string.source_title_hackernews), stringResource(R.string.source_subtitle_hackernews), "https://news.ycombinator.com"
    )
    SourceKeys.GITHUB_TRENDING -> SourceMeta(
        key, Icons.Filled.Code, SourceBrand.GitHub,
        stringResource(R.string.source_title_github_trending), stringResource(R.string.source_subtitle_github_trending), "https://github.com/trending"
    )
    SourceKeys.OPENAI_ANTHROPIC_NEWS -> SourceMeta(
        key, Icons.Filled.Business, SourceBrand.OpenAiAnthropicNews,
        stringResource(R.string.source_title_openai_anthropic), stringResource(R.string.source_subtitle_openai_anthropic), "https://openai.com"
    )
    SourceKeys.HUGGINGFACE_PAPERS -> SourceMeta(
        key, Icons.Filled.School, SourceBrand.HuggingFace,
        stringResource(R.string.source_title_huggingface), stringResource(R.string.source_subtitle_huggingface), "https://huggingface.co/papers/trending"
    )
    SourceKeys.PRODUCTHUNT -> SourceMeta(
        key, Icons.Filled.RocketLaunch, SourceBrand.ProductHunt,
        stringResource(R.string.source_title_producthunt), stringResource(R.string.source_subtitle_producthunt), "https://www.producthunt.com"
    )
    SourceKeys.RUNDOWN_AI -> SourceMeta(
        key, Icons.AutoMirrored.Filled.Article, SourceBrand.TheRundownAi,
        stringResource(R.string.source_title_rundown), stringResource(R.string.source_subtitle_rundown), "https://www.therundown.ai"
    )
    SourceKeys.AIHOT_FEATURED -> SourceMeta(
        key, Icons.Filled.Whatshot, SourceBrand.AiHot,
        stringResource(R.string.source_title_aihot_featured), stringResource(R.string.source_subtitle_aihot_featured), "https://aihot.virxact.com"
    )
    SourceKeys.STORMZHANG_AI -> SourceMeta(
        key, Icons.Filled.Bolt, SourceBrand.Stormzhang,
        stringResource(R.string.source_title_stormzhang), stringResource(R.string.source_subtitle_stormzhang), "https://news.stormzhang.ai"
    )
    else -> error("未知源 key: $key")
}

/**
 * 全 App 默认源顺序(8 源)。
 *
 * 用户在「信息源」页拖拽后的自定义顺序持久化于 [SettingsStore.sourceOrderFlow],
 * 读取时会以本常量为兜底(只保留已知 key + 补全缺失 key 到末尾)。
 */
val DEFAULT_SOURCE_ORDER: List<String> = listOf(
    SourceKeys.HACKERNEWS,
    SourceKeys.GITHUB_TRENDING,
    SourceKeys.OPENAI_ANTHROPIC_NEWS,
    SourceKeys.HUGGINGFACE_PAPERS,
    SourceKeys.PRODUCTHUNT,
    SourceKeys.RUNDOWN_AI,
    SourceKeys.AIHOT_FEATURED,
    SourceKeys.STORMZHANG_AI
)
