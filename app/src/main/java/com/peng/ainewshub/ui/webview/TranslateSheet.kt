package com.peng.ainewshub.ui.webview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.theme.AppText

/**
 * 翻译弹层 —— 原文/译文平铺对照列表(高信息密度)。
 *
 * [ModalBottomSheet] 半屏起步(skipPartiallyExpanded=false),可手势拖拽到全屏、
 * 拖下关闭。每段平铺:原文在上(主色 [cs.onSurface])、译文在下(次要色
 * [cs.onSurfaceVariant]),靠颜色区分层次不靠卡片,密度高、阅读连贯。译文未到时
 * 不占位,到达后在原文下方追加。翻译中头部展示线性进度条,完成态列表末尾给
 * 「翻译完成」收尾提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslateSheet(
    originals: List<String>,
    results: List<String?>?,
    progress: Pair<Int, Int>?,
    translating: Boolean,
    onCancelTranslate: () -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    // 全屏展开:跳过半屏态,直接展开到全屏
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        // 头部:图标 + 标题 + 进度/取消
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Translate,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.webview_translate_sheet_title),
                style = AppText.titleItem,
                color = cs.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            if (translating && progress != null) {
                Text(
                    text = stringResource(R.string.webview_translate_progress, progress.first, progress.second),
                    style = AppText.caption,
                    color = cs.onSurfaceVariant
                )
            }
            if (translating) {
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onCancelTranslate) { Text(stringResource(R.string.common_cancel)) }
            }
        }
        // 头部进度条:翻译中展示确定性进度;无轨道细线,与顶栏加载条同语言
        val progressFraction = if (translating && progress != null && progress.second > 0) {
            progress.first.toFloat() / progress.second
        } else null
        if (progressFraction != null) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(2.dp),
                color = cs.primary,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Round
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        // 空白块(空段落等)不参与展示
        val blocks = remember(originals) { originals.indices.filter { originals[it].isNotBlank() } }
        // 翻译完成判定:非翻译中、有结果、且有至少一条译文
        val finished = !translating && results != null && results.any { it != null }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(blocks.size) { position ->
                val index = blocks[position]
                val original = originals[index]
                val translated = results?.getOrNull(index)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    // 原文(在上):主色,信息主体
                    Text(
                        text = original,
                        style = AppText.body,
                        color = cs.onSurface
                    )
                    // 译文(在下):左侧 primary 色细条做视觉锚点 + 弱化色文字,
                    // 靠色条明确区分译文与原文(单靠 onSurfaceVariant 与 onSurface 差别太小)
                    if (translated != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(cs.primary, RoundedCornerShape(2.dp))
                            )
                            Text(
                                text = translated,
                                style = AppText.body,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
            // 完成态收尾:居中 check + 「翻译完成」
            if (finished) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.webview_translate_done),
                            style = AppText.caption,
                            color = cs.primary
                        )
                    }
                }
            }
            item {
                Spacer(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .height(16.dp)
                )
            }
        }
    }
}
