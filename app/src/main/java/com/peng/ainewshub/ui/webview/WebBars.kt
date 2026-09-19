package com.peng.ainewshub.ui.webview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.anim.Motion
import com.peng.ainewshub.ui.theme.AppText

/**
 * 顶部加载进度条 —— Safari/Chrome 风格的细线进度。
 *
 * 与默认 [LinearProgressIndicator] 的差异:
 *  1. 更细(2dp),贴顶精致,不抢视觉
 *  2. 无背景轨道(trackColor = transparent),加载区是干净的细线,
 *     不再铺满整条灰轨显得笨重
 *  3. [AnimatedVisibility] 包裹,加载完成时平滑淡出,而非硬切消失
 *
 * @param loading  是否加载中(控制显隐)
 * @param progress 0f..1f 加载进度
 */
@Composable
internal fun TopProgressBar(
    loading: Boolean,
    progress: () -> Float
) {
    AnimatedVisibility(
        visible = loading,
        enter = fadeIn(tween(Motion.SHORT)),
        exit = fadeOut(tween(Motion.MEDIUM))
    ) {
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {}
        )
    }
}

/**
 * WebView 底部工具栏 —— 高频导航操作(后退/前进/阅读模式/分享)提为一级操作,
 * 不再藏在「更多」菜单里(对齐浏览器惯例);阅读模式按 [readerActive] 切换进出。
 * 视频全屏时由调用方整体隐藏。
 */
@Composable
internal fun WebBottomBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    readerActive: Boolean,
    readerLoading: Boolean,
    translateEnabled: Boolean,
    translateActive: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onToggleReader: () -> Unit,
    onTranslate: () -> Unit,
    onShare: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surface)
            .navigationBarsPadding()
    ) {
        // 顶部发丝线,与网页内容区分隔
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(cs.outlineVariant)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            WebBarItem(
                label = stringResource(R.string.webview_bar_back),
                enabled = canGoBack,
                onClick = onBack,
                modifier = Modifier.weight(1f)
            )
            WebBarItem(
                label = stringResource(R.string.webview_bar_forward),
                enabled = canGoForward,
                onClick = onForward,
                modifier = Modifier.weight(1f)
            )
            WebBarItem(
                label = stringResource(
                    if (readerActive) R.string.webview_bar_exit_reader else R.string.webview_bar_reader
                ),
                enabled = !readerLoading,
                onClick = onToggleReader,
                modifier = Modifier.weight(1f)
            )
            // 翻译:非阅读模式下点击会先自动进阅读模式再接续翻译(调用方负责),
            // 阅读模式构建中(readerLoading)暂不可点;翻译中/已有结果时图标高亮
            WebBarItem(
                label = stringResource(R.string.webview_bar_translate),
                enabled = translateEnabled && !readerLoading,
                highlight = translateActive,
                onClick = onTranslate,
                modifier = Modifier.weight(1f)
            )
            WebBarItem(
                label = stringResource(R.string.common_share),
                enabled = true,
                onClick = onShare,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 底部工具栏单项:纯文字(去图标,与根底栏铅字块同语言)—— 中文双字词本身
 * 就是最好的符号;高亮(激活)用 primary + SemiBold,禁用态用 outline 色压低,
 * 触摸区 ≥48dp。
 */
@Composable
private fun WebBarItem(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    val color = when {
        highlight -> cs.primary
        enabled -> cs.onSurfaceVariant
        else -> cs.outline
    }
    Text(
        text = label,
        style = AppText.bodySmall,
        fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp)
    )
}
