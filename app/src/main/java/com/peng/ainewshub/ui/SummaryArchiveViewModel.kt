package com.peng.ainewshub.ui
import com.peng.ainewshub.ui.i18n.localized

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.data.repo.SourceSummary
import com.peng.ainewshub.data.repo.SummaryRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 历史摘要 ViewModel —— 「过刊」页(ui/more/HistoryHubScreen)宿主。
 *
 * 数据走 index.json 的 `history` 索引按日期寻址(见 [SummaryRepository]),
 * 纯归档语义。
 *
 * 两流:
 *  - [dates]:可选日期列表(全源 history 的日期并集,附当天有数据的源数;
 *    过刊页用作 31 天摘要窗口判定——选中日不在键集内则不渲染分源段);
 *  - [dateStates]:指定日期的全源摘要,按源独立 Loading/Error/Success。
 *    单实例宿主换日期前须 [clearDate] 清幂等守卫再 [loadDate](按日期隔离
 *    实例的历史用法已随三段式 hub 删除)。
 */
class SummaryArchiveViewModel(application: Application) : AndroidViewModel(application) {

    private val summaryRepo = SummaryRepository()

    /** 可选日期列表:(日期, 当天有归档的源数),倒序。 */
    private val _dates = MutableStateFlow<UiState<List<Pair<String, Int>>>>(UiState.Loading)
    val dates: StateFlow<UiState<List<Pair<String, Int>>>> = _dates.asStateFlow()

    /** 指定日期的全源摘要状态,key = source(对齐 SummaryRepository.SOURCE_KEYS)。 */
    private val _dateStates = MutableStateFlow<Map<String, UiState<SourceSummary>>>(emptyMap())
    val dateStates: StateFlow<Map<String, UiState<SourceSummary>>> = _dateStates.asStateFlow()

    /** 加载可选日期列表(history 索引为空时返回空列表,UI 显示空态)。 */
    fun loadDates() {
        if (_dates.value is UiState.Success) return
        _dates.value = UiState.Loading
        viewModelScope.launch {
            _dates.value = runCatching { summaryRepo.availableDates() }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { it.toUiError(getApplication<Application>().localized()) }
            )
        }
    }

    /**
     * 清空当前日期的分源状态 —— 单实例宿主(「过刊」页)切换日期前调用,
     * 使 [loadDate] 的幂等守卫放行新日期重新拉取(按日期隔离实例的详情页无需调用)。
     */
    fun clearDate() {
        _dateStates.value = emptyMap()
    }

    /** 并发拉取指定日期的全源摘要。每源独立失败,不互相拖累。 */
    fun loadDate(date: String) {
        if (_dateStates.value.isNotEmpty()) return
        _dateStates.value =
            SummaryRepository.SOURCE_KEYS.associateWith { UiState.Loading as UiState<SourceSummary> }
        viewModelScope.launch {
            SummaryRepository.SOURCE_KEYS.map { key ->
                async {
                    val state: UiState<SourceSummary> = summaryRepo.summarizeOn(key, date).fold(
                        onSuccess = { UiState.Success(it) },
                        onFailure = { it.toUiError(getApplication<Application>().localized()) }
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
                onFailure = { it.toUiError(getApplication<Application>().localized()) }
            )
            _dateStates.value = _dateStates.value + (source to state)
        }
    }
}
