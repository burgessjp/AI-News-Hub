package com.peng.ainewshub.ui.trends

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.SectionNotice
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.components.RankRowSkeletonList
import com.peng.ainewshub.ui.follows.FollowsManageSheet
import com.peng.ainewshub.ui.theme.AppText

/**
 * 「热词」二级页 —— 近 N 天热词榜 + 词云入口(原「热词」根 tab 下段拆出;
 * 上段关注命中流已降级并入「今天」页「我的关注」段)。
 *
 * 内容复用 [TrendsContent](与「历史热词」日期页共享:caption + 榜单 + 页脚),
 * 展开区动作(「+ 关注」/「查看全部命中」)完整保留;词云经 caption 行进入。
 * 页首「我的关注」管理行([FollowsManageRow])是关键词管理的主入口,
 * 点击开管理弹层([FollowsManageSheet],与今天页关注段共用同一弹层)。
 * 入口:今天页报头「热词」、ainewshub://tab/hotwords 深链。
 *
 * 二级页语义:顶栏带返回、无下拉刷新、底部不预留浮动底栏高度;
 * [listState] 由 AiNewsHubApp 按 Page 值上提持有(pageListStates)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotwordsScreen(
    onBack: () -> Unit,
    onOpenUrl: (url: String, title: String, source: String) -> Unit,
    // 词云二级页入口(caption 行「词云 ›」)
    onOpenCloud: () -> Unit,
    // 展开区「查看全部命中」带词进本地搜索(查设备内索引的全部命中)
    onOpenLocalSearch: (String) -> Unit,
    listState: LazyListState,
    trendsVm: TrendsViewModel = viewModel()
) {
    val trendsState by trendsVm.state.collectAsStateWithLifecycle()
    // 已关注词集合(小写):热词行展开区「+ 关注」按钮的已关注态判定
    val followedKeywords by trendsVm.followedKeywords.collectAsStateWithLifecycle()
    // 已关注词(原样大小写):管理行 chips 预览与管理弹层「已关注」区
    val followedKeywordsList by trendsVm.followedKeywordsList.collectAsStateWithLifecycle()
    var showManage by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.tab_hotwords),
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val s = trendsState) {
                is TrendsState.Loading -> RankRowSkeletonList(count = 5)
                is TrendsState.NoData -> SectionNotice(
                    title = stringResource(R.string.trends_no_data_title),
                    message = stringResource(R.string.trends_no_data_subtitle),
                    actionLabel = stringResource(R.string.common_retry),
                    onAction = { trendsVm.load() }
                )
                is TrendsState.Error -> SectionNotice(
                    title = stringResource(R.string.trends_load_failed),
                    message = s.message,
                    actionLabel = stringResource(R.string.common_retry),
                    onAction = { trendsVm.load() }
                )
                is TrendsState.Success -> TrendsContent(
                    digest = s.digest,
                    listState = listState,
                    onOpenUrl = onOpenUrl,
                    onOpenCloud = onOpenCloud,
                    onFollowKeyword = { trendsVm.followKeyword(it) },
                    onSearchTerm = onOpenLocalSearch,
                    followedKeywords = followedKeywords,
                    bottomReserve = false,
                    header = {
                        FollowsManageRow(
                            keywords = followedKeywordsList,
                            onClick = { showManage = true }
                        )
                    }
                )
            }
        }
    }

    // 关键词管理弹层:与今天页关注段共用 [FollowsManageSheet]。推荐词从当前
    // digest 热词派生(与今天页 FollowsViewModel.loadSuggestions 同源同规则:
    // 展示形态前 10,trim 剔空,排除已关注 ignoreCase)。
    if (showManage) {
        val digestKeywords = (trendsState as? TrendsState.Success)?.digest?.keywords
        val suggestions = digestKeywords
            .orEmpty()
            .take(SUGGESTION_COUNT)
            .map { it.display.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { s -> followedKeywordsList.any { it.equals(s, ignoreCase = true) } }
        FollowsManageSheet(
            keywords = followedKeywordsList,
            suggestions = suggestions,
            onDismiss = { showManage = false },
            // 添加走 followKeyword:与展开区「+ 关注」同路,带胶囊反馈与上限兜底
            onAdd = { trendsVm.followKeyword(it) },
            onRemove = { trendsVm.unfollowKeyword(it) }
        )
    }
}

/**
 * 「我的关注」管理行 —— 管理弹层的收起式入口(浅底长条,同引导行视觉语言):
 * 已关注词 chips 预览(最多 [PREVIEW_CHIPS] 个 + 余量计数)+ 尾随 ›;0 词时
 * 展示引导副文案 + 前缀符。整行点击开管理弹层。
 */
@Composable
private fun FollowsManageRow(
    keywords: List<String>,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.small)
            .background(cs.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (keywords.isEmpty()) {
            // 「+」引导前缀符(去图标)
            Text(
                text = "+",
                style = AppText.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = cs.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.follows_section_guide),
                style = AppText.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        } else {
            Text(
                text = stringResource(R.string.follows_section_title),
                style = AppText.bodySmall,
                color = cs.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                keywords.take(PREVIEW_CHIPS).forEach { keyword ->
                    Text(
                        text = keyword,
                        style = AppText.bodySmall,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 96.dp)
                            .clip(CircleShape)
                            .background(cs.surfaceContainerHigh)
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
                if (keywords.size > PREVIEW_CHIPS) {
                    Text(
                        text = stringResource(R.string.follows_more_count, keywords.size - PREVIEW_CHIPS),
                        style = AppText.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurfaceVariant
        )
    }
}

/** 管理行 chips 预览上限(余量以计数展示)。 */
private const val PREVIEW_CHIPS = 3

/** 管理弹层推荐词数量(与今天页 FollowsViewModel.SUGGESTION_COUNT 对齐)。 */
private const val SUGGESTION_COUNT = 10
