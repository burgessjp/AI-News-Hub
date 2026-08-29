package com.peng.ainewshub.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.TranslationState

/**
 * 监听翻译状态 Map,任一条变成 CONFIG_MISSING 错误时弹一次 Snackbar 引导去配置 AI 服务。
 *
 * 收口 6 个源列表 Screen 此前逐字复制的 `LaunchedEffect(states) {
 * states.values.firstOrNull { it is Error && it.message == CONFIG_MISSING }?.let { ... } }`
 * 样板(文案取词 + showSnackbar + ActionPerformed → onOpenSettings)。
 *
 * @param states 翻译状态 Map(任一 value 为 [TranslationState.Error] 且 message 为
 *               [TranslationState.CONFIG_MISSING] 时触发)
 * @param snackbarHostState 各 Screen 的 SnackbarHostState(与 Scaffold 绑定)
 * @param onOpenSettings 用户点 Snackbar 的「去设置」动作时的回调
 */
@Composable
fun TranslateConfigMissingEffect(
    states: Map<*, TranslationState>,
    snackbarHostState: SnackbarHostState,
    onOpenSettings: () -> Unit
) {
    val message = stringResource(R.string.common_translate_config_missing)
    val actionLabel = stringResource(R.string.common_go_settings)
    LaunchedEffect(states) {
        states.values.firstOrNull { it is TranslationState.Error && it.message == TranslationState.CONFIG_MISSING }
            ?.let {
                val result = snackbarHostState.showSnackbar(message = message, actionLabel = actionLabel)
                if (result == SnackbarResult.ActionPerformed) onOpenSettings()
            }
    }
}
