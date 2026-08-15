package com.peng.ainewshub.ui.nav

import android.os.Bundle
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.peng.ainewshub.data.SummaryRepository
import com.peng.ainewshub.ui.components.AppTab

/**
 * 多栈导航状态机 —— 顶层壳 AiNewsHubApp 的导航模型收编为单类。
 *
 * 模型:
 *  - currentTab: 当前选中的 4 个根 tab 之一(总览 / 摘要 / 趋势 / 更多)
 *  - pageStacks: 每个 tab 独立的二级页栈(栈空 = 处于根)
 *
 * 行为(与原 AiNewsHubApp 内联实现逐条一致):
 *  - [selectTab] 切 tab:各 tab 二级栈保留;重击当前 tab 时栈非空 → 清空回根,
 *    已在根 → reselectTick++(根屏据此滚回顶部并刷新)
 *  - [push] 进二级页 / [pop] 弹栈顶;isNavigatingBack 记录转场方向(push 前进 / pop 返回)
 *  - [goToRoot] 直达某 tab 根页(冷启动新数据弹窗「查看」):切 tab + 清空该 tab 栈
 */
@Stable
internal class AppNavState(
    initialTab: AppTab,
    initialStacks: Map<AppTab, List<Page>>,
) {
    /** 当前选中的根 tab。 */
    var currentTab by mutableStateOf(initialTab)
        private set

    /** 每 tab 独立的二级页栈。 */
    var pageStacks by mutableStateOf(initialStacks)
        private set

    /** 转场方向:push 前进 false / pop 返回 true。预测返回手势开始时由宿主置 true。 */
    var isNavigatingBack by mutableStateOf(false)

    /** 重击当前 tab 信号:已在根页时递增,根屏据此滚回顶部并刷新。 */
    var reselectTick by mutableStateOf(0)
        private set

    /** 当前 tab 的二级页栈(空 = 处于根)。 */
    val currentPages: List<Page> get() = pageStacks[currentTab].orEmpty()

    /** 是否处于根页(当前 tab 栈空)。 */
    val isRoot: Boolean get() = currentPages.isEmpty()

    /** 当前屏幕:根(tab) 或 二级页。用作转场的 currentState/targetState。 */
    val screen: Screen
        get() = if (isRoot) Screen.Root(currentTab) else Screen.Secondary(currentPages.last())

    /** 进入二级页:push 到当前 tab 栈(前进方向)。 */
    fun push(page: Page) {
        isNavigatingBack = false
        pageStacks = pageStacks.toMutableMap().apply {
            this[currentTab] = (this[currentTab].orEmpty()) + page
        }
    }

    /** 弹当前 tab 栈顶(返回方向);栈空不动,交系统默认退出 App。 */
    fun pop() {
        if (pageStacks[currentTab].orEmpty().isNotEmpty()) {
            isNavigatingBack = true
            pageStacks = pageStacks.toMutableMap().apply {
                this[currentTab] = (this[currentTab].orEmpty()).dropLast(1)
            }
        }
    }

    /** 切 tab(重击语义见类注释)。 */
    fun selectTab(tab: AppTab) {
        if (tab != currentTab) {
            isNavigatingBack = false
            currentTab = tab
        } else if (pageStacks[currentTab].orEmpty().isNotEmpty()) {
            isNavigatingBack = true
            pageStacks = pageStacks.toMutableMap().apply {
                this[currentTab] = emptyList()
            }
        } else {
            reselectTick++
        }
    }

    /** 直达某 tab 根页:切 tab + 清空该 tab 二级栈,前进方向(新数据弹窗「查看」)。 */
    fun goToRoot(tab: AppTab) {
        isNavigatingBack = false
        currentTab = tab
        pageStacks = pageStacks.toMutableMap().apply { this[tab] = emptyList() }
    }
}

/** Saver 内 currentTab 的存取键(与各 tab 栈键 tab.name 不冲突)。 */
private const val SAVER_KEY_TAB = "currentTab"

/**
 * [AppNavState] 的持久化入口:currentTab + pageStacks 一并 rememberSaveable,
 * 转屏/进程被杀后仍可恢复(需自定义 Saver,因 Page 含业务对象、AppTab 是 enum,
 * 默认 Bundle 无法直接存 Map)。页栈的 Bundle 格式与 [stacksToBundle] 完全一致。
 *
 * webFallbackTitle:恢复 Web 页且 Bundle 缺 title 时的占位文案(随语言取词,common_loading)。
 */
@Composable
internal fun rememberAppNavState(webFallbackTitle: String): AppNavState =
    rememberSaveable(saver = appNavStateSaver(webFallbackTitle)) {
        AppNavState(AppTab.Overview, emptyMap())
    }

/** AppNavState 持久化:每 tab 一个栈键(格式同 [stacksToBundle])+ 追加 currentTab 键。 */
private fun appNavStateSaver(webFallbackTitle: String) = Saver<AppNavState, Bundle>(
    save = { state ->
        stacksToBundle(state.pageStacks).apply {
            putString(SAVER_KEY_TAB, state.currentTab.name)
        }
    },
    restore = { b ->
        val tab = AppTab.entries.firstOrNull { it.name == b.getString(SAVER_KEY_TAB) }
            ?: AppTab.Overview
        AppNavState(tab, stacksFromBundle(b, webFallbackTitle))
    }
)

/** 取某个二级页持有的列表滚动状态(上提原因见 AiNewsHubApp 内说明)。 */
internal fun MutableMap<Page, LazyListState>.forPage(page: Page): LazyListState =
    getOrPut(page) { LazyListState() }

/** 取某个二级页持有的 Pager 状态(历史摘要按日期页;上提原因同列表状态)。 */
internal fun MutableMap<Page, PagerState>.forPagePager(page: Page): PagerState =
    getOrPut(page) { PagerState { SummaryRepository.SOURCE_KEYS.size } }
