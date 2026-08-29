package com.peng.ainewshub.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 本地搜索索引 DAO。用法与语义见 [SearchItemEntity] / [SearchIndexRepository]。
 */
@Dao
interface SearchItemDao {

    /** 批量回填(同 URL 覆盖更新)。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SearchItemEntity>)

    /**
     * 标题或摘要 LIKE 匹配,按 indexedAt 倒序取前 200 条。
     * 用 LIKE 而非 FTS:表规模有限(万级),且 FTS 默认分词器不支持中文;
     * [pattern] 由调用方拼好 % 通配并转义 %/_/\(配 ESCAPE '\')。
     */
    @Query(
        "SELECT * FROM search_items " +
            "WHERE title LIKE :pattern ESCAPE '\\' OR summary LIKE :pattern ESCAPE '\\' " +
            "ORDER BY indexedAt DESC LIMIT 200"
    )
    fun search(pattern: String): Flow<List<SearchItemEntity>>

    /** 清理 [before] 之前的旧索引(低频抽样调用,见 SearchIndexRepository)。 */
    @Query("DELETE FROM search_items WHERE indexedAt < :before")
    suspend fun pruneBefore(before: Long)
}
