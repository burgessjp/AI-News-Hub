package com.peng.ainewshub.ui.weekly

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.data.repo.WeeklyDigest
import com.peng.ainewshub.data.repo.WeeklyRepository
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.i18n.localized
import com.peng.ainewshub.ui.toUiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 周刊 ViewModel —— 单实例宿主(今天页入口行与周刊页共用同一 [viewModel]
 * 作用域,进页秒回缓存)。
 *
 * 三路状态(承 [com.peng.ainewshub.ui.overview.OverviewArchiveViewModel] 的
 * 「日期索引 + 换期强制重载」范式):
 *  - [latest]:根级 weekly.json 最新一期,今天页入口行的数据源(Success 后
 *    幂等;文件暂缺走 NoData,入口行随之隐藏);
 *  - [dates]:weekly_history 索引键(周一日期倒序),期次条数据源;
 *  - [digest]:当前选中期的正文,换期经 [retryIssue] 强制重载(归档按路径
 *    不可变缓存,重拉近零成本)。
 */
class WeeklyViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = WeeklyRepository()

    private val _latest = MutableStateFlow<UiState<WeeklyDigest>>(UiState.Loading)
    val latest: StateFlow<UiState<WeeklyDigest>> = _latest.asStateFlow()

    private val _dates = MutableStateFlow<UiState<List<String>>>(UiState.Loading)
    val dates: StateFlow<UiState<List<String>>> = _dates.asStateFlow()

    private val _digest = MutableStateFlow<UiState<WeeklyDigest>>(UiState.Loading)
    val digest: StateFlow<UiState<WeeklyDigest>> = _digest.asStateFlow()

    /** 当前加载的期次(weekStart;null = 尚未选中)。 */
    private var currentWeek: String? = null

    init {
        loadLatest()
    }

    /**
     * 加载最新一期(Success 后幂等)。
     *
     * @param force true 绕过 weekly.json 2 分钟缓存(今天页下拉刷新联动)
     */
    fun loadLatest(force: Boolean = false) {
        if (!force && _latest.value is UiState.Success) return
        _latest.value = UiState.Loading
        viewModelScope.launch {
            _latest.value = repo.loadLatest(force).fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { it.toUiError(getApplication<Application>().localized()) }
            )
        }
    }

    /** 加载期次索引(Success 后幂等;索引缺失/为空返回空列表,UI 走空态)。 */
    fun loadDates() {
        if (_dates.value is UiState.Success) return
        _dates.value = UiState.Loading
        viewModelScope.launch {
            _dates.value = runCatching { repo.availableIssues() }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { it.toUiError(getApplication<Application>().localized()) }
            )
        }
    }

    /** 加载指定期(Success 且未换期时幂等,重进页不重复触发)。 */
    fun loadIssue(week: String) {
        if (_digest.value is UiState.Success && currentWeek == week) return
        loadIssueInternal(week)
    }

    /** 换期 / 错误态重试(强制重载)。 */
    fun retryIssue(week: String) = loadIssueInternal(week)

    private fun loadIssueInternal(week: String) {
        currentWeek = week
        _digest.value = UiState.Loading
        viewModelScope.launch {
            _digest.value = repo.loadIssue(week).fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { it.toUiError(getApplication<Application>().localized()) }
            )
        }
    }
}
