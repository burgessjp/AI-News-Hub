package com.peng.ainewshub.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peng.ainewshub.data.db.AppDatabase
import com.peng.ainewshub.data.repo.BrowseHistoryRepository

/**
 * 已读 URL 集合(全 App 共用)—— 浏览历史(openUrl 唯一入口记录)驱动列表
 * 「已读/未读」判定,见 [BrowseHistoryRepository.observeReadUrls]。
 *
 * 各列表页一行取用:`val readUrls = rememberReadUrls()`,再以
 * `url in readUrls` 判定。Repository 按 AGENTS.md「无 DI、Composable 内直接构造」
 * 就地建(同一 Room DAO,进程内单例数据库,无额外开销)。
 */
@Composable
fun rememberReadUrls(): Set<String> {
    val context = LocalContext.current
    val repo = remember {
        BrowseHistoryRepository(AppDatabase.get(context).browseHistoryDao())
    }
    return repo.observeReadUrls()
        .collectAsStateWithLifecycle(initialValue = emptySet())
        .value
}
