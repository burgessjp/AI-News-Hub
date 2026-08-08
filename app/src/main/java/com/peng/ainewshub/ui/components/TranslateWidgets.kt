package com.peng.ainewshub.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.TranslationState
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText

/**
 * 翻译相关复用组件 —— 内联「译」按钮 + 译文文本。
 *
 * 此前定义在 HackerNewsScreen.kt(internal),被 GitHubTrending / HuggingFace /
 * ProductHunt / RundownAi / OpenAiAnthropic / HackerNewsComments 等 7 个 Screen
 * 跨文件复用。按「跨文件复用的组件收口 components/」约定迁出。
 */

/**
 * 内联「译」按钮 —— 跟在标题行末/meta 信息后,不占独立行。
 *
 * 文案随 [state] 变化:Idle→「译」;Loading→「翻译中…」;Success→「收起/显示译文」
 * (由 [collapsed] 决定);Error→「内容过短 / 翻译失败,重试」。CONFIG_MISSING 不渲染
 * (由调用方 snackbar 引导)。折叠态由调用方持有,使译文 Text 与按钮文案共享。
 *
 * @param collapsed 当前译文是否已折叠(Success 态用)
 * @param onToggleCollapse 切换折叠(Success 态点按钮)
 * @param onTranslate 触发翻译(Idle / Error 重试)
 */
@Composable
fun InlineTranslateButton(
    state: TranslationState,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onTranslate: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val text = when (state) {
        TranslationState.Idle -> stringResource(R.string.translate_label)
        TranslationState.Loading -> stringResource(R.string.items_translating)
        is TranslationState.Success -> if (collapsed) stringResource(R.string.items_show_translation) else stringResource(R.string.items_hide_translation)
        is TranslationState.Error -> when (state.message) {
            TranslationState.TOO_SHORT -> stringResource(R.string.error_too_short)
            TranslationState.CONFIG_MISSING -> return // 由 snackbar 引导,不渲染按钮
            else -> stringResource(R.string.items_translate_failed_retry)
        }
    }
    val enabled = state !is TranslationState.Loading
    val onClick: () -> Unit = when (state) {
        is TranslationState.Success -> onToggleCollapse
        else -> onTranslate
    }
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        modifier = Modifier.height(20.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) cs.primary.copy(alpha = AppAlpha.primaryEmphasis) else cs.onSurfaceVariant
        )
    }
}

/** 译文纯文本(弱色小字),供标题/评论译文区复用。 */
@Composable
fun TranslatedText(translated: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = translated,
        style = AppText.bodySmall,
        color = cs.onSurfaceVariant,
        modifier = modifier.fillMaxWidth()
    )
}
