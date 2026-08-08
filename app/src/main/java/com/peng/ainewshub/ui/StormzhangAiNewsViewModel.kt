package com.peng.ainewshub.ui

import android.app.Application
import com.peng.ainewshub.data.SourceListResult
import com.peng.ainewshub.data.StormzhangAiNews
import com.peng.ainewshub.data.StormzhangAiNewsRepository
import com.peng.ainewshub.data.StormzhangAiNewsResult
import com.peng.ainewshub.data.source.StormzhangAiNewsArchiveRepository
import com.peng.ainewshub.data.source.StormzhangAiNewsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * stormzhang AI Daily ViewModel。
 *
 * 继承 [SourceListViewModel]:sourceMode 订阅 / state / refresh / forceRefresh 等公共逻辑
 * 由基类统一。本类只提供 fetch/forceRefresh 调用与 pageDate 额外状态。
 *
 * 内容已是中文摘要(AI 生成),不接翻译(对比 GitHubTrending 接了英文描述翻译)。
 *
 * 额外暴露 [pageDate]:页面声明的资讯日期(如 "2026.07.13"),取自 title,
 * 供 UI 在顶栏展示「AI Daily · 2026.07.13」副标题,让用户知道看的是哪一天。
 */
class StormzhangAiNewsViewModel(application: Application) : SourceListViewModel<StormzhangAiNews>(application) {

    private val liveRepo: StormzhangAiNewsRepository = StormzhangAiNewsRepository(cacheDir = application.cacheDir)
    private val archiveRepo: StormzhangAiNewsSource = StormzhangAiNewsArchiveRepository()

    /** 页面声明的资讯日期(如 "2026.07.13");解析不到时为空。 */
    private val _pageDate = MutableStateFlow("")
    val pageDate: StateFlow<String> = _pageDate.asStateFlow()

    /** 按当前 sourceMode 取对应 Repository。 */
    private fun currentRepo(): StormzhangAiNewsSource =
        if (sourceMode.value == com.peng.ainewshub.data.source.SourceMode.ARCHIVE) archiveRepo else liveRepo

    override suspend fun doFetch(): SourceListResult<StormzhangAiNews> = currentRepo().fetch()

    override suspend fun doForceRefresh(): SourceListResult<StormzhangAiNews> = currentRepo().forceRefresh()

    /** 刷新成功后更新 pageDate(基类已处理 items/fetchedAt)。 */
    override fun onRefreshSuccess(result: SourceListResult<StormzhangAiNews>) {
        _pageDate.value = (result as StormzhangAiNewsResult).pageDate
    }
}
