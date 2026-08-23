package com.peng.ainewshub.ui

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 「刷新后无新批次」全局轻提示事件。
 *
 * 归档内容一天只更新数批,用户下拉强刷大概率拿回同一份数据;若刷新动画完整播放
 * 却内容纹丝不动,会被误读为「App 坏了」。各根页 ViewModel 在**用户主动强刷**
 * 成功、且刷新前后批次指纹相同、且非离线兜底时,经 [notifyNoNewBatch] 发一次
 * 事件;根组件(AiNewsHubApp)订阅后弹一次性 Snackbar「已是最新批次 · 数据
 * 生成于 …」——与 ArchiveHttpClient.offlineMode 的离线提示同款消费模式。
 *
 * 守卫约定(由各 ViewModel 自行满足,本通道不做二次校验):
 *  - 仅 force 刷新路径发(init 自动加载/缓存命中不发);
 *  - 刷新失败不发(错误态自身已可见);
 *  - 离线兜底(offlineMode=true)不发(离线 banner 已覆盖,叠加提示反而误导)。
 *
 * 事件值为当前数据的时间戳(毫秒),供 Snackbar 标注「数据生成于 …」;
 * 无有效时间戳时传 0,Snackbar 退化为不带时间的短文案。
 */
object RefreshNotices {

    /** 缓冲 4 条容纳瞬时并发(多页连刷);满时丢最旧即可(重复提示本就该被节流)。 */
    private val _noNewBatch = MutableSharedFlow<Long>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 订阅入口:值 = 数据时间戳(毫秒),0 = 无有效时间戳。 */
    val noNewBatch: SharedFlow<Long> = _noNewBatch

    /** 发一次「无新批次」事件(非挂起,任意线程可调)。 */
    fun notifyNoNewBatch(dataAtMs: Long) {
        _noNewBatch.tryEmit(dataAtMs)
    }
}
