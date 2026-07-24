package com.peng.ainewshub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peng.ainewshub.data.HackerNewsComment
import com.peng.ainewshub.data.HackerNewsRepository
import com.peng.ainewshub.data.HackerNewsStory
import com.peng.ainewshub.data.ShortContentException
import com.peng.ainewshub.data.AiConfigStore
import com.peng.ainewshub.data.TranslationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 单条内容(标题 / 评论)的翻译状态机 —— 与 [UiState] 同文件风格。
 *
 * - [Idle]: 未翻译,UI 显示「译」按钮
 * - [Loading]: 请求中(仅未命中缓存时短暂出现)
 * - [Success]: 已翻译,[translated] 为译文,UI 切换「收起/显示译文」
 * - [Error]: 失败,[message] 为原因。特殊值 [CONFIG_MISSING] / [TOO_SHORT]
 *   由 UI 分别走「引导去设置」「提示内容过短」,不显示通用错误文案
 */
sealed interface TranslationState {
    data object Idle : TranslationState
    data object Loading : TranslationState
    data class Success(val translated: String) : TranslationState
    data class Error(val message: String) : TranslationState

    companion object {
        const val CONFIG_MISSING = "config_missing"
        const val TOO_SHORT = "too_short"
    }
}

/**
 * HackerNews 评论树 ViewModel —— 懒加载(按需展开)。
 *
 * 策略:进入页面只拉取 story 的一级评论;用户点击某条有子评论的节点时,
 * 才拉取其下一层([Node.children]),不预取整棵子树。避免热门帖评论数百条时
 * 一次性发起海量请求。
 *
 * 展开状态([Node.expanded])独立于数据;[flatten] 仅把「已展开」节点的子节点
 * 递归铺平(带 [FlatComment.depth])供 LazyColumn 渲染。
 *
 * 继承 [AndroidViewModel] 以拿 application.cacheDir 注入翻译缓存,
 * 以及构造 [AiConfigStore] / [TranslationRepository]。
 */
class HackerNewsCommentsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = HackerNewsRepository()
    private val translationRepo = TranslationRepository(application)
    private val configStore = AiConfigStore(application)

    private val _state = MutableStateFlow<UiState<List<FlatComment>>>(UiState.Loading)
    val state: StateFlow<UiState<List<FlatComment>>> = _state.asStateFlow()

    /** 翻译配置流(UI 订阅以决定是否显示「译」按钮)。 */
    val configFlow = configStore.configFlow

    /** story 标题翻译状态(storyId → state)。标题不在评论 Node 树内,单独管理。 */
    private val _titleStates = MutableStateFlow<Map<Long, TranslationState>>(emptyMap())
    val titleStates: StateFlow<Map<Long, TranslationState>> = _titleStates.asStateFlow()

    /** 顶层评论节点(story 的一级评论)。展开/折叠通过修改节点树后重新 flatten 实现。 */
    private val roots = mutableListOf<Node>()

    fun load(story: HackerNewsStory) {
        if (roots.isNotEmpty()) return // 已加载过,不重复
        _state.value = UiState.Loading
        viewModelScope.launch {
            runCatching {
                // 归档模式下列表项没有 kids(归档快照未存评论树):先实时补拉 story 的一级评论 id,
                // 再拉评论详情。Firebase API 匿名免费,这步几乎不会失败。
                val kids = if (story.kids.isEmpty()) repo.fetchStoryKids(story.id) else story.kids
                repo.fetchComments(kids)
            }
                .onSuccess { list ->
                    roots.clear()
                    roots.addAll(list.map { Node(it) })
                    emitFlattened()
                }
                .onFailure { _state.value = it.toUiError() }
        }
    }

    fun retry(story: HackerNewsStory) {
        roots.clear()
        load(story)
    }

    /**
     * 展开/折叠某节点(懒加载)。
     *
     * - 折叠 → 收起,清空 [Node.children] 的展示(保留已加载数据,再次展开复用)
     * - 展开 → 若子评论未加载则拉取,加载完递归 flatten
     *
     * 加载中标记 [Node.childrenLoading],UI 展示"加载中"占位。
     */
    fun toggle(node: Node) {
        if (node.comment.kids.isEmpty()) return
        if (node.expanded) {
            node.expanded = false
            emitFlattened()
            return
        }
        // 展开:已有缓存数据直接展示,否则懒加载
        if (node.children.isNotEmpty()) {
            node.expanded = true
            emitFlattened()
        } else {
            // 加载中重复点击直接忽略(节点级去重),避免并发发多个 fetchComments 乱序覆盖
            if (node.childrenLoading) return
            node.childrenLoading = true
            node.expanded = true
            emitFlattened()
            viewModelScope.launch {
                runCatching { repo.fetchComments(node.comment.kids) }
                    .onSuccess { list ->
                        node.children = list.map { Node(it) }
                        node.childrenLoading = false
                        // 清除之前失败的残留错误,避免成功后错误文案仍显示
                        node.childrenError = null
                        emitFlattened()
                    }
                    .onFailure {
                        node.childrenLoading = false
                        node.childrenError = it.toUiError().message
                        emitFlattened()
                    }
            }
        }
    }

    /** 翻译某条评论正文。状态机类比 [toggle]。 */
    fun translateComment(node: Node) {
        if (node.translationState is TranslationState.Loading) return
        val text = node.comment.text
        if (text.isBlank()) return
        node.translationState = TranslationState.Loading
        emitFlattened()
        viewModelScope.launch {
            val outcome = doTranslate(text)
            node.translationState = outcome
            emitFlattened()
        }
    }

    /** 翻译 story 标题。 */
    fun translateTitle(story: HackerNewsStory) {
        val current = _titleStates.value[story.id]
        if (current is TranslationState.Loading) return
        if (story.title.isBlank()) return
        _titleStates.value = _titleStates.value + (story.id to TranslationState.Loading)
        viewModelScope.launch {
            val outcome = doTranslate(story.title)
            _titleStates.value = _titleStates.value + (story.id to outcome)
        }
    }

    /**
     * 实际翻译流程:校验配置 → 调 [TranslationRepository](内部带缓存)。
     * 返回供 UI 直接写入的状态。
     */
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

    /** 把节点树按展开状态铺平为带层级的列表(供 LazyColumn 渲染)。 */
    private fun emitFlattened() {
        val flat = mutableListOf<FlatComment>()
        fun walk(nodes: List<Node>, depth: Int) {
            nodes.forEach { node ->
                flat.add(
                    FlatComment(
                        node = node,
                        depth = depth,
                        hasKids = node.comment.kids.isNotEmpty(),
                        expanded = node.expanded,
                        childrenLoading = node.childrenLoading,
                        childrenError = node.childrenError,
                        translationState = node.translationState
                    )
                )
                if (node.expanded) walk(node.children, depth + 1)
            }
        }
        walk(roots, 0)
        // 空列表(无评论)是正常态,不是错误 —— 走 Success 让 UI 显示 EmptyState。
        // 真正的加载失败已在 load()/toggle() 的 onFailure 中发出 UiState.Error。
        _state.value = UiState.Success(flat)
    }
}

/**
 * 评论树的运行时节点:包装一条 [HackerNewsComment] + 展开状态 + 懒加载的子节点。
 *
 * 用普通 class(非 data class)并直接持有可变状态 —— 展开折叠频繁,避免每次
 * 复制整棵树。改动后调用 [HackerNewsCommentsViewModel.emitFlattened] 重新铺平。
 */
class Node(val comment: HackerNewsComment) {
    var expanded: Boolean = false
    var children: List<Node> = emptyList()
    var childrenLoading: Boolean = false
    var childrenError: String? = null
    /** 该评论正文的翻译状态(运行时,不持久化;译文本身由 Repository 缓存)。 */
    var translationState: TranslationState = TranslationState.Idle
}

/**
 * 铺平后的评论(供 LazyColumn 渲染)。携带层级 depth 与展开态。
 *
 * @param node 对应的树节点(用于 toggle 展开折叠)
 */
data class FlatComment(
    val node: Node,
    val depth: Int,
    val hasKids: Boolean,
    val expanded: Boolean,
    val childrenLoading: Boolean,
    val childrenError: String?,
    val translationState: TranslationState
) {
    /** LazyColumn key —— 同一条评论唯一。 */
    val key: String get() = "${node.comment.id}-${depth}"
}
