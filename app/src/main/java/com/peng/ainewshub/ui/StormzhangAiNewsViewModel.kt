package com.peng.ainewshub.ui

import android.app.Application
import com.peng.ainewshub.data.SourceListResult
import com.peng.ainewshub.data.StormzhangAiNews
import com.peng.ainewshub.data.StormzhangAiNewsResult
import com.peng.ainewshub.data.source.StormzhangAiNewsArchiveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * stormzhang AI Daily ViewModel。
 *
 * 继承 [SourceListViewModel]:state / refresh / forceRefresh 等公共逻辑由基类统一,
 * 数据恒走 gitcode 归档([StormzhangAiNewsArchiveRepository])。本类只提供
 * fetch/forceRefresh 调用与 pageDate 额外状态。
 *
 * 内容已是中文摘要(AI 生成),不接翻译(对比 GitHubTrending 接了英文描述翻译)。
 *
 * 额外暴露 [pageDate]:页面声明的资讯日期(如 "2026.07.13"),由流水线从 title 抽取,
 * 供 UI 在顶栏展示「AI Daily · 2026.07.13」副标题,让用户知道看的是哪一天。
 */
class StormzhangAiNewsViewModel(application: Application) : SourceListViewModel<StormzhangAiNews>(application) {

    private val archiveRepo = StormzhangAiNewsArchiveRepository()

    /** 页面声明的资讯日期(如 "2026.07.13");解析不到时为空。 */
    private val _pageDate = MutableStateFlow("")
    val pageDate: StateFlow<String> = _pageDate.asStateFlow()

    override suspend fun doFetch(): SourceListResult<StormzhangAiNews> = archiveRepo.fetch()

    override suspend fun doForceRefresh(): SourceListResult<StormzhangAiNews> = archiveRepo.forceRefresh()

    /** 刷新成功后更新 pageDate(基类已处理 items/fetchedAt)。 */
    override fun onRefreshSuccess(result: SourceListResult<StormzhangAiNews>) {
        _pageDate.value = (result as StormzhangAiNewsResult).pageDate
    }
}
