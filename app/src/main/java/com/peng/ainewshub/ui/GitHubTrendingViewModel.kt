package com.peng.ainewshub.ui

import androidx.lifecycle.viewModelScope
import android.app.Application
import com.peng.ainewshub.data.model.SourceListResult
import com.peng.ainewshub.data.model.TrendingRepo
import com.peng.ainewshub.data.source.GitHubTrendingArchiveRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * GitHub Trending ViewModel。
 *
 * 继承 [SourceListViewModel]:state / refresh / forceRefresh 等公共逻辑由基类统一,
 * 数据恒走 gitcode 归档([GitHubTrendingArchiveRepository])。翻译逻辑由
 * [translateSupport] 委托(描述翻译,以 repo.url 为 key)。
 */
class GitHubTrendingViewModel(application: Application) : SourceListViewModel<TrendingRepo>(application) {

    private val archiveRepo = GitHubTrendingArchiveRepository()
    private val translateSupport = TranslateSupport(application)

    /** 翻译配置流(UI 订阅以决定是否显示「译」按钮)。 */
    val configFlow = translateSupport.configFlow

    /** 描述翻译状态(repoUrl → state)。 */
    val descStates: StateFlow<Map<String, TranslationState>> = translateSupport.states

    override suspend fun doFetch(): SourceListResult<TrendingRepo> = archiveRepo.fetch()

    override suspend fun doForceRefresh(): SourceListResult<TrendingRepo> = archiveRepo.forceRefresh()

    /** 翻译仓库描述(列表页)。 */
    fun translateDesc(repo: TrendingRepo) =
        translateSupport.translate(viewModelScope, repo.url, repo.description)
}
