package com.example.aihot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aihot.data.HackerNewsComment
import com.example.aihot.data.HackerNewsRepository
import com.example.aihot.data.HackerNewsStory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * HackerNews 评论树 ViewModel —— 懒加载(按需展开)。
 *
 * 策略:进入页面只拉取 story 的一级评论;用户点击某条有子评论的节点时,
 * 才拉取其下一层([Node.children]),不预取整棵子树。避免热门帖评论数百条时
 * 一次性发起海量请求。
 *
 * 展开状态([Node.expanded])独立于数据;[flatten] 仅把「已展开」节点的子节点
 * 递归铺平(带 [FlatComment.depth])供 LazyColumn 渲染。
 */
class HackerNewsCommentsViewModel : ViewModel() {

    private val repo = HackerNewsRepository()

    private val _state = MutableStateFlow<UiState<List<FlatComment>>>(UiState.Loading)
    val state: StateFlow<UiState<List<FlatComment>>> = _state.asStateFlow()

    /** 顶层评论节点(story 的一级评论)。展开/折叠通过修改节点树后重新 flatten 实现。 */
    private val roots = mutableListOf<Node>()

    fun load(story: HackerNewsStory) {
        if (roots.isNotEmpty()) return // 已加载过,不重复
        _state.value = UiState.Loading
        viewModelScope.launch {
            runCatching { repo.fetchComments(story.kids) }
                .onSuccess { list ->
                    roots.clear()
                    roots.addAll(list.map { Node(it) })
                    emitFlattened()
                }
                .onFailure { _state.value = UiState.Error(it.message ?: "未知错误") }
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
                        node.childrenError = it.message ?: "未知错误"
                        emitFlattened()
                    }
            }
        }
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
                        childrenError = node.childrenError
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
    val childrenError: String?
) {
    /** LazyColumn key —— 同一条评论唯一。 */
    val key: String get() = "${node.comment.id}-${depth}"
}
