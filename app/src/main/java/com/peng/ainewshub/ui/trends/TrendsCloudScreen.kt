package com.peng.ainewshub.ui.trends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peng.ainewshub.R
import com.peng.ainewshub.data.TrendCloudWord
import com.peng.ainewshub.data.TrendsCloudDigest
import com.peng.ainewshub.ui.EmptyState
import com.peng.ainewshub.ui.ErrorKind
import com.peng.ainewshub.ui.ErrorState
import com.peng.ainewshub.ui.UiState
import com.peng.ainewshub.ui.components.AppTopBar
import com.peng.ainewshub.ui.components.AppTopBarDefaults
import com.peng.ainewshub.ui.theme.AppText
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 趋势词云页 —— 近窗口期热词的全景词云(趋势 Tab caption 行进入的二级页)。
 *
 * 数据走根级独立文件 `trends_cloud.json`(流水线与热词榜同批生成的纯统计
 * 词云候选 top ~60,专用数据文件,见 [TrendsCloudViewModel]);文件暂缺
 * (尚未生成)走空态,下次批次自愈。
 *
 * 布局是真词云而非标签云平铺:Canvas + [androidx.compose.ui.text.TextMeasurer]
 * 自研阿基米德螺线碰撞排布 ——
 *  - 词条按 total 降序逐个放置,大词先落位、天然居中(权重越大越靠中心);
 *  - 字号从 [AppText] 六档(caption→titleHero)按名次加权分档派生(头部少、
 *    尾部多的金字塔分布,避免大词扎堆),不出现散落 sp 字面量,且随设置页
 *    字号档位整体缩放;
 *  - 部分词条竖排(90°):名次取模的固定模式(首 3 个大词恒横排,保证头部
 *    可读),横排放不下的超宽长词强制竖排挽救;
 *  - 碰撞检测用 AABB(竖排交换宽高)+ 词间距 padding + 画布边距,螺线走完仍
 *    无处容身的词直接丢弃(放置序为大词在前,丢弃的总是最小的词)。
 *
 * 词云词条不带代表条目(词云文件刻意轻量),词条不响应点击 —— 本页是纯
 * 全景可视化,阅读出口仍在趋势 Tab 榜单行(展开代表条目经 openUrl 进 WebView)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsCloudScreen(
    onBack: () -> Unit,
    vm: TrendsCloudViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.trends_cloud_title),
                titleFontSize = AppTopBarDefaults.secondaryTitleFontSize,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val s = state) {
                is UiState.Loading -> TrendsCloudLoading()
                is UiState.Error -> if (s.kind == ErrorKind.NoData) {
                    EmptyState(
                        title = stringResource(R.string.trends_cloud_no_data_title),
                        subtitle = stringResource(R.string.trends_cloud_no_data_subtitle),
                        icon = Icons.Outlined.HourglassEmpty,
                        actionLabel = stringResource(R.string.common_retry),
                        onAction = { vm.retry() }
                    )
                } else {
                    ErrorState(
                        message = s.message,
                        onRetry = { vm.retry() },
                        title = stringResource(R.string.trends_cloud_load_failed)
                    )
                }
                is UiState.Success -> TrendsCloudContent(digest = s.data)
            }
        }
    }
}

/** 加载中:居中转圈 + 说明(词云页无列表骨架,不借用排名行 skeleton)。 */
@Composable
private fun TrendsCloudLoading() {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            color = cs.primary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.trends_loading),
            style = AppText.bodySmall,
            color = cs.onSurfaceVariant
        )
    }
}

/**
 * 词云内容:顶部时效 caption(与趋势 Tab 同词条)+ 词云画布 + 生成时间页脚。
 */
@Composable
private fun TrendsCloudContent(digest: TrendsCloudDigest) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(
                R.string.trends_window_caption,
                digest.windowDays,
                formatDay(context, digest.days.lastOrNull().orEmpty())
            ),
            style = AppText.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )
        WordCloudCanvas(
            words = digest.words,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        )
        if (digest.generatedAt > 0) {
            Text(
                text = stringResource(R.string.trends_generated_at, formatClock(digest.generatedAt)),
                style = AppText.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }
    }
}

/**
 * 词云画布:BoxWithConstraints 拿到可用像素尺寸后一次性算好全部落位
 * (remember 缓存,数据 / 尺寸 / 字号档变化才重排),Canvas 只负责描画。
 */
