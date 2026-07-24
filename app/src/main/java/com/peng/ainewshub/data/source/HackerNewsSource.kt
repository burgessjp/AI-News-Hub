package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.HackerNewsTopStories

/**
 * HackerNews 数据源抽象 —— [com.peng.ainewshub.data.HackerNewsRepository](实时)
 * 与 [HackerNewsArchiveRepository](gitcode 归档)的共同接口。
 *
 * ViewModel 依赖本接口,按 [SourceMode] 选择具体实现,两种模式可互换。
 *
 * 注意:HN 实时 Repository 的方法名是 fetchTopStories/forceRefresh(带 limit),
 * 这里统一成 fetch/forceRefresh(带默认 limit=20)以与其余 4 个源接口对齐;
 * 实时 Repository 用扩展函数/override 桥接到原方法,归档 Repository 忽略 limit。
 */
interface HackerNewsSource {
    /** 拉取 Top Stories(实时:走缓存;归档:直接拉最新快照)。 */
    suspend fun fetch(limit: Int = 20): HackerNewsTopStories

    /** 强制刷新(实时:忽略缓存;归档:等同 fetch,归档本身无缓存概念)。 */
    suspend fun forceRefresh(limit: Int = 20): HackerNewsTopStories
}
