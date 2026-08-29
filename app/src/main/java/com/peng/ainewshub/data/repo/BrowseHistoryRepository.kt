package com.peng.ainewshub.data.repo

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.peng.ainewshub.data.db.BrowseHistoryDao
import com.peng.ainewshub.data.db.BrowseHistoryEntity

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

    // ===== 阅读进度(「继续上次阅读」) =====

    /**
     * fire-and-forget 进度落库(0-100;0 = 无进度)。
     *
     * 调用时机是 WebView 页面销毁(onDispose)/切后台(ON_PAUSE)—— composable
     * 的 rememberCoroutineScope 同帧即被取消,写不完;故仓库持独立作用域发出写入,
     * 活过页面销毁。语义对齐 [updateTitle]:只动 progress,不碰 visitCount/visitedAt。
     */
    fun saveProgress(url: String, progress: Int) {
        if (!isRecordable(url)) return
        progressScope.launch { dao.updateProgress(url, progress.coerceIn(0, 100)) }
    }

    /** 读某 URL 的上次阅读进度(无记录/无进度返回 0;恢复判定区间由 UI 决定)。 */
    suspend fun progressOf(url: String): Int =
        if (!isRecordable(url)) 0 else (dao.get(url)?.progress ?: 0)

    /** 进度写入专用作用域:SupervisorJob + IO,仓库生命周期即进程生命周期。 */
    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 历史总数流(顶栏计数 / 空态判断备用)。 */
    fun observeCount(): Flow<Int> = dao.observeCount()

    /**
     * 已读 URL 集合(列表「已读/未读」判定):浏览历史在 openUrl 唯一入口记录,
     * 「URL 在集合中」即天然已读状态,无需独立已读表。Room Flow 自动推送 ——
     * 打开文章返回列表后弱化即时生效;删除单条历史 = 恢复未读。
     */
    fun observeReadUrls(): Flow<Set<String>> = dao.observeAllUrls().map { it.toSet() }
}

/**
 * URL 是否可入库记录:非空且 scheme 为 http/https(过滤 about:blank、javascript: 等脏值)。
 * 浏览历史与收藏共用,此前两个 Repository 各有一份私有拷贝,现收口于此。
 */
internal fun isRecordable(url: String): Boolean {
    if (url.isBlank()) return false
    val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
    return scheme == "http" || scheme == "https"
}

/** URL 的 host(解析失败或无 host 时原样返回,供收藏/历史的域名列展示)。 */
internal fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)
