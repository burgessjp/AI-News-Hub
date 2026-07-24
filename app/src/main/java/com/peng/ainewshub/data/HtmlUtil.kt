package com.peng.ainewshub.data

import android.text.Html

/**
 * HTML 处理工具。
 *
 * HackerNews 评论正文是 HTML(`<p>`/`<a>`/`<i>` 等),UI 用 `AnnotatedString.fromHtml` 渲染。
 * 翻译前需剥成纯文本发给 LLM —— 用平台 [Html.fromHtml] 把标签转义为文本,
 * 不引入额外依赖。译文是纯文本,不回填标签,避免 LLM 破坏标签结构导致渲染崩溃。
 */
object HtmlUtil {
    fun stripHtml(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().trim()
}
