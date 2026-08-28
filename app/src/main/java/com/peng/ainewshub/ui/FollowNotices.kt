package com.peng.ainewshub.ui

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 「关键词关注结果」全局轻提示事件。
 *
 * 趋势页展开区「+ 关注」一键把热词写入关注词(SettingsStore);写入结果
 * (成功 / 达上限)经本通道发一次事件,根组件(AiNewsHubApp)订阅后弹顶部
 * 玻璃胶囊「已关注 X」/「最多关注 20 个关键词」—— 与 RefreshNotices 的
 * 「已是最新批次」同款消费模式(VM 不持有 Compose 状态,走单例事件总线)。
 *
 * 守卫约定(由调用方 TrendsViewModel 自行满足,本通道不做二次校验):
 *  - 已关注 / 空词不发(按钮已呈已关注态,无变化可告知);
 *  - 写入失败(DataStore 异常)不发(静默,用户再点一次即可)。
 */
object FollowNotices {

    /** 关注动作结果(宿主据此选文案与图标)。 */
    enum class Outcome { Added, Capped }

    /** 一次关注动作的事件:关键词(展示原样大小写)+ 结果。 */
    data class Event(val keyword: String, val outcome: Outcome)

    /** 缓冲 4 条容纳瞬时并发;满时丢最旧(重复提示本就该被顶替)。 */
    private val _events = MutableSharedFlow<Event>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 订阅入口。 */
    val events: SharedFlow<Event> = _events

    /** 发一次关注结果事件(非挂起,任意线程可调)。 */
    fun notify(event: Event) {
        _events.tryEmit(event)
    }
}
