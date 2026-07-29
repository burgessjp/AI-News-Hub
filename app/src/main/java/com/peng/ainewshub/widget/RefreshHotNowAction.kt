package com.peng.ainewshub.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

/**
 * 小组件刷新按钮(及空态「点此刷新重试」)的点击回调:强制拉取一次最新总览。
 * 成功后 [HotNowWidgetUpdater.refresh] 内部 updateAll 触发重渲染;
 * 失败时保留旧数据,界面无破坏性变化。
 */
class RefreshHotNowAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        HotNowWidgetUpdater.refresh(context, force = true)
    }
}