@Composable
private fun WordCloudCanvas(
    words: List<TrendCloudWord>,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        // 字号档:AppText 六档按大到小(caption→titleHero 反向),名次加权分档
        val tierStyles = listOf(
            AppText.titleHero, AppText.titleSection, AppText.titleItem,
            AppText.titleCompact, AppText.bodySmall, AppText.caption
        )
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val placed = remember(words, widthPx, heightPx, tierStyles, density) {
            with(density) {
                layoutWordCloud(
                    measurer = measurer,
                    words = words,
                    canvasWidth = widthPx,
                    canvasHeight = heightPx,
                    tierStyles = tierStyles,
                    wordGapPx = 5.dp.toPx(),
                    edgeMarginPx = 4.dp.toPx(),
                    spiralPitchPx = 2.5.dp.toPx()
                )
            }
        }
        // 颜色按档位映射(只走 colorScheme):最大词 primary 强调,次档中性主文,
        // 中档 tertiary/secondary 轮换,最小两档弱化 —— 尺寸与色彩同层级语义
        val cs = MaterialTheme.colorScheme
        val tierColors = listOf(
            cs.primary, cs.onSurface, cs.tertiary, cs.secondary,
            cs.onSurfaceVariant, cs.onSurfaceVariant
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            placed.forEach { p ->
                // 落位以中心点记录,描画换算回左上角;竖排绕中心旋转 90°
                val topLeft = Offset(
                    p.center.x - p.layout.size.width / 2f,
                    p.center.y - p.layout.size.height / 2f
                )
                if (p.vertical) {
                    rotate(degrees = 90f, pivot = p.center) {
                        drawText(p.layout, color = tierColors[p.tier], topLeft = topLeft)
                    }
                } else {
                    drawText(p.layout, color = tierColors[p.tier], topLeft = topLeft)
                }
            }
        }
    }
}

/** 词云词条落位结果:测量布局 + 中心点 + 是否竖排 + 字号档索引(档位决定颜色)。 */
private data class PlacedCloudWord(
    val layout: TextLayoutResult,
    val center: Offset,
    val vertical: Boolean,
    val tier: Int
)

/**
 * 字号档分界(名次占比的累计边界):头部少、尾部多的金字塔分布——60 词时约
 * 3/6/9/15/15/12,最大档只留给头部几个词,避免大词扎堆撑爆画布。
 */
private val TIER_BOUNDS = floatArrayOf(0.06f, 0.16f, 0.32f, 0.55f, 0.80f, 1f)

private fun tierIndexFor(rank: Int, count: Int): Int {
    val p = rank.toFloat() / count
    val idx = TIER_BOUNDS.indexOfFirst { p < it }
    return if (idx < 0) TIER_BOUNDS.lastIndex else idx
}

/**
 * 阿基米德螺线词云排布(纯函数,便于 remember 缓存)。
 *
 * 每个词沿螺线 r = pitch·θ 逐点试探(θ 步进 0.12rad):候选位置的 AABB
 * (含词间距 padding;竖排交换宽高)不与已放盒子重叠、且在画布边距内即落位,
 * 否则继续外扩;螺线超出到角的最远距离仍无容身处则丢弃该词。首词从 θ=0
 * (正中心)起步,天然居中;放置顺序按 total 降序,大词先占中心、小词填外围,
 * 丢弃风险集中在最小的词上。
 */
private fun layoutWordCloud(
    measurer: TextMeasurer,
    words: List<TrendCloudWord>,
    canvasWidth: Float,
    canvasHeight: Float,
    tierStyles: List<TextStyle>,
    wordGapPx: Float,
    edgeMarginPx: Float,
    spiralPitchPx: Float
): List<PlacedCloudWord> {
    if (words.isEmpty() || canvasWidth <= 0f || canvasHeight <= 0f) return emptyList()
    val usableW = canvasWidth - edgeMarginPx * 2f
    val usableH = canvasHeight - edgeMarginPx * 2f
    val cx = canvasWidth / 2f
    val cy = canvasHeight / 2f
    // 螺线半径上限:中心到四角(扣边距)的最远距离,保证四角区域也可被试探到
    val maxRadius = hypot(cx - edgeMarginPx, cy - edgeMarginPx)
    val ranked = words.sortedByDescending { it.total }
    val placed = mutableListOf<PlacedCloudWord>()
    val boxes = mutableListOf<Rect>()
    ranked.forEachIndexed { rank, word ->
        val tier = tierIndexFor(rank, ranked.size)
        val layout = measurer.measure(word.display, tierStyles[tier])
        val w = layout.size.width.toFloat()
        val h = layout.size.height.toFloat()
        if (w > usableW && h > usableH) return@forEachIndexed
        // 竖排模式:首 3 个大词恒横排(头部可读),此后每 3 个词竖排 1 个;
        // 横排超宽的长词强制竖排挽救(竖排高度通常放得下)
        val vertical = (rank >= 3 && (rank - 3) % 3 == 0) || (w > usableW && h <= usableH)
        val boxW = (if (vertical) h else w) + wordGapPx * 2f
        val boxH = (if (vertical) w else h) + wordGapPx * 2f
        if (boxW > usableW || boxH > usableH) return@forEachIndexed
        var theta = 0f
        while (true) {
            val r = spiralPitchPx * theta
            if (r > maxRadius) return@forEachIndexed
            val x = cx + r * cos(theta)
            val y = cy + r * sin(theta)
            val box = Rect(
                left = x - boxW / 2f,
                top = y - boxH / 2f,
                right = x + boxW / 2f,
                bottom = y + boxH / 2f
            )
            val collides = box.left < edgeMarginPx || box.top < edgeMarginPx ||
                box.right > canvasWidth - edgeMarginPx || box.bottom > canvasHeight - edgeMarginPx ||
                boxes.any { it.overlaps(box) }
            if (!collides) {
                placed += PlacedCloudWord(layout = layout, center = Offset(x, y), vertical = vertical, tier = tier)
                boxes += box
                break
            }
            theta += 0.12f
        }
    }
    return placed
}
