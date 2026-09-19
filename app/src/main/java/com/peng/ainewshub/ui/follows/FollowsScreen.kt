package com.peng.ainewshub.ui.follows

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.peng.ainewshub.R
import com.peng.ainewshub.data.repo.FollowFeedItem
import com.peng.ainewshub.data.repo.FollowsRepository
import com.peng.ainewshub.data.repo.SummaryRepository
import com.peng.ainewshub.ui.components.SectionHeader
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.peng.ainewshub.data.prefs.MAX_FOLLOWED_KEYWORDS

/**
 * 「我的关注」共享件 —— 「热词」根屏(HotwordsScreen)上段的渲染实现。
 *
 * 原关注 tab 根屏已随 v1.4.0 日刊化并入 [HotwordsScreen](关注命中流在上、
 * 热词榜在下的单页);本文件只保留共享渲染件:关键词区([FollowsHeaderRow])、
 * 命中条目行([FollowItemRow])、页脚([FollowsFooter])与管理弹层
 * ([FollowsManageSheet])。语料与过滤在 [FollowsViewModel] 内完成
 * (当日总览 Top10 + 8 源结构化摘要,见 FollowsRepository)。
 */

/** 关键词区:统计行 + 编辑入口 + 关键词 chips(点选单选过滤,再点恢复全部)。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FollowsHeaderRow(
    ui: FollowsUi,
    onSelect: (String) -> Unit,
    onManage: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.follows_stats_caption,
                    ui.keywords.size,
                    // 单选过滤时以当前过滤结果计数,与列表所见一致
                    ui.items.size
                ),
                style = AppText.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 「+」文字符(去图标);触控由 padding 撑足,读屏语义保留在文案
            Text(
                text = stringResource(R.string.follows_add_action),
                style = AppText.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onManage)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ui.keywords.forEach { keyword ->
                FollowKeywordChip(
                    text = keyword,
                    selected = keyword == ui.selectedKeyword,
                    onClick = { onSelect(keyword) }
                )
            }
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/**
 * 关注词 chip —— 配色对齐历史摘要页源名 chips:选中 primary 实底 SemiBold,
 * 未选 surfaceContainerHigh;点选 = 只看该词,再点恢复全部。
 * selectable 声明选中态,读屏可播报(此前只靠颜色区分)。
 */
@Composable
private fun FollowKeywordChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = text,
        style = AppText.bodySmall,
        fontWeight = if (selected) FontWeight.SemiBold else null,
        color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 200.dp)
            .clip(CircleShape)
            .background(if (selected) cs.primary else cs.surfaceContainerHigh)
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/**
 * 命中条目行 —— 结构对齐本地搜索结果行(标题/摘要/来源),另带:
 * 命中词(primary 小字)、总览条目的指标行与 Breaking 标签。
 * url 为空的条目只读不可点(与「今天」页分源条目一致)。
 */
@Composable
internal fun FollowItemRow(
    item: FollowFeedItem,
    sourceLabel: String,
    isRead: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val entry = item.entry
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                enabled = entry.url.isNotEmpty(),
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (entry.breaking) {
                FollowBreakingTag()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = entry.title,
                style = AppText.titleItem,
                color = if (isRead) cs.onSurface.copy(alpha = AppAlpha.readDim) else cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (entry.desc.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.desc,
                style = AppText.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 命中词:本页的身份标识,primary 强调
            Text(
                text = item.matchedKeywords.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = cs.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (entry.metrics.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.metrics,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = sourceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 「Breaking」标签 —— 样式对齐总览页同款(tertiary 实底小胶囊)。 */
@Composable
private fun FollowBreakingTag() {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(cs.tertiary)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(R.string.overview_breaking_tag),
            style = AppText.caption,
            fontWeight = FontWeight.Bold,
            color = cs.onTertiary
        )
    }
}

/** 页脚:缺失源标注(总览伪源映射为 Tab 名)+ 数据截至时效。 */
@Composable
internal fun FollowsFooter(ui: FollowsUi) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (ui.missingSources.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.follows_missing_sources,
                    ui.missingSources.joinToString("、") { followsSourceLabel(context, it) }
                ),
                style = AppText.caption,
                color = cs.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
        }
        if (ui.dataFetchedAt > 0) {
            Text(
                text = stringResource(
                    R.string.overview_data_until,
                    formatFetchedAt(context, ui.dataFetchedAt)
                ),
                style = AppText.caption,
                color = cs.onSurfaceVariant
            )
        }
    }
}

