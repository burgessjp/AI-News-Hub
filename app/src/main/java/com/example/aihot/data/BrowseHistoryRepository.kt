package com.example.aihot.data

import android.net.Uri
import kotlinx.coroutines.flow.Flow

/**
 * 浏览历史仓库 —— 对 [BrowseHistoryDao] 的薄封装,负责记录时机的领域逻辑:
 *  - 解析 host(从 URL 抽域名,失败回退为原始 URL)
 *  - URL 合法性校验(空串 / 非 http(s) 直接丢弃,不污染历史)
 *  - upsert 计数:同 URL 已存在则 visitCount+1、visitedAt 刷新;否则新建
 *
 * 记录点统一在 MainActivity 的 openUrl,全 App 覆盖。
 *
 * @param dao 由 [AppDatabase] 提供
 */
class BrowseHistoryRepository(private val dao: BrowseHistoryDao) {

    /** 分页历史(时间倒序),取前 [limit] 条。 */
    fun observePage(limit: Int): Flow<List<BrowseHistoryEntity>> = dao.observePage(limit)

    /**
     * 记录一次访问。
     *
     * @param url 原始 URL
     * @param title 标题(可能是占位"加载中…",WebView 加载完成后由 [updateTitle] 修正)
     * @param source 来源标签(可空)
     */
    suspend fun record(url: String, title: String, source: String?) {
        if (!isRecordable(url)) return
        val host = hostOf(url)
        val now = System.currentTimeMillis()
        val existing = dao.get(url)
        val entity = if (existing != null) {
            existing.copy(
                title = if (title.isNotBlank()) title else existing.title,
                host = host,
                // source 取本次传入;若本次为空则保留旧值,避免历史标签被抹掉
                source = source ?: existing.source,
                visitedAt = now,
                visitCount = existing.visitCount + 1
            )
        } else {
            BrowseHistoryEntity(
                url = url,
                title = title.ifBlank { url },
                host = host,
                source = source,
                visitedAt = now,
                visitCount = 1
            )
        }
        dao.upsert(entity)
    }

    /** 删除单条。 */
    suspend fun delete(url: String) = dao.delete(url)

    /**
     * 原样恢复一条记录(撤销删除用)。直接 upsert 原实体,保留其 visitCount/visitedAt,
     * 不走 [record] 的计数逻辑(撤销语义 = 恢复原状,而非新增一次访问)。
     */
    suspend fun restore(entity: BrowseHistoryEntity) = dao.upsert(entity)

    /** 清空全部。 */
    suspend fun clearAll() = dao.clearAll()

    /** 回写真实标题(不更新访问时间,避免刷新标题把条目顶到最前)。 */
    suspend fun updateTitle(url: String, title: String) {
        if (title.isBlank()) return
        dao.updateTitle(url, title)
    }

    /** 历史总数流(顶栏计数 / 空态判断备用)。 */
    fun observeCount(): Flow<Int> = dao.observeCount()

    private fun isRecordable(url: String): Boolean {
        if (url.isBlank()) return false
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        return scheme == "http" || scheme == "https"
    }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)
}
