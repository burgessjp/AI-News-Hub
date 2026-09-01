package com.peng.ainewshub.ui.more

/**
 * 更新日志数据模型与解析器 —— App 内「更新日志」页（[ChangelogScreen]）的数据来源。
 *
 * 输入是仓库根 CHANGELOG.md 的原文（构建时由 app/build.gradle.kts 的 syncChangelogAssets
 * 拷入 assets，单一真相源，发版只改 CHANGELOG.md）。格式约定：
 *
 * ```
 * ## [1.2.3] - 2026-08-16      ← 版本节（## [Unreleased] 为开发中内容，跳过不展示）
 * ### 新增                     ← 分类节（新增/修复/改进/核心功能/数据流水线/工程化…）
 * - **标题** — 说明            ← 条目（**加粗**标记保留，供渲染层 AnnotatedString 加粗）
 * ```
 *
 * 解析容错：不认识的行直接忽略；文件读不到或格式异常时返回空列表，页面显示空态。
 */

/** 一个版本节点（仅已发布版本，Unreleased 节在解析时即被丢弃）。 */
internal data class ChangelogVersion(
    val version: String,
    val date: String?,
    val sections: List<ChangelogSection>
) {
    /** 该版本下所有条目总数（空版本节据此跳过）。 */
    val entryCount: Int get() = sections.sumOf { it.entries.size }
}

/** 版本内的分类小节（category 为 CHANGELOG.md 原文，如「新增」；渲染层再映射双语）。 */
internal data class ChangelogSection(
    val category: String,
    val entries: List<String>
)

/** 版本节标题：`## [1.2.3] - 2026-08-16` 或 `## [Unreleased]` / `## [未发布]`。 */
private val VERSION_HEADER_REGEX = Regex("^##\\s+\\[([^]]+)](?:\\s+-\\s+(.+))?$")

/** 解析 CHANGELOG.md 全文。输入为空或格式异常时返回空列表。 */
internal fun parseChangelog(text: String): List<ChangelogVersion> {
    val versions = mutableListOf<MutableVersion>()
    // 逐行状态机：条目行挂在最近一个「版本 + 分类」组合上
    var currentVersion: MutableVersion? = null
    var currentSection: MutableSection? = null

    fun closeVersion() {
        currentVersion?.let(versions::add)
        currentVersion = null
        currentSection = null
    }

    text.lineSequence().forEach { rawLine ->
        val line = rawLine.trimEnd()
        when {
            line.startsWith("### ") -> {
                val category = line.removePrefix("### ").trim()
                currentSection = category.takeIf { it.isNotEmpty() }
                    ?.let { key -> currentVersion?.addSection(key) }
            }
            line.startsWith("## ") -> {
                closeVersion()
                val match = VERSION_HEADER_REGEX.find(line)
                if (match != null) {
                    val tag = match.groupValues[1].trim()
                    // Unreleased/未发布 是开发中内容，App 内不展示
                    val unreleased = tag.equals("unreleased", ignoreCase = true) || tag == "未发布"
                    if (!unreleased) {
                        currentVersion = MutableVersion(
                            version = tag,
                            date = match.groupValues[2].trim().ifEmpty { null }
                        )
                    }
                }
            }
            line.startsWith("- ") -> {
                val entry = line.removePrefix("- ").trim()
                if (entry.isNotEmpty()) currentSection?.addEntry(entry)
            }
            else -> Unit // 文件头、空行、正文段落（如 1.0 的「首个公开发布版本。」）忽略
        }
    }
    closeVersion()
    return versions.map(MutableVersion::toImmutable).filter { it.entryCount > 0 }
}

/** 解析过程中的可变中间结构，收尾统一转不可变数据类。 */
private class MutableVersion(val version: String, val date: String?) {
    private val sections = mutableListOf<MutableSection>()

    fun addSection(category: String): MutableSection =
        MutableSection(category).also(sections::add)

    fun toImmutable() = ChangelogVersion(version, date, sections.map(MutableSection::toImmutable))
}

private class MutableSection(val category: String) {
    private val entries = mutableListOf<String>()

    fun addEntry(entry: String) {
        entries += entry
    }

    fun toImmutable() = ChangelogSection(category, entries.toList())
}

/** 轻量 Markdown 兜底渲染的单行：heading = 原标题行（去 # 前缀）；列表项文本已去标记。 */
internal data class MarkdownishLine(val heading: Boolean, val text: String)

/** Markdown 行内链接 `[文字](url)`；Release body 兜底时仅保留文字。 */
private val LINK_REGEX = Regex("\\[([^]]+)]\\(([^)]+)\\)")

/**
 * 轻量 Markdown 行拆分 —— 更新弹窗 Release body 的兜底渲染用。
 *
 * [parseChangelog] 认不出条目时（历史 Release 的 GitHub 自动生成 body：`## What's
 * Changed` / `* PR 标题` / `**Full Changelog:**`，不含 CHANGELOG 版本节格式），
 * 退到本拆分按行直读，避免整段原样展示 Markdown 符号：
 *  - `#`~`######` 标题行去前缀，记 heading（渲染层强调）；
 *  - `- ` / `* ` 列表项去标记，加 `• ` 前缀（自动生成 body 用 `*`，CHANGELOG 用 `-`，统一处理）；
 *  - `[文字](url)` 只留文字（弹窗内不可点）；
 *  - `**加粗**` 标记原样保留，交渲染层（renderBoldLine）转 AnnotatedString；
 *  - 空行过滤；其余行原样。
 *
 * 不追求完整 Markdown：代码块/表格/图片 Release body 罕见，不支持。
 */
internal fun markdownLines(md: String): List<MarkdownishLine> =
    md.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val heading = HEADING_LINE_REGEX.find(line)
            when {
                heading != null -> MarkdownishLine(heading = true, text = linkText(heading.groupValues[1].trim()))
                line.startsWith("- ") || line.startsWith("* ") ->
                    MarkdownishLine(heading = false, text = "• " + linkText(line.substring(2).trim()))
                else -> MarkdownishLine(heading = false, text = linkText(line))
            }
        }
        .toList()

/** Markdown 标题行 `#`~`######` 前缀。 */
private val HEADING_LINE_REGEX = Regex("^#{1,6}\\s+(.+)$")

/** `[文字](url)` → `文字`；无链接语法时原样返回（避免无谓的正则替换）。 */
private fun linkText(s: String): String =
    if (s.contains("](")) LINK_REGEX.replace(s) { it.groupValues[1] } else s
