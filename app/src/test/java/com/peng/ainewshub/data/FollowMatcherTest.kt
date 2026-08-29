package com.peng.ainewshub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.peng.ainewshub.data.repo.FollowCorpusEntry
import com.peng.ainewshub.data.repo.FollowMatcher

/**
 * [FollowMatcher] 关键词匹配规则回归 —— 「我的关注」过滤核心,规则细节见被测类 KDoc。
 *
 * 重点钉住两类边界语义(最易回归):
 *  - 拉丁词必须词边界命中(「AI」不得命中 said/main/email,但「Claude发布」要命中)
 *  - 非拉丁词(中文/数字)维持子串包含
 */
class FollowMatcherTest {

    private fun entry(title: String = "", desc: String = "", url: String = "https://example.com") =
        FollowCorpusEntry(source = "hackernews", title = title, desc = desc, url = url, fromOverview = false)

    private fun matchedTitles(
        entries: List<FollowCorpusEntry>,
        keywords: List<String>,
        selected: String? = null
    ): List<String> = FollowMatcher.filter(entries, keywords, selected).map { it.entry.title }

    // ===== 拉丁词:词边界匹配 =====

    @Test
    fun `短拉丁词不误命中英文子串`() {
        val corpus = listOf(
            entry(title = "He said the email was main point"),
            entry(title = "AI 发布新模型")
        )
        // "AI" 不命中 said/email/main;命中独立出现的 AI
        assertEquals(listOf("AI 发布新模型"), matchedTitles(corpus, listOf("AI")))
    }

    @Test
    fun `拉丁词命中括号与汉字相邻场景`() {
        val corpus = listOf(
            entry(title = "(Claude) 登顶榜单"),
            entry(title = "Claude发布"),
            entry(title = "Claude 4 Opus 评测")
        )
        assertEquals(3, FollowMatcher.filter(corpus, listOf("Claude"), null).size)
    }

    @Test
    fun `带连字符的词不误命中更长的数字后缀`() {
        val corpus = listOf(entry(title = "GPT-50 定价公布"))
        // "GPT-5" 右邻 '0' 是 ASCII 数字 → 非词边界,不命中
        assertTrue(FollowMatcher.filter(corpus, listOf("GPT-5"), null).isEmpty())
    }

    // ===== 非拉丁词:子串包含 =====

    @Test
    fun `中文关键词按子串命中`() {
        val corpus = listOf(
            entry(title = "多智能体协作新框架", desc = ""),
            entry(title = " unrelated story", desc = "")
        )
        assertEquals(listOf("多智能体协作新框架"), matchedTitles(corpus, listOf("智能体")))
    }

    @Test
    fun `匹配文本包含标题与摘要正文`() {
        val corpus = listOf(entry(title = "无关键词标题", desc = "但摘要里提到了 Anthropic"))
        assertEquals(1, FollowMatcher.filter(corpus, listOf("Anthropic"), null).size)
    }

    // ===== 过滤行为 =====

    @Test
    fun `命中任一关键词即收录且保留用户输入的原样大小写`() {
        val corpus = listOf(entry(title = "OpenAI 发布 GPT-5"))
        val result = FollowMatcher.filter(corpus, listOf("GPT-5", "OpenAI"), null)
        assertEquals(1, result.size)
        // 命中词按用户输入原样返回,供标签展示(不做大小写归一)
        assertEquals(setOf("OpenAI", "GPT-5"), result.single().matchedKeywords.toSet())
    }

    @Test
    fun `selected 单选过滤只保留命中该词的条目`() {
        val corpus = listOf(
            entry(title = "Claude 更新", url = "https://e.com/1"),
            entry(title = "GPT-5 发布", url = "https://e.com/2")
        )
        assertEquals(listOf("GPT-5 发布"), matchedTitles(corpus, listOf("Claude", "GPT-5"), selected = "GPT-5"))
    }

    @Test
    fun `空关键词或全空白关键词返回空`() {
        val corpus = listOf(entry(title = "AI Agent 时代"))
        assertTrue(FollowMatcher.filter(corpus, emptyList(), null).isEmpty())
        assertTrue(FollowMatcher.filter(corpus, listOf("  ", ""), null).isEmpty())
    }

    @Test
    fun `结果保持语料顺序`() {
        val corpus = listOf(
            entry(title = "第一条 AI", url = "https://e.com/1"),
            entry(title = "中间无关条目", url = "https://e.com/2"),
            entry(title = "第二条 AI", url = "https://e.com/3")
        )
        assertEquals(listOf("第一条 AI", "第二条 AI"), matchedTitles(corpus, listOf("AI")))
    }
}
