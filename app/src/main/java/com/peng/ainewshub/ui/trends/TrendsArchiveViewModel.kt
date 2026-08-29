package com.peng.ainewshub.ui.trends

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.data.repo.TrendsDigest
import com.peng.ainewshub.data.repo.TrendsRepository
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.i18n.localized
import com.peng.ainewshub.ui.toUiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 历史热词 ViewModel —— 「更多 → 历史热词」两级页共用。
 *
 * 数据走根级独立索引 `trends_history.json` 按日期寻址(见 [TrendsRepository]),
 * 纯归档语义:热词榜由流水线逐批次落盘(trends/<date>/ 目录)并经一次性回填
 * 补齐历史日期,此处只读。
 *
 * 两级页各取一流(同 [com.peng.ainewshub.ui.overview.OverviewArchiveViewModel] 套路):
 *  - [dates]:可选日期列表(索引键,倒序);
 *  - [digest]:指定日期的热词榜,日期详情页用 `viewModel(key = "trends-date-$date")`
 *    按日期隔离实例,避免换日期时闪现上一日期内容。
 */
class TrendsArchiveViewModel(application: Application) : AndroidViewModel(application) {

    private val trendsRepo = TrendsRepository()

    /** 可选日期列表(YYYY-MM-DD,倒序)。 */
    private val _dates = MutableStateFlow<UiState<List<String>>>(UiState.Loading)
    val dates: StateFlow<UiState<List<String>>> = _dates.asStateFlow()

    /** 指定日期的热词榜状态。 */
    private val _digest = MutableStateFlow<UiState<TrendsDigest>>(UiState.Loading)
    val digest: StateFlow<UiState<TrendsDigest>> = _digest.asStateFlow()

    /** 加载可选日期列表(trends_history 索引为空时返回空列表,UI 显示空态)。 */
    fun loadDates() {
        if (_dates.value is UiState.Success) return
        _dates.value = UiState.Loading
        viewModelScope.launch {
            _dates.value = runCatching { trendsRepo.availableDates() }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { it.toUiError(getApplication<Application>().localized()) }
            )
        }
    }

    /** 加载指定日期的热词榜(Success 后幂等,重进页不重复触发)。 */
    fun loadDigest(date: String) {
        if (_digest.value is UiState.Success) return
        loadDigestInternal(date)
    }

    /** 错误态重试(指定日期)。 */
    fun retryDigest(date: String) = loadDigestInternal(date)

    private fun loadDigestInternal(date: String) {
        _digest.value = UiState.Loading
        viewModelScope.launch {
            _digest.value = trendsRepo.loadDigestOn(date).fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { it.toUiError(getApplication<Application>().localized()) }
            )
        }
    }
}