/**
 * 关键词管理弹层 —— 输入添加 / 已关注词删除 / 推荐词一键加。
 * 上限 [MAX_FOLLOWED_KEYWORDS]:达上限时禁用添加并提示(存储层同样兜底忽略)。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FollowsManageSheet(
    keywords: List<String>,
    suggestions: List<String>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    val atLimit = keywords.size >= MAX_FOLLOWED_KEYWORDS
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
        ) {
            // 弹层标题对齐全 App 弹层惯例 titleSection(20sp),与 14sp 小节条/12sp chips 拉开层级
            Text(
                text = stringResource(R.string.follows_manage_title),
                style = AppText.titleSection
            )
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                KeywordInputPill(
                    text = text,
                    onTextChange = { text = it },
                    onSubmit = {
                        onAdd(text)
                        text = ""
                    },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        onAdd(text)
                        text = ""
                    },
                    enabled = text.isNotBlank() && !atLimit
                ) {
                    Text(stringResource(R.string.follows_add_action))
                }
            }
            if (atLimit) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.follows_limit_toast),
                    style = AppText.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (keywords.isNotEmpty()) {
                // 弹层内容层已统一 18dp 边距:章节条去竖线、清零水平缩进,与其他内容左对齐
                SectionHeader(
                    title = stringResource(R.string.follows_following_section),
                    showAccent = false,
                    contentPadding = PaddingValues(top = 12.dp, bottom = 6.dp)
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    keywords.forEach { keyword ->
                        FollowRemoveChip(
                            text = keyword,
                            onRemove = { onRemove(keyword) }
                        )
                    }
                }
            }
            if (suggestions.isNotEmpty()) {
                SectionHeader(
                    title = stringResource(R.string.follows_suggestions_title),
                    showAccent = false,
                    contentPadding = PaddingValues(top = 12.dp, bottom = 6.dp)
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    suggestions.forEach { word ->
                        FollowSuggestChip(text = word, onClick = { onAdd(word) })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** 输入胶囊 —— 结构对齐本地搜索顶栏的搜索框(返回键/完成键即提交)。 */
@Composable
private fun KeywordInputPill(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.follows_input_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    inner()
                }
            },
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 可删除的已关注词 chip:文字 + ✕。
 *
 * 仅 ✕ 触发删除(此前整个 chip 可点删除,误触即删关注词);✕ 命中区 28dp 圆形
 * (chip 内留足命中范围,与相邻 chip 间距 8dp 不重叠 —— 删除是破坏性操作,
 * 不采用跨 chip 的触控外扩)。
 */
@Composable
private fun FollowRemoveChip(text: String, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .widthIn(max = 220.dp)
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
    ) {
        Text(
            text = text,
            style = AppText.bodySmall,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = androidx.compose.material3.ripple(),
                    onClick = onRemove
                ),
            contentAlignment = Alignment.Center
        ) {
            // 「×」删除符:chips 的功能符号,读屏语义经 semantics 保留
            val removeCdLabel = stringResource(R.string.follows_remove_cd, text)
            Text(
                text = "×",
                style = AppText.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = removeCdLabel }
            )
        }
    }
}

/** 推荐词 chip:onboarding 空态与管理弹层共用,点击即关注。 */
@Composable
private fun FollowSuggestChip(text: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = text,
        style = AppText.bodySmall,
        color = cs.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 320.dp)
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** 源 key → 本地化标题;总览伪 key 映射为 Tab 名,未知 key 原样返回(与总览页脚同法)。 */
internal fun followsSourceLabel(context: Context, source: String): String =
    if (source == FollowsRepository.OVERVIEW_KEY) context.getString(R.string.tab_overview)
    else SummaryRepository.titleOf(context, source)

/** 数据时刻格式化(「M月d日 HH:mm」,与总览/摘要卡头同规格;模式串走 date_fmt_month_day_time)。 */
private fun formatFetchedAt(context: Context, ms: Long): String =
    runCatching {
        SimpleDateFormat(context.getString(R.string.date_fmt_month_day_time), Locale.getDefault()).format(Date(ms))
    }.getOrDefault("")
