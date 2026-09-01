package com.peng.ainewshub.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.peng.ainewshub.data.prefs.SettingsStore
import com.peng.ainewshub.data.source.AppConfigSync
import com.peng.ainewshub.notify.DailyNotifyScheduler
import kotlinx.coroutines.flow.first

/**
 * 远程配置同步的进程级会话闸门:每次进程启动只拉一次(见 [AppConfigSyncHost])。
 * 与 NewDataPromptGate 同理 —— 挂根组合 `LaunchedEffect(Unit)`,旋转/语言切换等
 * Activity 重建都会重跑 effect,不加闸门一次会话内会重复打网络。
 */
private object AppConfigSyncGate {
    @Volatile
    var shouldSync = true
}

/**
 * 远程配置(app_config.json)全局同步宿主:无 UI,只承载「每次进程启动拉一次
 * 远程配置并应用到 PipelineSchedule」(批次时刻表等,见 [AppConfigSync])。
 *
 * 批次表变化且每日通知开关开启时重排 WorkManager 检查链 —— 已入队任务带着旧
 * 时刻表的 delay,不重排会偏差到下次 Worker 运行才自愈。
 */
@Composable
internal fun AppConfigSyncHost(settingsStore: SettingsStore) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        // 会话闸门:一次进程启动只同步一次,recreate 后的重组不再重复打网络
        if (!AppConfigSyncGate.shouldSync) return@LaunchedEffect
        AppConfigSyncGate.shouldSync = false
        val changed = AppConfigSync.refresh()
        if (changed && settingsStore.prefsFlow.first().dailyNotify) {
            DailyNotifyScheduler.enqueueNext(context)
        }
    }
}
