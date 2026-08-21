package com.peng.ainewshub.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 浏览历史数据访问。
 *
 * - [upsert] 复用 INSERT OR REPLACE:新行直接插入;已存在的 URL 由 Repository
 *   先 [get] 出旧行、计算好 visitCount+1 后整体替换(详见 Repository 注释)。
 * - 所有读查询按 [BrowseHistoryEntity.visitedAt] 倒序,保证最近访问在最前。
 */
@Dao
interface BrowseHistoryDao {

    /**
     * 分页列表(按访问时间倒序),取前 [limit] 条。
     * UI 用递增 limit 实现滚动加载;Flow 形式保证删除/清空时列表自动刷新。
     */
    @Query("SELECT * FROM browse_history ORDER BY visitedAt DESC LIMIT :limit")
    fun observePage(limit: Int): Flow<List<BrowseHistoryEntity>>

    /** 取单条(用于 upsert 前读旧 visitCount)。 */
    @Query("SELECT * FROM browse_history WHERE url = :url LIMIT 1")
    suspend fun get(url: String): BrowseHistoryEntity?

    /** 插入或整体替换(主键冲突时 REPLACE)。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BrowseHistoryEntity)

    /** 删除单条。 */
    @Query("DELETE FROM browse_history WHERE url = :url")
    suspend fun delete(url: String)

    /** 清空全部。 */
    @Query("DELETE FROM browse_history")
    suspend fun clearAll()

    /** 回写真实标题(WebView 加载完成后更新)。 */
    @Query("UPDATE browse_history SET title = :title WHERE url = :url")
    suspend fun updateTitle(url: String, title: String)

    /**
     * 回写阅读进度(0-100;WebView 离开页面/切后台时更新)。
     * 语义对齐 [updateTitle]:只动 progress,不碰 visitCount/visitedAt ——
     * 进度回写不是一次「访问」,绝不能经 record() 走(会重复计数)。
     */
    @Query("UPDATE browse_history SET progress = :progress WHERE url = :url")
    suspend fun updateProgress(url: String, progress: Int)

    /** 历史总数(顶栏计数 / 空态判断备用)。 */
    @Query("SELECT COUNT(*) FROM browse_history")
    fun observeCount(): Flow<Int>

    /**
     * 全部已访问 URL(列表「已读」判定用)。整表只取 url 列,量级万级以内,
     * 一次取回转 Set 供各列表 O(1) 查询;Flow 保证打开文章后返回列表即自动弱化。
     */
    @Query("SELECT url FROM browse_history")
    fun observeAllUrls(): Flow<List<String>>
}
