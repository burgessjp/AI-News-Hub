package com.peng.ainewshub.data.repo

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.peng.ainewshub.data.db.AppDatabase
import com.peng.ainewshub.data.db.SearchItemDao
import com.peng.ainewshub.data.db.SearchItemEntity

/**
 * 本地搜索索引仓库 —— 「搜索」页「本地」模式的数据源。
 *
 * 单例 object(而非构造注入):各数据源 Repository 在无 Context 的环境里直接构造
 * (见 AGENTS.md「无 DI」约定),回填索引只能走进程级单例;init 由 [com.peng.ainewshub.App]
 * 在 Application.onCreate 调用(与 ArchiveDiskCache 同范式),未初始化时所有操作静默空转。
 *
 * 回填时机:各源 Repository 成功取数后把「列表条目点击时实际打开的 URL」连同标题/
 * 摘要写入 —— 只索引用户浏览过的批次数据,天然限制表规模(万级),也保证搜索结果
 * 点击后的已读状态(browse_history 驱动)与列表行为一致。
 *
 * 查询:title/summary LIKE(FTS 默认分词不支持中文,表规模有限 LIKE 足够,
 * 见 SearchItemDao.search 注释);结果按 indexedAt 倒序近似新鲜度。
 */
object SearchIndexRepository {

    /** 抽样清理周期:每 N 次回填顺带清一次旧索引。 */
    private const val PRUNE_INTERVAL = 16

    /** 索引保留期:90 天(对齐总览/趋势历史归档窗口)。 */
    private const val RETAIN_MS = 90L * 24 * 60 * 60 * 1000

    @Volatile
    private var dao: SearchItemDao? = null

    private var writeCount = 0

    /** 初始化(Application.onCreate 调用,幂等)。 */
    fun init(context: Context) {
        if (dao != null) return
        synchronized(this) {
            if (dao == null) {
                dao = AppDatabase.get(context).searchItemDao()
            }
        }
    }

    /** 回填一条索引文档([source] 语义见 [SearchItemEntity])。 */
    data class SearchDoc(
        val url: String,
        val title: String,
        val summary: String,
        val source: String
    )

    /**
     * 批量回填(尽力而为):URL/标题任一为空的条目跳过;任何 DB 异常静默吞掉,
     * 不影响取数主流程。写入量低频(每批次每源一次),全量 REPLACE 即可。
     */
    suspend fun index(docs: List<SearchDoc>) {
        val d = dao ?: return
        runCatching {
            val now = System.currentTimeMillis()
            val entities = docs
                .filter { it.url.isNotBlank() && it.title.isNotBlank() }
                .map {
                    SearchItemEntity(
                        url = it.url.trim(),
                        title = it.title.trim(),
                        summary = it.summary.trim(),
                        source = it.source,
                        indexedAt = now
                    )
                }
            if (entities.isEmpty()) return
            d.upsertAll(entities)
            // 抽样清理:低频进行,避免每次写入都附带全表 DELETE
            if (++writeCount % PRUNE_INTERVAL == 0) {
                d.pruneBefore(now - RETAIN_MS)
            }
        }
    }

    /**
     * 本地搜索:<2 字或未初始化返回空流。返回 Flow(Room 响应式,索引回填后自动重发)。
     */
    fun search(query: String): Flow<List<SearchItemEntity>> {
        val q = query.trim()
        val d = dao ?: return flowOf(emptyList())
        if (q.length < 2) return flowOf(emptyList())
        return d.search(likePattern(q))
    }

    /** 拼 LIKE 通配模式并转义 %/_/\(配 DAO 里的 ESCAPE '\')。 */
    private fun likePattern(q: String): String {
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }
}
