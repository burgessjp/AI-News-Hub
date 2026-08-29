package com.peng.ainewshub.ui.trends

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.data.repo.TrendsCloudDigest
import com.peng.ainewshub.data.repo.TrendsRepository
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.i18n.localized
import com.peng.ainewshub.ui.toUiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 趋势词云 ViewModel —— 「趋势词云」二级页。
 *
 * 数据走根级独立文件 `trends_cloud.json`(流水线与热词榜同批生成的纯统计
 * 词云候选,专用数据文件,见 [TrendsRepository.loadCloud]);独立 2 分钟缓存,
 * 进页通常秒回。词云页无下拉刷新(二级页语义),Success 后幂等,重进页不重复
 * 触发;错误态由页面重试按钮调 [retry]。
 */
class TrendsCloudViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = TrendsRepository()

    private val _state = MutableStateFlow<UiState<TrendsCloudDigest>>(UiState.Loading)
    val state: StateFlow<UiState<TrendsCloudDigest>> = _state.asStateFlow()

    init {
        load()
    }

    /** 首次加载(Success 后幂等;文件暂缺走 NoData 空态语义,下次批次自愈)。 */
    fun load() {
        if (_state.value is UiState.Success) return
        loadInternal()
    }

    /** 错误 / 空态重试。 */
    fun retry() = loadInternal()

    private fun loadInternal() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = repo.loadCloud().fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { it.toUiError(getApplication<Application>().localized()) }
            )
        }
    }
}
