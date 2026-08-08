package com.peng.ainewshub.ui

import androidx.lifecycle.viewModelScope
import android.app.Application
import com.peng.ainewshub.data.SourceListResult
import com.peng.ainewshub.data.GitHubTrendingRepository
import com.peng.ainewshub.data.TrendingRepo
import com.peng.ainewshub.data.source.GitHubTrendingArchiveRepository
import com.peng.ainewshub.data.source.GitHubTrendingSource
import com.peng.ainewshub.data.source.SourceMode
import kotlinx.coroutines.flow.StateFlow

/**
 * GitHub Trending ViewModel。
 *
 * 继承 [SourceListViewModel]:sourceMode 订阅 / state / refresh / forceRefresh 等公共逻辑
 * 由基类统一。翻译逻辑由 [translateSupport] 委托(描述翻译,以 repo.url 为 key)。
 */
class GitHubTrendingViewModel(application: Application) : SourceListViewModel<TrendingRepo>(application) {

    private val liveRepo: GitHubTrendingRepository = GitHubTrendingRepository(cacheDir = application.cacheDir)
    private val archiveRepo: GitHubTrendingSource = GitHubTrendingArchiveRepository()
    private val translateSupport = TranslateSupport(application)

    /** 翻译配置流(UI 订阅以决定是否显示「译」按钮)。 */
    val configFlow = translateSupport.configFlow

    /** 描述翻译状态(repoUrl → state)。 */
    val descStates: StateFlow<Map<String, TranslationState>> = translateSupport.states

    private fun currentRepo(): GitHubTrendingSource =
        if (sourceMode.value == SourceMode.ARCHIVE) archiveRepo else liveRepo

    override suspend fun doFetch(): SourceListResult<TrendingRepo> = currentRepo().fetch()

    override suspend fun doForceRefresh(): SourceListResult<TrendingRepo> = currentRepo().forceRefresh()

    /** 翻译仓库描述(列表页)。 */
    fun translateDesc(repo: TrendingRepo) =
        translateSupport.translate(viewModelScope, repo.url, repo.description)
}
