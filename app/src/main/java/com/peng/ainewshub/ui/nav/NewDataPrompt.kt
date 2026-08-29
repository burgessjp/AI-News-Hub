package com.peng.ainewshub.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.data.source.ArchiveHttpClient
import com.peng.ainewshub.ui.theme.AppText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.peng.ainewshub.data.prefs.SettingsStore

/**
 * 冷启动新数据弹窗载荷。
 *
 * [generatedAt] 为最新批次指纹(latest_overview.generatedAt),弹窗关闭时写回
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
 * 关闭(「我知道了」/ 点外部 / 下滑)都写回指纹(= 用户已感知该批次),语义上与每日
 * 通知互补:每天至多 1 条提醒,通知与弹窗任一形式先触达即静默;用户冷启动在先,
 * 当天批次就不再推送打扰。
 *
 * [deferWhile] 为 true 时(首启引导正在展示)只照常探测、**暂停弹窗渲染**,引导
 * 关闭后参数翻 false 随重组补弹 —— 升级用户可能同时满足两个弹窗的触发条件
 * (通知开关已开 + 批次指纹落后 + 引导未看过),不互斥会双层弹窗同屏;引导优先。
 */
@Composable
internal fun NewDataPromptHost(
    settingsStore: SettingsStore,
    deferWhile: Boolean = false
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
        // networkOnly 探测:必须真实打网络(绕过内存缓存与磁盘兜底),断网/服务端
        // 故障一律失败 → 不弹窗 —— 不能拿盘上旧数据当「新数据」提示;联网冷启动
        // 相比共享缓存至多多一次 index 请求,可接受
        val json = try {
            ArchiveHttpClient.fetchLatestOverview(networkOnly = true)
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

    // 引导展示期间不渲染(探测照常);引导关闭后 deferWhile 翻 false,此处随重组补弹
    newDataPrompt?.takeIf { !deferWhile }?.let { prompt ->
        NewDataSheet(
            digest = prompt.digest,
            onDismiss = { dismissNewDataPrompt(prompt) }
        )
    }
}

/**
 * 冷启动新数据底部半屏提示(对齐 OnboardingSheet 的视觉语言):标题 + 今日综述正文 +
 * 全宽「我知道了」按钮。按钮点击、点外部、下滑关闭都走 [onDismiss](写回批次指纹)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewDataSheet(
    digest: String?,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.notify_daily_title),
                style = AppText.titleSection,
                color = cs.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = digest ?: stringResource(R.string.notify_daily_text_fallback),
                style = AppText.body,
                color = cs.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.common_got_it),
                    style = AppText.body,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
