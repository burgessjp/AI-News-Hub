package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.HackerNewsStory
import com.peng.ainewshub.data.HackerNewsTopStories

/**
 * HackerNews 的 [gitcode 归档]数据源实现。
 *
 * 与 [com.peng.ainewshub.data.HackerNewsRepository](实时)并列,实现同一 [HackerNewsSource]
 * 接口。数据来自 GitHub Action 每天 08:00 归档的快照(见 docs/news-hub-data-usage.md)。
 *
 * 无缓存概念:归档本身是历史快照,fetch == forceRefresh,每次直接拉最新。
 * limit 参数被忽略(归档条数固定,按归档时实时抓的 20 条返回)。
 *
 * 失败处理:index 无 hackernews / 快照缺失 / items 为空 / 网络错误 → 抛 RuntimeException,
 * 交由 ViewModel 显示 Error 态(归档模式明确提示,不回退实时)。
 */
class HackerNewsArchiveRepository : HackerNewsSource {

    override suspend fun fetch(limit: Int): HackerNewsTopStories = load()

    override suspend fun forceRefresh(limit: Int): HackerNewsTopStories = load()

    private suspend fun load(): HackerNewsTopStories {
        val (fetchedAt, stories) = ArchiveHttpClient.fetchItemsList(SOURCE_KEY) { obj, _ ->
            val id = obj.optLong("id", -1L)
            if (id <= 0) null
            else HackerNewsStory(
                id = id,
                title = obj.optString("title"),
                url = obj.optString("url"),
                by = obj.optString("by"),
                score = obj.optInt("score", 0),
                descendants = obj.optInt("descendants", 0),
                time = obj.optLong("time", 0L),
                // 归档快照不带评论树(kids),列表页用不到;评论页仅实时模式可进
                kids = emptyList()
            )
        }
        return HackerNewsTopStories(fetchedAt, stories)
    }

    private companion object {
        // 对齐 index.json 的 latest 键与目录名(fetch_data.py 中定义)
        const val SOURCE_KEY = "hackernews"
    }
}
