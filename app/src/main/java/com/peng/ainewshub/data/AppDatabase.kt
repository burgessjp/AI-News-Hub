package com.peng.ainewshub.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * App 数据库 —— 承载浏览历史与收藏(稍后读)。
 *
 * 单例:同一进程复用同一实例,避免 Room 打开多个文件句柄。
 *
 * 版本史:
 *  - v1:仅 browse_history(当时可丢,[fallbackToDestructiveMigration] 直接重建)
 *  - v2:新增 favorites 表。老设备升级走 [MIGRATION_1_2](仅 CREATE TABLE),
 *    保住既有浏览历史;destructive 兜底仍保留,迁移缺失时重建(开发期分支)。
 *  - v3:新增 search_items 本地搜索索引表([SearchItemEntity])。升级走
 *    [MIGRATION_2_3](仅 CREATE TABLE,旧表不受影响)。
 *  - v4:本地搜索索引改为只收本 App 归档数据 —— 清掉开发期由 aihot 实时 API
 *    流回填的站内阅读页条目(schema 不变,仅 DELETE,见 [MIGRATION_3_4])。
 */
@Database(
    entities = [BrowseHistoryEntity::class, FavoriteEntity::class, SearchItemEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun browseHistoryDao(): BrowseHistoryDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun searchItemDao(): SearchItemDao

    companion object {
        /** v1 → v2:新增收藏表(不影响既有 browse_history 数据)。 */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorites` (" +
                        "`url` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`host` TEXT NOT NULL, " +
                        "`source` TEXT, " +
                        "`savedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`url`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_favorites_savedAt` ON `favorites` (`savedAt`)"
                )
            }
        }

        /** v2 → v3:新增本地搜索索引表(不影响既有浏览历史/收藏数据)。 */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `search_items` (" +
                        "`url` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`summary` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`indexedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`url`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_search_items_indexedAt` ON `search_items` (`indexedAt`)"
                )
            }
        }

        /**
         * v3 → v4:本地搜索定位收窄为「本 App 归档数据」—— 删除开发期由 aihot
         * 实时 API 流(NewsRepository,后已移除回填)写入的站内阅读页条目。
         * 表结构不变,仅数据清理;其余行(归档源条目)保留。
         */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM `search_items` WHERE `url` LIKE 'https://aihot.virxact.com%'")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ainewshub.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
