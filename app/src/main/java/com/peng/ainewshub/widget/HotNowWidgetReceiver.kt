package com.peng.ainewshub.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 「今日热点」小组件 Receiver —— 系统经此类发现并驱动小组件(注册见 AndroidManifest)。
 * 类名/包名变更会直接导致已添加的小组件失效,保持稳定。
 */
class HotNowWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HotNowWidget()
}
