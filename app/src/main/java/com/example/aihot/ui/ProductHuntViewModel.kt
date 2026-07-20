package com.example.aihot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aihot.data.ProductHunt
import com.example.aihot.data.ShortContentException
import com.example.aihot.data.AiConfigStore
import com.example.aihot.data.TranslationRepository
import com.example.aihot.data.source.ProductHuntArchiveRepository
import com.example.aihot.data.source.ProductHuntSource
import com.example.aihot.data.source.SourceMode
import com.example.aihot.ui.more.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Product Hunt ViewModel。
 *
 * 与 [HuggingFacePapersViewModel] 同构,但 **不接实时源**:Product Hunt 的
 * Developer Token 是服务端 secret,不进 APK,App 端不直连 PH GraphQL。
 * 故 [SourceMode] LIVE 与 ARCHIVE 都走归档([archiveRepo])——与 LinuxDo「只实时」
 * 对称,PH 是「只归档」。[sourceMode] 仍订阅设置,仅为顶栏角标一致。
 *
 * 整体翻译:产品名(name)与 tagline 多为英文,翻译开关开且配置就绪时,标题行出现
 * 「译」按钮(复用 [InlineTranslateButton] / [TranslatedText],UI 与其他源一致)。
 * 翻译状态以 product.slug 为 key(slug 唯一,刷新后状态保留,避免重复请求)。
 *
 * 单按钮整体翻译:name 与 tagline 合并为一段文本,一次 API 调用拿到整体译文,
 * UI 把译文块整体显示在 tagline 之后。合并而非分开两次请求,既省一次网络往返,
 * 译文也天然连贯。
 */
class ProductHuntViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)

    // 只归档:无 liveRepo,两种 SourceMode 都用归档(PH Developer Token 不进 APK)。
    private val archiveRepo: ProductHuntSource = ProductHuntArchiveRepository()

    private val _sourceMode = MutableStateFlow(
        runCatching { settingsStore.currentSourceModeSync() }.getOrDefault(SourceMode.LIVE)
    )
    val sourceMode: StateFlow<SourceMode> = _sourceMode.asStateFlow()

    private val translationRepo = TranslationRepository(application)
    private val configStore = AiConfigStore(application)

    private val _state = MutableStateFlow<UiState<List<ProductHunt>>>(UiState.Loading)
    val state: StateFlow<UiState<List<ProductHunt>>> = _state.asStateFlow()

    /**
     * 上次成功拿到数据时的「数据落盘时刻」。归档快照的 fetched_at_ms。
     */
    private val _lastRefreshAt = MutableStateFlow<Long?>(null)
    val lastRefreshAt: StateFlow<Long?> = _lastRefreshAt.asStateFlow()

    /**
     * 手动刷新进行中(下拉刷新转圈 + 防重复)。
     */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** 翻译配置流(UI 订阅以决定是否显示「译」按钮)。 */
    val configFlow = configStore.configFlow

    /** 整体译文翻译状态(slug → state),与 HuggingFacePapersViewModel.translationStates 同源。 */
    private val _translationStates = MutableStateFlow<Map<String, TranslationState>>(emptyMap())
    val translationStates: StateFlow<Map<String, TranslationState>> = _translationStates.asStateFlow()

    init {
        // 订阅数据源设置:仅用于顶栏角标一致(PH 实际都走归档)。
        viewModelScope.launch {
            settingsStore.prefsFlow.map { it.sourceMode }.collect { _sourceMode.value = it }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { archiveRepo.fetch() }
                .onSuccess { result ->
                    _state.value =
                        if (result.products.isEmpty()) UiState.Error("今日暂无内容", ErrorKind.NoData) else UiState.Success(result.products)
                    _lastRefreshAt.value = result.fetchedAt
                }
                .onFailure { _state.value = it.toUiError() }
        }
    }

    /**
     * 强制刷新:忽略缓存真打网络(用户下拉刷新)。归档源 fetch==forceRefresh,
     * 这里仍单独设 isRefreshing 状态供下拉转圈。
     */
    fun forceRefresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            runCatching { archiveRepo.forceRefresh() }
                .onSuccess { result ->
                    _state.value =
                        if (result.products.isEmpty()) UiState.Error("今日暂无内容", ErrorKind.NoData) else UiState.Success(result.products)
                    _lastRefreshAt.value = result.fetchedAt
                }
                .onFailure {
                    if (_state.value !is UiState.Success) {
                        _state.value = it.toUiError()
                    }
                }
            _isRefreshing.value = false
        }
    }

    /**
     * 翻译产品(name + tagline 整体),列表页单按钮触发。
     * 合并为一段文本一次翻译;两者都为空时不翻译。
     */
    fun translateProduct(product: ProductHunt) {
        val key = product.slug.ifBlank { product.id }
        val current = _translationStates.value[key]
        if (current is TranslationState.Loading) return
        val text = buildString {
            if (product.name.isNotBlank()) append(product.name.trim())
            if (product.tagline.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(product.tagline.trim())
            }
        }
        if (text.isBlank()) return
        _translationStates.value = _translationStates.value + (key to TranslationState.Loading)
        viewModelScope.launch {
            val outcome = doTranslate(text)
            _translationStates.value = _translationStates.value + (key to outcome)
        }
    }

    private suspend fun doTranslate(text: String): TranslationState {
        val config = configStore.configFlow.first()
        if (!config.isReady) return TranslationState.Error(TranslationState.CONFIG_MISSING)
        return runCatching { translationRepo.translate(text, config).getOrThrow() }
            .fold(
                onSuccess = { TranslationState.Success(it) },
                onFailure = {
                    if (it is ShortContentException) {
                        TranslationState.Error(TranslationState.TOO_SHORT)
                    } else {
                        TranslationState.Error(it.toUiError().message)
                    }
                }
            )
    }
}
