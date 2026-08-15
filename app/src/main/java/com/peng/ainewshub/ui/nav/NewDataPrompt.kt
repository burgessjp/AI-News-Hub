package com.peng.ainewshub.ui.nav

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.peng.ainewshub.R
import com.peng.ainewshub.data.source.ArchiveHttpClient
import com.peng.ainewshub.ui.more.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 冷启动新数据弹窗载荷。
 *
 * [generatedAt] 为最新批次指纹(latest_overview.generatedAt),弹窗确认/忽略时写回
 * `lastNotified_overview_at`(与每日通知共用,见 [NewDataPromptHost] 注释);[digest] 为今日综述
 * 正文(流水线内容,始终中文;空则弹窗正文退回 fallback 文案)。
 */
internal data class NewDataPrompt(
    val generatedAt: Long,
    val digest: String?
)

/**
 * 冷启动弹窗检查的进程级会话闸门:每次进程启动只查一次。
 * 弹窗检查挂在根组合 `LaunchedEffect(Unit)` 上,旋转/语言切换 recreate 等任何
 * Activity 重建都会重跑 effect —— 若不加闸门,一次会话内会重复打网络、重复弹窗。
 */
private object NewDataPromptGate {
    @Volatile
    var shouldCheck = true
}

/**
 * 冷启动新数据全局弹窗宿主:检查逻辑 + 弹窗渲染,悬浮于任意 tab / 二级页之上。
 *
 * 冷启动新数据弹窗随「每日更新通知」开关,与通知共用批次指纹 lastNotifiedOverviewAt
 * —— 开关开启且最新 latest_overview.generatedAt 领先指纹 → 全局弹窗提示。
 * 确认/忽略都写回指纹(= 用户已感知该批次),语义上与每日通知互补:每天至多 1 条
 * 提醒,通知与弹窗任一形式先触达即静默;用户冷启动在先,当天批次就不再推送打扰。
 *
 * onGoOverview:「查看」直达总览根页(切 tab + 清空该 tab 二级栈)。
 */
@Composable
internal fun NewDataPromptHost(
    settingsStore: SettingsStore,
    onGoOverview: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var newDataPrompt by remember { mutableStateOf<NewDataPrompt?>(null) }
    val dismissNewDataPrompt: (NewDataPrompt) -> Unit = { prompt ->
        newDataPrompt = null
        scope.launch { settingsStore.setLastNotifiedOverviewAt(prompt.generatedAt) }
    }
    LaunchedEffect(Unit) {
        // 会话闸门:一次进程启动只查一次(见 NewDataPromptGate);查过即关闭,
        // recreate 后的重组不再重复触发网络检查与弹窗
        if (!NewDataPromptGate.shouldCheck) return@LaunchedEffect
        NewDataPromptGate.shouldCheck = false
        // 开关关闭不支持弹窗;首帧默认值不可信,须读 DataStore 真值
        if (!settingsStore.prefsFlow.first().dailyNotify) return@LaunchedEffect
        val json = try {
            ArchiveHttpClient.fetchLatestOverview()
        } catch (e: CancellationException) {
            // 组合销毁的取消要放行重抛,不能当失败吞掉(否则继续走完剩余判断,
            // 破坏结构化取消语义)
            throw e
        } catch (e: Exception) {
            null
        } ?: return@LaunchedEffect
        val generatedAt = json.optLong("generatedAt", 0L)
        if (generatedAt > 0 && generatedAt > settingsStore.lastNotifiedOverviewAt()) {
            val digest = json.optString("digest").orEmpty().trim().takeIf { it.isNotEmpty() }
            newDataPrompt = NewDataPrompt(generatedAt, digest)
        }
    }

    newDataPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { dismissNewDataPrompt(prompt) },
            title = { Text(stringResource(R.string.notify_daily_title)) },
            text = { Text(prompt.digest ?: stringResource(R.string.notify_daily_text_fallback)) },
            confirmButton = {
                TextButton(onClick = {
                    dismissNewDataPrompt(prompt)
                    onGoOverview()
                }) { Text(stringResource(R.string.common_view)) }
            },
            dismissButton = {
                TextButton(onClick = { dismissNewDataPrompt(prompt) }) {
                    Text(stringResource(R.string.common_ignore))
                }
            }
        )
    }
}
