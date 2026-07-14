package com.example.aihot.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * App 数据库 —— 目前仅承载浏览历史。
 *
 * 单例:同一进程复用同一实例,避免 Room 打开多个文件句柄。
 * [fallbackToDestructiveMigration]:历史数据可丢,迁移缺失时直接重建表,
 * 不为 v1 投入正式迁移成本(后续正式迭代再加 Migration)。
 */
@Database(
    entities = [BrowseHistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun browseHistoryDao(): BrowseHistoryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aihot.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
