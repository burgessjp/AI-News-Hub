package com.peng.ainewshub.ui.overview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.data.OverviewDigest
import com.peng.ainewshub.data.OverviewRepository
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.i18n.localized
import com.peng.ainewshub.ui.toUiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 历史总览 ViewModel —— 「更多 → 历史总览」两级页共用。
 *
 * 数据走 index.json 的 `overview_history` 索引按日期寻址(见 [OverviewRepository]),
 * 纯归档语义:总览由流水线逐批次归档(overview/<date>/ 目录)并经一次性回填补齐
 * 历史日期,此处只读。
 *
 * 两级页各取一流(同 [com.peng.ainewshub.ui.SummaryArchiveViewModel] 套路):
 *  - [dates]:可选日期列表(索引键,倒序);
 *  - [digest]:指定日期的总览,日期详情页用 `viewModel(key = "overview-date-$date")`
 *    按日期隔离实例,避免换日期时闪现上一日期内容。
 */
class OverviewArchiveViewModel(application: Application) : AndroidViewModel(application) {

    private val overviewRepo = OverviewRepository()

    /** 可选日期列表(YYYY-MM-DD,倒序)。 */
    private val _dates = MutableStateFlow<UiState<List<String>>>(UiState.Loading)
    val dates: StateFlow<UiState<List<String>>> = _dates.asStateFlow()

    /** 指定日期的总览状态。 */
    private val _digest = MutableStateFlow<UiState<OverviewDigest>>(UiState.Loading)
    val digest: StateFlow<UiState<OverviewDigest>> = _digest.asStateFlow()

    /** 加载可选日期列表(overview_history 索引为空时返回空列表,UI 显示空态)。 */
    fun loadDates() {
        if (_dates.value is UiState.Success) return
        _dates.value = UiState.Loading
        viewModelScope.launch {
            _dates.value = runCatching { overviewRepo.availableDates() }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { it.toUiError(getApplication<Application>().localized()) }
            )
        }
    }

    /** 加载指定日期的总览(Success 后幂等,重进页不重复触发)。 */
    fun loadDigest(date: String) {
        if (_digest.value is UiState.Success) return
        loadDigestInternal(date)
    }

    /** 错误态重试(指定日期)。 */
    fun retryDigest(date: String) = loadDigestInternal(date)

    private fun loadDigestInternal(date: String) {
        _digest.value = UiState.Loading
        viewModelScope.launch {
            _digest.value = overviewRepo.loadDigestOn(date).fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { it.toUiError(getApplication<Application>().localized()) }
            )
        }
    }
}
