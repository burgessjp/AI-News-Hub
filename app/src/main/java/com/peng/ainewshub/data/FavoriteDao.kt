package com.peng.ainewshub.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 收藏(稍后读)数据访问。
 *
 * - [upsert] 复用 INSERT OR REPLACE:同 URL 再次收藏整体替换(刷新标题/savedAt)。
 * - 所有读查询按 [FavoriteEntity.savedAt] 倒序,保证最近收藏在最前。
 */
@Dao
interface FavoriteDao {

    /**
     * 分页列表(按收藏时间倒序),取前 [limit] 条。
     * UI 用递增 limit 实现滚动加载;Flow 形式保证删除/清空时列表自动刷新。
     */
    @Query("SELECT * FROM favorites ORDER BY savedAt DESC LIMIT :limit")
    fun observePage(limit: Int): Flow<List<FavoriteEntity>>

    /** 观察单条(WebView 星标状态跟随当前 URL)。 */
    @Query("SELECT * FROM favorites WHERE url = :url LIMIT 1")
    fun observeByUrl(url: String): Flow<FavoriteEntity?>

    /** 取单条(toggle 前判存在)。 */
    @Query("SELECT * FROM favorites WHERE url = :url LIMIT 1")
    suspend fun get(url: String): FavoriteEntity?

    /** 插入或整体替换(主键冲突时 REPLACE)。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteEntity)

    /** 删除单条。 */
    @Query("DELETE FROM favorites WHERE url = :url")
    suspend fun delete(url: String)

    /** 清空全部。 */
    @Query("DELETE FROM favorites")
    suspend fun clearAll()

    /** 回写真实标题(WebView 加载完成后同步更新已收藏条目)。 */
    @Query("UPDATE favorites SET title = :title WHERE url = :url")
    suspend fun updateTitle(url: String, title: String)

    /** 收藏总数(顶栏清空按钮显隐 / hasMore 判断)。 */
    @Query("SELECT COUNT(*) FROM favorites")
    fun observeCount(): Flow<Int>
}
