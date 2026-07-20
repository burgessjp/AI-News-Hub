package com.example.aihot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aihot.data.SourceSummary
import com.example.aihot.data.SummaryRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 历史摘要 ViewModel —— 「更多 → 历史摘要」两级页共用。
 *
 * 数据走 index.json 的 `history` 索引按日期寻址(见 [SummaryRepository]),
 * 纯归档语义,与全局 SourceMode 无关。
 *
 * 两级页各取一流:
 *  - [dates]:可选日期列表(7 源 history 的日期并集,附当天有数据的源数);
 *  - [dateStates]:指定日期的 7 源摘要,按源独立 Loading/Error/Success。
 *    日期详情页用 `viewModel(key = "summary-date-$date")` 按日期隔离实例
 *    (同 DailyDateScreen 套路),避免换日期时闪现上一日期内容。
 */
class SummaryArchiveViewModel(application: Application) : AndroidViewModel(application) {

    private val summaryRepo = SummaryRepository()

    /** 可选日期列表:(日期, 当天有归档的源数),倒序。 */
    private val _dates = MutableStateFlow<UiState<List<Pair<String, Int>>>>(UiState.Loading)
    val dates: StateFlow<UiState<List<Pair<String, Int>>>> = _dates.asStateFlow()

    /** 指定日期的 7 源摘要状态,key = source(对齐 SummaryRepository.SOURCE_KEYS)。 */
    private val _dateStates = MutableStateFlow<Map<String, UiState<SourceSummary>>>(emptyMap())
    val dateStates: StateFlow<Map<String, UiState<SourceSummary>>> = _dateStates.asStateFlow()

    /** 加载可选日期列表(history 索引为空时返回空列表,UI 显示空态)。 */
    fun loadDates() {
        if (_dates.value is UiState.Success) return
        _dates.value = UiState.Loading
        viewModelScope.launch {
            _dates.value = runCatching { summaryRepo.availableDates() }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { it.toUiError() }
            )
        }
    }

    /** 并发拉取指定日期的 7 源摘要。每源独立失败,不互相拖累。 */
    fun loadDate(date: String) {
        if (_dateStates.value.isNotEmpty()) return
        _dateStates.value =
            SummaryRepository.SOURCE_KEYS.associateWith { UiState.Loading as UiState<SourceSummary> }
        viewModelScope.launch {
            SummaryRepository.SOURCE_KEYS.map { key ->
                async {
                    val state: UiState<SourceSummary> = summaryRepo.summarizeOn(key, date).fold(
                        onSuccess = { UiState.Success(it) },
                        onFailure = { it.toUiError() }
                    )
                    _dateStates.value = _dateStates.value + (key to state)
                }
            }.awaitAll()
        }
    }

    /** 单源重试(指定日期)。 */
    fun retrySource(date: String, source: String) {
        viewModelScope.launch {
            _dateStates.value = _dateStates.value + (source to UiState.Loading as UiState<SourceSummary>)
            val state: UiState<SourceSummary> = summaryRepo.summarizeOn(source, date).fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { it.toUiError() }
            )
            _dateStates.value = _dateStates.value + (source to state)
        }
    }
}
