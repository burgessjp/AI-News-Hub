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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
import com.peng.ainewshub.data.prefs.MAX_FOLLOWED_KEYWORDS

/**
 * 「我的关注」域共享渲染件:关键词区([FollowsHeaderRow])、命中条目行
 * ([FollowItemRow])与管理弹层([FollowsManageSheet])。
 *
 * 原独立「关注」根 tab 已降级:命中流并入「今天」页的「我的关注」段
 * (TodayScreen 宿主 [FollowsViewModel]),管理弹层由「今天」段与热词页
 * (HotwordsScreen 管理行)两处宿主共用同一弹层。语料与过滤仍在
 * [FollowsViewModel] 内完成(当日总览 Top10 + 8 源结构化摘要,见
 * FollowsRepository)。
 */

/** 关键词区:统计行 + 管理入口 + 关键词 chips(点选单选过滤,再点恢复全部)。 */
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
                // 命中数不在此重复:收起入口行已展示「今天命中 N 条」
                text = stringResource(R.string.follows_stats_caption, ui.keywords.size),
                style = AppText.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 管理动作文字按钮(去图标);触控由 padding 撑足,读屏语义保留在文案
            Text(
                text = stringResource(R.string.follows_manage_action),
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
 * 命中条目行 —— 版式对齐「今天」页热点行([TopEntryRow] 同款):序号 + 标题
 * (breaking 以红字「头条 ·」前缀内联)+ 描述 + 文末署名行,命中词以主色嵌于
 * 署名首位(本段的身份标识)。url 为空的条目只读不可点(与分源条目一致)。
 */
@Composable
internal fun FollowItemRow(
    index: Int,
    item: FollowFeedItem,
    sourceLabel: String,
    isRead: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val entry = item.entry
    // 「头条」内联前缀(同 TopEntryRow):纯文字红色 Bold +「·」分隔,无背景
    val tagText = stringResource(R.string.overview_breaking_tag)
    val title = remember(entry.title, entry.breaking, tagText, cs.primary) {
        buildAnnotatedString {
            if (entry.breaking) {
                withStyle(SpanStyle(color = cs.primary, fontWeight = FontWeight.Bold)) {
                    append("$tagText · ")
                }
            }
            append(entry.title)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.url.isNotEmpty(), onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        // 序号列:与 RankBadge 同宽(24dp),多行条目顶对齐,行间文字列对齐
        Text(
            text = "%02d".format(index + 1),
            style = AppText.bodySmall,
            fontWeight = FontWeight.Bold,
            color = cs.onSurfaceVariant,
            modifier = Modifier
                .width(24.dp)
                .padding(top = 2.dp)
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = AppText.body,
                color = if (isRead) cs.onSurface.copy(alpha = AppAlpha.readDim) else cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.desc.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = entry.desc,
                    style = AppText.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(6.dp))
            // 文末署名行(同总览署名语言),命中词主色 SemiBold 嵌于首位
            val byline = buildAnnotatedString {
                append("—— ")
                withStyle(SpanStyle(color = cs.primary, fontWeight = FontWeight.SemiBold)) {
                    append(item.matchedKeywords.joinToString(" · "))
                }
                append(" · $sourceLabel")
                if (entry.metrics.isNotBlank()) append(" · ${entry.metrics}")
            }
            Text(
                text = byline,
                style = AppText.caption,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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

/** 推荐词 chip:管理弹层用,点击即关注。 */
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

/** 源 key → 本地化标题;总览伪 key 映射为总览词条,未知 key 原样返回(与总览页脚同法)。 */
internal fun followsSourceLabel(context: Context, source: String): String =
    if (source == FollowsRepository.OVERVIEW_KEY) context.getString(R.string.tab_overview)
    else SummaryRepository.titleOf(context, source)
