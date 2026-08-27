package com.peng.ainewshub.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ripple
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
 * 触控目标:此前 `height(20.dp)` 硬压出 20dp 可点区,是全 App 触控最频繁的小控件、
 * 远低于 48dp 标准;现经 [inlineTouchTarget] 紧凑占位 + 48dp 命中区外扩修复,
 * 行高与视觉位置不变(详见该修饰符注释)。
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
    Box(modifier = Modifier.inlineTouchTarget()) {
        Box(
            modifier = Modifier
                // 命中区高度 ≥48dp(标题行对其只有行高约束);宽度随文案 + 8dp 横向命中余量
                .heightIn(min = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false),
                    enabled = enabled,
                    onClick = onClick
                )
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) cs.primary.copy(alpha = AppAlpha.primaryEmphasis) else cs.onSurfaceVariant
            )
        }
    }
}

/**
 * 行内小控件触控外扩 —— 外层按 [maxPlaceHeight] 紧凑占位(不撑高所在标题行),
 * 子内容实际按 ≥48dp 触控目标测量并竖直居中外扩;越界部分与相邻行重叠仍可点
 * (父级未 clip,与 M3 minimumInteractiveComponentSize 的重叠语义一致)。
 * 仅供 InlineTranslateButton 这类「嵌在标题行里的小文字按钮」使用;
 * 独立控件直接用 minimumInteractiveComponentSize 即可,不需要本修饰符。
 */
private fun Modifier.inlineTouchTarget(maxPlaceHeight: Dp = 20.dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
    )
    val height = minOf(placeable.height, maxPlaceHeight.roundToPx())
    layout(placeable.width, height) {
        // 竖直居中放置:外扩量上下均分,文字与标题首行的视觉相对位置保持不变
        placeable.placeRelative(0, -(placeable.height - height) / 2)
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
