package com.peng.ainewshub

import android.app.Application
import com.peng.ainewshub.data.repo.SearchIndexRepository
import com.peng.ainewshub.data.source.ArchiveDiskCache

/**
 * 应用入口 —— 初始化无 Activity 依赖的全局组件。
 *
 * 归档磁盘缓存与本地搜索索引在此初始化(而非 MainActivity):桌面小组件更新等
 * 不经 MainActivity 的入口也会走 ArchiveHttpClient 取数,需保证任何进程入口
 * 都能落盘兜底 / 回填索引。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        ArchiveDiskCache.init(this)
        SearchIndexRepository.init(this)
    }
}
