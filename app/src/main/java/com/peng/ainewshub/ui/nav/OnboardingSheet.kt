package com.peng.ainewshub.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.theme.AppText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.peng.ainewshub.data.prefs.SettingsStore

/**
 * 首次启动引导的进程级会话闸门:每次进程启动只查一次。
 * 检查挂在根组合 `LaunchedEffect(Unit)` 上,旋转/语言切换 recreate 会重跑 effect,
 * 不加闸门会在一次会话内重复弹;持久化的布尔键兜底跨会话只展示一次。
 */
private object OnboardingGate {
    @Volatile
    var shouldCheck = true
}

/**
 * 首次启动引导宿主 —— 一次性 ModalBottomSheet,悬浮于任意 tab / 二级页之上。
 *
 * 展示对象:**所有用户**安装/升级后首次启动各展示一次(布尔键无历史版本记录,
 * 存量老用户升级后也会看到 —— 正好借此传达「批次制更新、刷新无新内容属正常」
 * 的产品心智,这是本次引导的核心目的)。
 *
 * 内容刻意泛化:不写死批次时刻(流水线时间会调),不穷举功能(会过期),
 * 只讲 4 条「猜不到且影响预期」的事:AI 预生成、批次制更新、阅读体验、隐藏手势。
 *
 * 按钮点击与下滑关闭([ModalBottomSheet] 的 onDismissRequest)都写回完成标记。
 */
@Composable
internal fun OnboardingHost(settingsStore: SettingsStore) {
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 会话闸门:一次进程启动只查一次;查过即关闭,recreate 重组不再触发
        if (!OnboardingGate.shouldCheck) return@LaunchedEffect
        OnboardingGate.shouldCheck = false
        // 首帧默认值不可信,须读 DataStore 真值
        if (!settingsStore.onboardingDoneFlow.first()) show = true
    }
    val dismiss: () -> Unit = {
        show = false
        scope.launch { settingsStore.setOnboardingDone() }
    }
    if (show) OnboardingSheet(onDismiss = dismiss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingSheet(onDismiss: () -> Unit) {
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
                text = stringResource(R.string.onboarding_title),
                style = AppText.titleSection,
                color = cs.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = AppText.bodySmall,
                color = cs.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            OnboardingRow(
                titleRes = R.string.onboarding_item_brief_title,
                descRes = R.string.onboarding_item_brief_desc
            )
            OnboardingRow(
                titleRes = R.string.onboarding_item_batch_title,
                descRes = R.string.onboarding_item_batch_desc
            )
            OnboardingRow(
                titleRes = R.string.onboarding_item_reading_title,
                descRes = R.string.onboarding_item_reading_desc
            )
            OnboardingRow(
                titleRes = R.string.onboarding_item_tips_title,
                descRes = R.string.onboarding_item_tips_desc
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.onboarding_start),
                    style = AppText.body,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/** 单条要点行:标题 + 两行说明(纯文字,靠字重与留白分层,与全 App 目录行同语言)。 */
@Composable
private fun OnboardingRow(
    titleRes: Int,
    descRes: Int
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(titleRes),
                style = AppText.titleCompact,
                color = cs.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(descRes),
                style = AppText.bodySmall,
                color = cs.onSurfaceVariant
            )
        }
    }
}
