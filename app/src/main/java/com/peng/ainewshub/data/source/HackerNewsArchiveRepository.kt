package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.HackerNewsStory
import com.peng.ainewshub.data.HackerNewsTopStories
import com.peng.ainewshub.data.SearchIndexRepository
import com.peng.ainewshub.data.SourceKeys

/**
 * HackerNews 的 [gitcode 归档]数据源。
 *
 * 数据来自数据流水线每天三批归档的快照(见 docs/news-hub-data-usage.md)。
 * 无缓存概念:归档本身是历史快照,fetch == forceRefresh,每次直接拉最新。
 *
 * 失败处理:index 无 hackernews / 快照缺失 / items 为空 / 网络错误 → 抛 RuntimeException,
 * 交由 ViewModel 显示 Error 态。
 */
class HackerNewsArchiveRepository {

    suspend fun fetch(): HackerNewsTopStories = load()

    suspend fun forceRefresh(): HackerNewsTopStories = load()

    private suspend fun load(): HackerNewsTopStories {
        val (fetchedAt, stories) = ArchiveHttpClient.fetchItemsList(SourceKeys.HACKERNEWS) { obj, _ ->
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
                // 归档快照不带评论树(kids),列表页用不到;评论页进入时经
                // HackerNewsRepository.fetchStoryKids 实时补全
                kids = emptyList()
            )
        }
        // 本地搜索索引回填:尽力而为,失败静默(见 SearchIndexRepository)
        SearchIndexRepository.index(
            stories.map {
                SearchIndexRepository.SearchDoc(
                    url = it.targetUrl,
                    title = it.title,
                    summary = "",
                    source = SourceKeys.HACKERNEWS
                )
            }
        )
        return HackerNewsTopStories(fetchedAt, stories)
    }
}
