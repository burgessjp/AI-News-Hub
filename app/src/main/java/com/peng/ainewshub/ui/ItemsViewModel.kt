package com.peng.ainewshub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.data.Mode
import com.peng.ainewshub.data.NewsCategory
import com.peng.ainewshub.data.NewsItem
import com.peng.ainewshub.data.NewsPage
import com.peng.ainewshub.data.NewsRepository
import com.peng.ainewshub.ui.i18n.localized
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 动态列表 ViewModel。
 *
 * 支持的筛选:
 *  - mode: 精选 / 全部
 *  - category: 5 类之一,可清空
 *  - query: 关键词搜索(≥2 字触发)
 *
 * 任一筛选变化 → 自动重新拉首页。分页通过 [loadMore] 触发,基于上次的 cursor。
 */
class ItemsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = NewsRepository()

    /** 当前筛选条件。 */
    data class Filter(
        val mode: Mode = Mode.SELECTED,
        val category: NewsCategory? = null,
        val query: String? = null
    ) {
        /** 语义"搜索中"。 */
        val isSearching: Boolean get() = !query.isNullOrBlank() && query.trim().length >= 2
    }

    private val _filter = MutableStateFlow(Filter())
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    /** 累积的列表(首页 + 已加载后续页)。 */
    private val _items = MutableStateFlow<List<NewsItem>>(emptyList())
    val items: StateFlow<List<NewsItem>> = _items.asStateFlow()

    /** 当前分页游标。null 表示无下一页或未开始分页。 */
    private var nextCursor: String? = null
    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /** 是否正在加载下一页(供 UI 去重,避免重复触发同一页)。 */
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    /** 下拉刷新进行中(转圈 + 防重复)。 */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** 刷新触发器:递增强制重拉(filter 不变时 StateFlow/distinct 会吞掉相等值,
     *  单靠重写 filter 无法触发刷新,故用独立 tick)。 */
    private val _refreshTick = MutableStateFlow(0)

    /** 列表层状态(独立于 filter,避免 filter 变化触发旧加载)。 */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val state: StateFlow<UiState<List<NewsItem>>> =
        combine(_filter, _refreshTick) { f, t -> f to t }
        .debounce(300) // 输入抖动;仅对 query 有意义,但对 mode/category/refresh 无害
        .distinctUntilChanged()
        .flatMapLatest { (f, _) ->
            kotlinx.coroutines.flow.flow {
                emit(UiState.Loading)
                _items.value = emptyList()
                nextCursor = null
                _isLoadingMore.value = false
                runCatching { repo.fetchItems(mode = f.mode, category = f.category, query = f.query) }
                    .onSuccess { page -> applyPage(page, replace = true); emit(UiState.Success(_items.value)) }
                    .onFailure { emit(it.toUiError(getApplication<Application>().localized())) }
            }
        }
        // 终态(成功/失败)到达即结束下拉刷新转圈;Loading 不清,避免闪断
        .onEach { if (it !is UiState.Loading) _isRefreshing.value = false }
        .stateIn(viewModelScope, SharingStarted.Lazily, UiState.Loading)

    init {
        // trigger first fetch via state subscription lazily; but ensure immediate start
        state
    }

    fun setMode(mode: Mode) {
        if (_filter.value.mode != mode) _filter.update { it.copy(mode = mode) }
    }

    fun setCategory(category: NewsCategory?) {
        if (_filter.value.category != category) _filter.update { it.copy(category = category) }
    }

    fun setQuery(query: String?) {
        val normalized = query?.trim()?.takeIf { it.isNotEmpty() }
        if (_filter.value.query != normalized) _filter.update { it.copy(query = normalized) }
    }

    fun refresh() {
        // 触发重新加载:递增刷新 tick(filter 不变也能强制重拉)
        _isRefreshing.value = true
        _refreshTick.update { it + 1 }
    }

    /**
     * 加载下一页。仅在:有游标、还有更多、且当前未在加载时有效。
     * 并发去重靠 [isLoadingMore],避免快速滚动时同一页被重复请求。
     */
    fun loadMore() {
        if (_isLoadingMore.value) return
        val cursor = nextCursor ?: return
        if (!_hasMore.value) return
        val f = _filter.value
        _isLoadingMore.value = true
        viewModelScope.launch {
            runCatching {
                repo.fetchItems(
                    mode = f.mode, category = f.category, query = f.query,
                    cursor = cursor
                )
            }.onSuccess { page ->
                // 防竞态:加载期间若 filter 已变(切分类/改搜索),丢弃这批过期结果,
                // 否则会把旧分类数据追加进已被清空的新列表,并覆盖新游标。
                if (_filter.value == f) applyPage(page, replace = false)
            }
            _isLoadingMore.value = false
        }
    }

    private fun applyPage(page: NewsPage, replace: Boolean) {
        _items.value = if (replace) page.items else _items.value + page.items
        nextCursor = page.nextCursor
        _hasMore.value = page.hasNext && !page.nextCursor.isNullOrBlank()
    }
}
