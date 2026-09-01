package com.peng.ainewshub.ui.more

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [markdownLines] 轻量 Markdown 行拆分回归(纯 JVM)。
 *
 * 钉住更新弹窗兜底渲染的契约:历史 Release 的 GitHub 自动生成 body(`## What's
 * Changed` / `* PR 标题` / `**Full Changelog:**`)不裸显 Markdown 符号 ——
 * 标题去 # 前缀记 heading、`- ` 与 `* ` 统一圆点前缀、链接只留文字、
 * `**加粗**` 标记保留给渲染层、空行过滤、普通行原样。
 */
class MarkdownLinesTest {

    @Test
    fun `GitHub 自动生成 body 去符号拆行`() {
        val md = """
            ## What's Changed
            * feat: add background download by @burgessjp in #12
            * fix: sheet state loss by @burgessjp in #13

            **Full Changelog:** https://github.com/burgessjp/AI-News-Hub/compare/v1.2.0...v1.3.0
        """.trimIndent()

        val lines = markdownLines(md)

        assertEquals(4, lines.size)
        assertEquals(MarkdownishLine(heading = true, text = "What's Changed"), lines[0])
        assertEquals(
            MarkdownishLine(heading = false, text = "• feat: add background download by @burgessjp in #12"),
            lines[1]
        )
        // 加粗标记原样保留,由渲染层(renderBoldLine)转 AnnotatedString
        assertEquals(
            MarkdownishLine(heading = false, text = "**Full Changelog:** https://github.com/burgessjp/AI-News-Hub/compare/v1.2.0...v1.3.0"),
            lines[3]
        )
    }

    @Test
    fun `各级标题去井号前缀并记为标题`() {
        val lines = markdownLines("### 新增\n###### 六级标题\n普通行")
        assertEquals(MarkdownishLine(true, "新增"), lines[0])
        assertEquals(MarkdownishLine(true, "六级标题"), lines[1])
        assertEquals(MarkdownishLine(false, "普通行"), lines[2])
    }

    @Test
    fun `减号与星号列表项统一圆点前缀`() {
        val lines = markdownLines("- CHANGELOG 风格条目\n* GitHub 风格条目")
        assertEquals(MarkdownishLine(false, "• CHANGELOG 风格条目"), lines[0])
        assertEquals(MarkdownishLine(false, "• GitHub 风格条目"), lines[1])
    }

    @Test
    fun `行内链接只保留文字`() {
        val lines = markdownLines("详见 [发布说明](https://example.com) 与 [变更记录](https://example.com/2)")
        assertEquals(MarkdownishLine(false, "详见 发布说明 与 变更记录"), lines.single())
    }

    @Test
    fun `空行与纯空白行被过滤`() {
        val lines = markdownLines("\n\n### 新增\n   \n- 条目\n")
        assertEquals(2, lines.size)
        assertEquals(MarkdownishLine(true, "新增"), lines[0])
        assertEquals(MarkdownishLine(false, "• 条目"), lines[1])
    }
}
