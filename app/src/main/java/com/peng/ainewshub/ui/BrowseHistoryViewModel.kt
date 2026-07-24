package com.peng.ainewshub.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.data.BrowseHistoryEntity
import com.peng.ainewshub.data.BrowseHistoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 浏览历史 ViewModel。
 *
 * 分页加载:默认展示前 [PAGE_SIZE] 条,滚动到底部时 [loadMore] 把 [visibleLimit]
 * 增加 [PAGE_SIZE],DB Flow 自动重发新的更大窗口。删除/清空时 Flow 自动刷新当前窗口。
 *
 * @param repo 由 [BrowseHistoryRepository] 单例注入(MainActivity 构造)
 */
class BrowseHistoryViewModel(
    private val repo: BrowseHistoryRepository
) : ViewModel() {

    /** 单页条数。 */
    val pageSize: Int = PAGE_SIZE

    /** 是否按域名分组(分组本身在 UI 层做,VM 只透传开关)。 */
    private val _groupByHost = MutableStateFlow(false)
    val groupByHost: StateFlow<Boolean> = _groupByHost.asStateFlow()

    /** 当前请求展示的条数上限(随 loadMore 递增)。 */
    private val _visibleLimit = MutableStateFlow(PAGE_SIZE)
    val visibleLimit: StateFlow<Int> = _visibleLimit.asStateFlow()

    /** 历史总数(用于 hasMore 判断 + 清空按钮显隐)。 */
    val count: StateFlow<Int> = repo.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * 当前窗口的历史:订阅 [BrowseHistoryRepository.observePage]。
     * flatMapLatest —— visibleLimit 变化时自动切到新的 limit 查询。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val history: StateFlow<List<BrowseHistoryEntity>> =
        _visibleLimit.flatMapLatest { limit -> repo.observePage(limit) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 是否还有更多未加载(count > 当前窗口条数)。 */
    val hasMore: StateFlow<Boolean> =
        combine(history, count) { list, total -> list.size < total }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 加载下一页(已达末页或正在加载时忽略)。 */
    fun loadMore() {
        _visibleLimit.value += PAGE_SIZE
    }

    fun toggleGroupByHost() { _groupByHost.value = !_groupByHost.value }

    fun delete(url: String) {
        viewModelScope.launch { repo.delete(url) }
    }

    /** 撤销删除:原样恢复实体(保留原 visitCount/visitedAt)。 */
    fun restore(entity: BrowseHistoryEntity) {
        viewModelScope.launch { repo.restore(entity) }
    }

    fun clearAll() {
        _visibleLimit.value = PAGE_SIZE // 清空后重置回首页
        viewModelScope.launch { repo.clearAll() }
    }

    /** Factory:把 MainActivity 构造的 Repository 注入 VM。 */
    class Factory(private val repo: BrowseHistoryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BrowseHistoryViewModel(repo) as T
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
