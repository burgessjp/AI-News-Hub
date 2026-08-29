package com.peng.ainewshub.ui.webview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.WebAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
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
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                label = stringResource(R.string.webview_bar_back),
                enabled = canGoBack,
                onClick = onBack,
                modifier = Modifier.weight(1f)
            )
            WebBarItem(
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                label = stringResource(R.string.webview_bar_forward),
                enabled = canGoForward,
                onClick = onForward,
                modifier = Modifier.weight(1f)
            )
            WebBarItem(
                icon = if (readerActive) Icons.Outlined.WebAsset else Icons.AutoMirrored.Outlined.MenuBook,
                label = stringResource(
                    if (readerActive) R.string.webview_bar_exit_reader else R.string.webview_bar_reader
                ),
                enabled = !readerLoading,
                onClick = onToggleReader,
                modifier = Modifier.weight(1f)
            )
            // 翻译:阅读模式下可用;翻译中或已有结果时图标高亮表示已激活
            WebBarItem(
                icon = Icons.Outlined.Translate,
                label = stringResource(R.string.webview_bar_translate),
                enabled = readerActive && translateEnabled,
                highlight = translateActive,
                onClick = onTranslate,
                modifier = Modifier.weight(1f)
            )
            WebBarItem(
                icon = Icons.Outlined.Share,
                label = stringResource(R.string.common_share),
                enabled = true,
                onClick = onShare,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 底部工具栏单项:图标 + 小字标签,触摸区 ≥48dp;禁用态用 outline 色压低。 */
@Composable
private fun WebBarItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    // 高亮(已激活)用 primary 色,否则用常规禁用/可用色
    val color = when {
        highlight -> MaterialTheme.colorScheme.primary
        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.outline
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(20.dp))
        Text(text = label, style = AppText.caption, color = color)
    }
}
