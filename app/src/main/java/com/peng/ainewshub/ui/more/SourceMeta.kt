package com.peng.ainewshub.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import com.peng.ainewshub.R
import com.peng.ainewshub.data.source.SourceKeys

/**
 * Hub 八源元数据单点定义 —— 信息源页 / 「今天」页分源区块 / 关于页三处的源顺序、标题、
 * 副标题、品牌色、跳转 URL 统一收口于此,消除此前三处各自硬编码、顺序互不一致、
 * About 漏 OpenAI×Anthropic 等问题。
 *
 * - **默认顺序** [DEFAULT_SOURCE_ORDER]:HackerNews → GitHub Trending → OpenAI×Anthropic
 *   → HuggingFace Papers → Product Hunt → The Rundown AI → AIHot 精选 → stormzhang AI。
 *   此为全 App 默认顺序,信息源页可拖拽自定义(持久化于 [SettingsStore.sourceOrderFlow]),
 *   「今天」页分源区块跟随用户顺序,关于页固定用此默认顺序。
 * - **源 key**:字面量集中定义于 [com.peng.ainewshub.data.source.SourceKeys](data 层),
 *   与归档源 key(见 [com.peng.ainewshub.data.repo.SummaryRepository])完全一致,
 *   分源区块与源页跳转分发均按 key 列表驱动。
 * - **品牌色**:[brand] 复用 [SourceBrand](颜色收口仍归 SourceBrandColors.kt,本文件只引用)。
 */
@Immutable
data class SourceMeta(
    val key: String,
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
        key, SourceBrand.HackerNews,
        stringResource(R.string.source_title_hackernews), stringResource(R.string.source_subtitle_hackernews), "https://news.ycombinator.com"
    )
    SourceKeys.GITHUB_TRENDING -> SourceMeta(
        key, SourceBrand.GitHub,
        stringResource(R.string.source_title_github_trending), stringResource(R.string.source_subtitle_github_trending), "https://github.com/trending"
    )
    SourceKeys.OPENAI_ANTHROPIC_NEWS -> SourceMeta(
        key, SourceBrand.OpenAiAnthropicNews,
        stringResource(R.string.source_title_openai_anthropic), stringResource(R.string.source_subtitle_openai_anthropic), "https://openai.com"
    )
    SourceKeys.HUGGINGFACE_PAPERS -> SourceMeta(
        key, SourceBrand.HuggingFace,
        stringResource(R.string.source_title_huggingface), stringResource(R.string.source_subtitle_huggingface), "https://huggingface.co/papers/trending"
    )
    SourceKeys.PRODUCTHUNT -> SourceMeta(
        key, SourceBrand.ProductHunt,
        stringResource(R.string.source_title_producthunt), stringResource(R.string.source_subtitle_producthunt), "https://www.producthunt.com"
    )
    SourceKeys.RUNDOWN_AI -> SourceMeta(
        key, SourceBrand.TheRundownAi,
        stringResource(R.string.source_title_rundown), stringResource(R.string.source_subtitle_rundown), "https://www.therundown.ai"
    )
    SourceKeys.AIHOT_FEATURED -> SourceMeta(
        key, SourceBrand.AiHot,
        stringResource(R.string.source_title_aihot_featured), stringResource(R.string.source_subtitle_aihot_featured), "https://aihot.virxact.com"
    )
    SourceKeys.STORMZHANG_AI -> SourceMeta(
        key, SourceBrand.Stormzhang,
        stringResource(R.string.source_title_stormzhang), stringResource(R.string.source_subtitle_stormzhang), "https://news.stormzhang.ai"
    )
    else -> error("未知源 key: $key")
}
