package com.peng.ainewshub.ui.nav

import android.os.Bundle
import com.peng.ainewshub.data.model.HackerNewsStory
import com.peng.ainewshub.data.model.NewsItem
import com.peng.ainewshub.ui.anim.PageNavStyle
import com.peng.ainewshub.ui.components.AppTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 导航页栈的 Bundle 序列化契约(进程死亡恢复的根基,见 docs/agents/navigation.md)。
 *
 * 两层护栏:
 *  - 完整性:Page 密封子类数量(经 Java sealed permittedSubclasses,无反射依赖)必须
 *    与下方样例覆盖的类型数一致 —— 新增页面忘了补样例时第一时间失败;
 *  - 往返:每个页面 toBundle → pageFromBundle 后与原对象相等(含「三处同步」的
 *    Bundle 分支正确性)。
 */
@RunWith(RobolectricTestRunner::class)
class PageBundleTest {

    /** 全部页面类型的代表性样例(Web/LocalSearch 各带默认值变体)。 */
    private val samplePages: List<Page> = listOf(
        Page.Detail(NewsItem(id = "cuid-1", title = "标题", url = "https://e.com/a")),
        Page.Web("https://e.com/b", "页面标题", "GitHub Trending"),
        Page.Web("https://e.com/c", "页面标题", null),
        Page.DailyArchive,
        Page.DailyDate("2026-08-01"),
        Page.All,
        Page.Daily,
        Page.Search,
        Page.LocalSearch("初始词"),
        Page.LocalSearch(""),
        Page.Settings,
        Page.AiService,
        Page.About,
        Page.AboutSources,
        Page.AboutOss,
        Page.Changelog,
        Page.HackerNews,
        Page.HackerNewsComments(HackerNewsStory(id = 7, title = "story", kids = listOf(1, 2))),
        Page.GitHubTrending,
        Page.StormzhangAiNews,
        Page.HuggingFacePapers,
        Page.ProductHunt,
        Page.RundownAi,
        Page.OpenAiAnthropicNews,
        Page.FeaturedHub,
        Page.Sources,
        Page.BrowseHistory,
        Page.Favorites,
        Page.HistoryHub,
        Page.SummaryDate("2026-08-01"),
        Page.OverviewDate("2026-08-01"),
        Page.TrendsDate("2026-08-01"),
        Page.TrendsCloud
    )

    @Test
    fun `页面类型全量覆盖护栏`() {
        // 新增 Page 子类型时:同步 toBundle/pageFromBundle 两处分支(编译器只给警告)
        // 并在此补样例,否则下一个往返用例会漏测新类型
        assertEquals(samplePages.map { it::class }.distinct().size, Page::class.java.permittedSubclasses!!.size)
    }

    @Test
    fun `每个页面 Bundle 往返后保持相等`() {
        samplePages.forEach { page ->
            val restored = pageFromBundle(page.toBundle(), "兜底标题")
            assertEquals("往返失败: ${page::class.simpleName}", page, restored)
        }
    }

    @Test
    fun `未知 tag 与缺内容返回 null 或兜底`() {
        assertNull(pageFromBundle(Bundle().apply { putString("t", "FutureType") }, "兜底标题"))

        val webNoTitle = Bundle().apply {
            putString("t", "Web")
            putString("url", "https://e.com/d")
            // 缺 title → 用兜底文案占位(随语言取词,见 pageFromBundle)
        }
        assertEquals(Page.Web("https://e.com/d", "兜底标题", null), pageFromBundle(webNoTitle, "兜底标题"))
    }

    @Test
    fun `各 tab 页栈整体序列化往返`() {
        val stacks = mapOf(
            AppTab.Overview to listOf(Page.Settings, Page.Web("https://e.com", "t", null), Page.LocalSearch("kw")),
            AppTab.Summary to listOf(Page.SummaryDate("2026-08-01")),
            AppTab.Follows to emptyList<Page>(),
            AppTab.Trends to listOf(Page.TrendsCloud),
            AppTab.More to listOf(Page.HistoryHub, Page.Favorites)
        )
        assertEquals(stacks, stacksFromBundle(stacksToBundle(stacks), "兜底标题"))
    }

    @Test
    fun `转场风格契约 Web 为 FADE 其余 PUSH`() {
        // WebView 位移会撕裂,Web 页恒 FADE;该契约被 Motion 查表消费
        assertEquals(PageNavStyle.FADE, Page.Web("https://e.com", "t").navStyle)
        assertEquals(PageNavStyle.PUSH, Page.Settings.navStyle)
        assertEquals(PageNavStyle.PUSH, Page.Detail(NewsItem(id = "1")).navStyle)
    }
}
