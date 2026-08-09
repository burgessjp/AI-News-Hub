package com.peng.ainewshub.data

/**
 * 八源 key 字面量集中定义 —— 全 App 源标识的**唯一真相源**。
 *
 * 各处(归档 Repository、摘要 Repository、源元数据、UI 跳转分发、强调色 when 分支等)
 * 一律引用本 object 的常量,不写裸字符串。这样:
 *  - 新增/重命名源时只改这一处,所有引用编译期同步;
 *  - 杜绝某处 key 漂移(如 `"rundown-ai"` 误写成 `"rundown_ai"`)导致的静默断裂
 *    —— 此前 SummaryCard 强调色 / SummaryScreen 跳转的 when 分支用裸字符串,
 *    key 拼错只会让该源回退默认色 / 跳转失效,编译期无任何报错。
 *
 * 这些 key 与数据流水线(scripts/)写入 index.json / 快照的源标识完全一致,
 * 是 App 与流水线之间的契约字符串(跨语言、跨进程,无法用枚举统一,只能靠常量收口)。
 *
 * 原位于 [com.peng.ainewshub.ui.more.SourceMeta](UI 层),因 data 层(归档 Repository /
 * SummarySource)也需要引用,提升到 data 层避免反向依赖(data → ui)。
 */
object SourceKeys {
    const val HACKERNEWS = "hackernews"
    const val GITHUB_TRENDING = "github-trending"
    const val OPENAI_ANTHROPIC_NEWS = "openai-anthropic-news"
    const val HUGGINGFACE_PAPERS = "huggingface-papers"
    const val PRODUCTHUNT = "producthunt"
    const val RUNDOWN_AI = "rundown-ai"
    const val AIHOT_FEATURED = "aihot-featured"
    const val STORMZHANG_AI = "stormzhang-ai"
}
