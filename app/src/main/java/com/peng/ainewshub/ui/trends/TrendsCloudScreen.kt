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
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material.icons.outlined.Cyclone
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.peng.ainewshub.ui.theme.AppAlpha
import com.peng.ainewshub.ui.theme.AppText
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 趋势词云页 —— 近窗口期热词的全景可视化(趋势 Tab caption 行进入的二级页)。
 *
 * 数据走根级独立文件 `trends_cloud.json`(流水线与热词榜同批生成的纯统计
 * 词云候选 top ~60,专用数据文件,见 [TrendsCloudViewModel]);文件暂缺
 * (尚未生成)走空态,下次批次自愈。
 *
 * 布局支持两种形态(顶栏右侧单图标按钮循环切换,瞬态偏好,不占正文空间):
 * Canvas + [androidx.compose.ui.text.TextMeasurer] 自研排布 ——
 *  - 螺旋(默认):阿基米德螺线碰撞词云([layoutSpiralCloud]),大词先落位、
 *    天然居中,部分词竖排;
 *  - 圆形气泡([layoutBubbleCloud]):词入圆形气泡(半径 ∝ √权重且不小于文字
 *    所需),贪心正切链把气泡堆成紧致圆簇 —— 零重叠、零丢词、天然圆形轮廓;
 *  - 字号统一从 [AppText] 六档(caption→titleHero)按名次加权分档派生(头部少、
 *    尾部多的金字塔分布),不出现散落 sp 字面量,且随设置页字号档位整体缩放。
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
    // 布局模式为瞬态偏好(rememberSaveable,旋转/进程恢复不丢);上提到本层
    // 以便顶栏图标按钮直接切换,图标随当前模式变化、点击循环到下一形态
    var mode by rememberSaveable { mutableStateOf(CloudLayoutMode.SPIRAL) }

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
                },
                actions = {
                    if (state is UiState.Success) {
                        IconButton(onClick = { mode = mode.next() }) {
                            Icon(
                                imageVector = if (mode == CloudLayoutMode.SPIRAL) {
                                    Icons.Outlined.Cyclone
                                } else {
                                    Icons.Outlined.BubbleChart
                                },
                                contentDescription = stringResource(R.string.trends_cloud_switch_layout)
                            )
                        }
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
                is UiState.Success -> TrendsCloudContent(digest = s.data, mode = mode)
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

/** 词云内容:顶部时效 caption(与趋势 Tab 同词条)+ 词云画布 + 生成时间页脚。 */
@Composable
private fun TrendsCloudContent(digest: TrendsCloudDigest, mode: CloudLayoutMode) {
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
            mode = mode,
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
 * (remember 缓存,数据 / 布局模式 / 尺寸 / 字号档变化才重排),Canvas 只负责描画。
 * 两种布局模式共用字号分档与配色映射,仅排布引擎与绘制路径不同。
 */
@Composable
private fun WordCloudCanvas(
    words: List<TrendCloudWord>,
    mode: CloudLayoutMode,
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
        // 颜色走词云专用调色板(CloudWordColors.kt,hex 集中例外,不走 colorScheme):
        // 字号档定色相(蓝/橙/绿/紫/玫红/青灰),落位序号定明暗变体 —— 尺寸与色彩同层级语义
        val tierColors = cloudTierColors()
        if (mode == CloudLayoutMode.SPIRAL) {
            val placed = remember(words, widthPx, heightPx, tierStyles, density) {
                with(density) {
                    layoutSpiralCloud(
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
            Canvas(modifier = Modifier.fillMaxSize()) {
                placed.forEach { word ->
                    val color = tierColors[word.tier][word.shade]
                    // 落位以词心记录,描画换算回左上角;竖排词绕词心转正
                    val topLeft = Offset(
                        word.center.x - word.layout.size.width / 2f,
                        word.center.y - word.layout.size.height / 2f
                    )
                    if (word.rotationDeg != 0f) {
                        rotate(degrees = word.rotationDeg, pivot = word.center) {
                            drawText(word.layout, color = color, topLeft = topLeft)
                        }
                    } else {
                        drawText(word.layout, color = color, topLeft = topLeft)
                    }
                }
            }
        } else {
            val bubbles = remember(words, widthPx, heightPx, tierStyles, density) {
                with(density) {
                    layoutBubbleCloud(
                        measurer = measurer,
                        words = words,
                        canvasWidth = widthPx,
                        canvasHeight = heightPx,
                        tierStyles = tierStyles,
                        bubbleGapPx = 3.dp.toPx(),
                        edgeMarginPx = 4.dp.toPx()
                    )
                }
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                bubbles.forEach { b ->
                    val color = tierColors[b.tier][b.shade]
                    // 气泡底走分档色弱化档(徽章/药丸同款透明度),文字全透明压底,
                    // 相邻气泡靠档位色差区分,不加描边保持干净
                    drawCircle(
                        color = color,
                        alpha = AppAlpha.badgeOverlay,
                        radius = b.radius,
                        center = b.center
                    )
                    drawText(
                        b.layout,
                        color = color,
                        topLeft = Offset(
                            b.center.x - b.layout.size.width / 2f,
                            b.center.y - b.layout.size.height / 2f
                        )
                    )
                }
            }
        }
    }
}

/**
 * 词云词条落位结果(螺旋模式):整词一次测量布局 + 词心位置 +
 * 旋转角(度;0 = 正立)+ 字号档与明暗变体序号(两者共同决定调色板取色)。
 */
private data class PlacedCloudWord(
    val layout: TextLayoutResult,
    val center: Offset,
    val rotationDeg: Float,
    val tier: Int,
    val shade: Int
)

/** 气泡落位结果(圆形气泡模式):文字布局(字号可能已随整体缩放)+ 圆心 + 半径 + 字号档与明暗变体序号。 */
private data class PlacedBubbleWord(
    val layout: TextLayoutResult,
    val center: Offset,
    val radius: Float,
    val tier: Int,
    val shade: Int
)

/** 词云布局模式:螺旋散布(默认,词填满矩形画布)与圆形气泡(词入泡、泡堆成圆簇);新增形态在此扩展一枚即可接入顶栏切换。 */
private enum class CloudLayoutMode {
    SPIRAL, CIRCLE;

    /** 循环切换到下一形态(顶栏单图标按钮,两态互切)。 */
    fun next(): CloudLayoutMode = entries[(ordinal + 1) % entries.size]
}

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
 * 阿基米德螺线词云排布(螺旋模式)。
 *
 * 每个词沿螺线 r = pitch·θ 逐点试探(θ 步进 0.12rad):候选位置的 AABB
 * (含词间距 padding;竖排交换宽高)不与已放盒子重叠、且在画布边距内即落位,
 * 否则继续外扩;螺线超出到角的最远距离仍无容身处则丢弃该词。首词从 θ=0
 * (正中心)起步,天然居中;放置顺序按 total 降序,大词先占中心、小词填外围,
 * 丢弃风险集中在最小的词上。
 */
private fun layoutSpiralCloud(
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
    val tierPlaced = IntArray(tierStyles.size)  // 各档已放词数,取 % 2 定明暗变体交替
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
                placed += PlacedCloudWord(
                    layout = layout,
                    center = Offset(x, y),
                    rotationDeg = if (vertical) 90f else 0f,
                    tier = tier,
                    shade = tierPlaced[tier] % 2
                )
                tierPlaced[tier]++
                boxes += box
                break
            }
            theta += 0.12f
        }
    }
    return placed
}

/**
 * 圆形气泡排布(贪心正切链打包,~60 个气泡的规模足够,d3.pack 同款思路的简化版)。
 *
 * 词按分值降序:气泡半径 = max(权重半径, 文字所需半径) —— 权重半径按
 * r ∝ √total(面积 ∝ 权重)在短边 10%~34% 间映射;文字所需半径取文字对角一半
 * 加内留白,长词低分也装得下,**零丢词**。每个新气泡放在「与已放任意两气泡
 * 同时相切」的候选点中离圆心最近者(不与任何已放气泡重叠),得到紧致的近圆
 * 形簇;簇超出画布时整体等比缩放(字号同步缩小重测,文字仍装得进气泡)。
 * 相切候选全灭的极端情况走螺线兜底,理论上不可达的失败才丢词。
 */
private fun layoutBubbleCloud(
    measurer: TextMeasurer,
    words: List<TrendCloudWord>,
    canvasWidth: Float,
    canvasHeight: Float,
    tierStyles: List<TextStyle>,
    bubbleGapPx: Float,
    edgeMarginPx: Float
): List<PlacedBubbleWord> {
    if (words.isEmpty() || canvasWidth <= 0f || canvasHeight <= 0f) return emptyList()
    val availR = minOf(canvasWidth, canvasHeight) / 2f - edgeMarginPx
    if (availR <= 0f) return emptyList()
    val ranked = words.sortedByDescending { it.total }

    /** 打包中的气泡:圆心随打包推进写入。 */
    class Bubble(
        val display: String,
        val tier: Int,
        val layout: TextLayoutResult,
        val r: Float
    ) {
        var x = 0f
        var y = 0f
        var shade = 0
    }

    // 权重半径:面积 ∝ 权重 → r ∝ √total,首末半径锚定可用半径的 34% / 10%
    val maxTotal = ranked.first().total.toFloat()
    val minTotal = ranked.last().total.toFloat()
    val span = maxTotal - minTotal
    fun weightRadius(total: Int): Float {
        val t = if (span <= 0f) 1f else ((total.toFloat() - minTotal) / span).coerceIn(0f, 1f)
        return availR * (0.10f + 0.24f * sqrt(t))
    }
    val bubbles = ranked.mapIndexed { rank, w ->
        val tier = tierIndexFor(rank, ranked.size)
        val layout = measurer.measure(w.display, tierStyles[tier])
        // 文字所需半径:对角一半 + 12% 内留白,保证文字完整落在气泡内
        val textR = hypot(layout.size.width.toFloat(), layout.size.height.toFloat()) * 0.56f
        Bubble(w.display, tier, layout, maxOf(weightRadius(w.total), textR))
    }

    val placedCircles = mutableListOf<Bubble>()
    val tierPlaced = IntArray(tierStyles.size)  // 各档已放词数,取 % 2 定明暗变体交替
    /** 候选点是否与已放气泡全部无重叠(留 0.5px 浮点余量)。 */
    fun fits(px: Float, py: Float, r: Float): Boolean =
        placedCircles.none { hypot(px - it.x, py - it.y) + 0.5f < it.r + r + bubbleGapPx }

    bubbles.forEach { b ->
        // 候选点:与已放任意两气泡同时相切的圆交点,取离圆心最近的有效者
        var bestX = Float.NaN
        var bestY = Float.NaN
        var bestD = Float.MAX_VALUE
        for (i in placedCircles.indices) {
            for (j in i + 1 until placedCircles.size) {
                val a = placedCircles[i]
                val c = placedCircles[j]
                val ra = a.r + b.r + bubbleGapPx
                val rc = c.r + b.r + bubbleGapPx
                val dx = c.x - a.x
                val dy = c.y - a.y
                val d = hypot(dx, dy)
                if (d >= ra + rc || d <= abs(ra - rc) || d == 0f) continue
                val along = (ra * ra - rc * rc + d * d) / (2f * d)
                val h2 = ra * ra - along * along
                if (h2 <= 0f) continue
                val h = sqrt(h2)
                val mx = a.x + along * dx / d
                val my = a.y + along * dy / d
                val ox = -dy / d * h
                val oy = dx / d * h
                for (sign in intArrayOf(1, -1)) {
                    val px = mx + ox * sign
                    val py = my + oy * sign
                    val dc = hypot(px, py)
                    if (dc < bestD && fits(px, py, b.r)) {
                        bestD = dc
                        bestX = px
                        bestY = py
                    }
                }
            }
        }
        when {
            // 首词(最大)居中
            placedCircles.isEmpty() -> {
                bestX = 0f
                bestY = 0f
            }
            // 只有 1 个已放气泡时无交点对:直接放其右侧相切(距圆心最近等价)
            placedCircles.size == 1 -> {
                val a = placedCircles.first()
                bestX = a.x + a.r + b.r + bubbleGapPx
                bestY = a.y
            }
            // 相切候选全灭(极端):沿螺线外扩找最近空位兜底
            bestX.isNaN() -> {
                var theta = 0f
                while (theta < 628f) {
                    val rr = bubbleGapPx * 2f * theta
                    val px = rr * cos(theta)
                    val py = rr * sin(theta)
                    if (fits(px, py, b.r)) {
                        bestX = px
                        bestY = py
                        break
                    }
                    theta += 0.2f
                }
            }
        }
        if (bestX.isNaN()) return@forEach  // 兜底失败才丢词(理论上不可达)
        b.shade = tierPlaced[b.tier] % 2
        tierPlaced[b.tier]++
        b.x = bestX
        b.y = bestY
        placedCircles += b
    }
    if (placedCircles.isEmpty()) return emptyList()
    // 整体等比缩放进画布(打包是齐次的,缩放不产生重叠);字号同步缩小重测,
    // 文字尺寸随字号近似线性缩放,仍装得进气泡
    val clusterR = placedCircles.maxOf { hypot(it.x, it.y) + it.r }
    val s = minOf(1f, availR / clusterR)
    val cx = canvasWidth / 2f
    val cy = canvasHeight / 2f
    return placedCircles.map { b ->
        val layout = if (s >= 0.999f) {
            b.layout
        } else {
            measurer.measure(b.display, tierStyles[b.tier].copy(fontSize = tierStyles[b.tier].fontSize * s))
        }
        PlacedBubbleWord(
            layout = layout,
            center = Offset(cx + b.x * s, cy + b.y * s),
            radius = b.r * s,
            tier = b.tier,
            shade = b.shade
        )
    }
}
