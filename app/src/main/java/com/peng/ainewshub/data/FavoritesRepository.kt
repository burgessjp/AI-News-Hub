package com.peng.ainewshub.data

import android.net.Uri
import kotlinx.coroutines.flow.Flow

/**
 * 收藏(稍后读)仓库 —— 对 [FavoriteDao] 的薄封装,负责收藏时机的领域逻辑:
 *  - 解析 host(从 URL 抽域名,失败回退为原始 URL)
 *  - URL 合法性校验(空串 / 非 http(s) 直接丢弃,不产生脏数据)
 *  - toggle 语义:已收藏则删除,未收藏则落库
 *
 * 收藏入口统一在 WebView 顶栏星标(全 App 链接都经 openUrl 进内置 WebView)。
 *
 * @param dao 由 [AppDatabase] 提供
 */
class FavoritesRepository(private val dao: FavoriteDao) {

    /** 分页收藏(时间倒序),取前 [limit] 条。 */
    fun observePage(limit: Int): Flow<List<FavoriteEntity>> = dao.observePage(limit)

    /** 观察某 URL 是否已收藏(WebView 星标状态)。 */
    fun observeByUrl(url: String): Flow<FavoriteEntity?> = dao.observeByUrl(url)

    /**
     * 切换收藏状态。
     *
     * @return true = 本次为收藏;false = 本次为取消收藏(或 URL 不可收藏被丢弃)
     */
    suspend fun toggle(url: String, title: String, source: String?): Boolean {
        if (!isRecordable(url)) return false
        return if (dao.get(url) != null) {
            dao.delete(url)
            false
        } else {
            dao.upsert(
                FavoriteEntity(
                    url = url,
                    title = title.ifBlank { url },
                    host = hostOf(url),
                    source = source,
                    savedAt = System.currentTimeMillis()
                )
            )
            true
        }
    }

    /** 删除单条。 */
    suspend fun delete(url: String) = dao.delete(url)

    /**
     * 原样恢复一条记录(撤销删除用)。直接 upsert 原实体,保留其 savedAt,
     * 不走 [toggle] 的新建逻辑(撤销语义 = 恢复原状,而非重新收藏)。
     */
    suspend fun restore(entity: FavoriteEntity) = dao.upsert(entity)

    /** 清空全部。 */
    suspend fun clearAll() = dao.clearAll()

    /** 回写真实标题;未收藏该 URL 时静默跳过(UPDATE 无命中行)。 */
    suspend fun updateTitle(url: String, title: String) {
        if (title.isBlank()) return
        dao.updateTitle(url, title)
    }

    /** 收藏总数流(hasMore 判断 / 清空按钮显隐)。 */
    fun observeCount(): Flow<Int> = dao.observeCount()

    private fun isRecordable(url: String): Boolean {
        if (url.isBlank()) return false
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        return scheme == "http" || scheme == "https"
    }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)
}
