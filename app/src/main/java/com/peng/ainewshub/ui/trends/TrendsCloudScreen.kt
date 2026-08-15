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
import com.peng.ainewshub.ui.components.SegmentedOptionRow
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
 * 布局支持多种形态(页面内分段按钮切换,瞬态偏好):Canvas +
 * [androidx.compose.ui.text.TextMeasurer] 自研排布 ——
 *  - 螺旋(默认):阿基米德螺线碰撞检测,大词先落位、天然居中,部分词竖排;
 *  - 圆环:同心圆环排布,大词内环、小词外环,逐字沿弧弯排(真实弧度,下半环
 *    自动翻转保持可读);
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
 * 词云内容:顶部时效 caption(与趋势 Tab 同词条)+ 布局切换器 + 词云画布 +
 * 生成时间页脚。布局模式为瞬态偏好(rememberSaveable,旋转/进程恢复不丢)。
 */
@Composable
private fun TrendsCloudContent(digest: TrendsCloudDigest) {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(CloudLayoutMode.SPIRAL) }
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
        // 布局切换(螺旋/圆环):MD3 单选分段按钮,选项文案顺序与枚举一致
        SegmentedOptionRow(
            options = listOf(
                stringResource(R.string.trends_cloud_mode_spiral),
                stringResource(R.string.trends_cloud_mode_circle)
            ),
            selectedIndex = mode.ordinal,
            onSelect = { mode = CloudLayoutMode.entries[it] },
            modifier = Modifier.padding(horizontal = 18.dp)
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
        val placed = remember(words, mode, widthPx, heightPx, tierStyles, density) {
            with(density) {
                layoutWordCloud(
                    mode = mode,
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
            placed.forEach { word ->
                val color = tierColors[word.tier]
                word.glyphs.forEach { g ->
                    // 落位以字心记录,描画换算回左上角;非零旋转角绕字心转正
                    val topLeft = Offset(
                        g.center.x - g.layout.size.width / 2f,
                        g.center.y - g.layout.size.height / 2f
                    )
                    if (g.rotationDeg != 0f) {
                        rotate(degrees = g.rotationDeg, pivot = g.center) {
                            drawText(g.layout, color = color, topLeft = topLeft)
                        }
                    } else {
                        drawText(g.layout, color = color, topLeft = topLeft)
                    }
                }
            }
        }
    }
}

/** 单个字形的落位:测量布局 + 中心点 + 旋转角(度;0 = 正立)。 */
private data class CloudGlyph(
    val layout: TextLayoutResult,
    val center: Offset,
    val rotationDeg: Float
)

/**
 * 词云词条落位结果:一个词的全部字形 + 字号档(档位决定颜色)。
 * 螺旋模式整词一个字形;圆环模式逐字沿弧弯排,每字一个字形。
 */
private data class PlacedCloudWord(
    val glyphs: List<CloudGlyph>,
    val tier: Int
)

/** 词云布局模式:螺旋散布(默认)与圆环弧排;新增形态在此扩展一枚即可接入切换器。 */
private enum class CloudLayoutMode { SPIRAL, CIRCLE }

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
 * 词云排布统一入口(按 [CloudLayoutMode] 分发,纯函数便于 remember 缓存):
 *  - 螺旋([layoutSpiralCloud]):阿基米德螺线 + AABB 碰撞,大词先落位居中;
 *  - 圆环([layoutRingCloud]):同心圆环逐字沿弧弯排,大词内环小词外环,几何无重叠。
 */
private fun layoutWordCloud(
    mode: CloudLayoutMode,
    measurer: TextMeasurer,
    words: List<TrendCloudWord>,
    canvasWidth: Float,
    canvasHeight: Float,
    tierStyles: List<TextStyle>,
    wordGapPx: Float,
    edgeMarginPx: Float,
    spiralPitchPx: Float
): List<PlacedCloudWord> = when (mode) {
    CloudLayoutMode.SPIRAL ->
        layoutSpiralCloud(measurer, words, canvasWidth, canvasHeight, tierStyles, wordGapPx, edgeMarginPx, spiralPitchPx)
    CloudLayoutMode.CIRCLE ->
        layoutRingCloud(measurer, words, canvasWidth, canvasHeight, tierStyles, wordGapPx, edgeMarginPx)
}

/**
 * 阿基米德螺线词云排布。
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
                    glyphs = listOf(
                        CloudGlyph(
                            layout = layout,
                            center = Offset(x, y),
                            rotationDeg = if (vertical) 90f else 0f
                        )
                    ),
                    tier = tier
                )
                boxes += box
                break
            }
            theta += 0.12f
        }
    }
    return placed
}

/** 弧度转度(绕过 kotlin.math 的 Double 版本,保持 Float 运算)。 */
private const val RAD_TO_DEG = 57.29578f

/** 圆环模式单字弯排的最大弧度(≈172°):词心居中时两端不越过水平线,不出现倒立字。 */
private const val RING_ARC_CAP = 3.0f

/** 圆环模式的词用料:逐字测量布局 + 弧长(Σ字宽 + 字距)+ 最大字高。 */
private class RingWord(
    val tier: Int,
    val chars: List<TextLayoutResult>,
    val arcPx: Float,
    val maxCharH: Float
)

/**
 * 圆环词云排布:词条按分值序填入同心圆环 —— 大词在内环、小词外环,每个词
 * **拆成单字沿环弧逐字弯排**(每字按自身在弧上的位置独立旋转,带真实弧度,
 * 而非整词切线平移),上半环顺弧上凸、下半环翻转 180° 顺弧下垂,字始终不倒立。
 *
 * 无碰撞检测:环内按「词弧长 + 间隙」占角累计装环(留 8% 呼吸),剩余角均摊
 * 到词间隙;环距按上一环最大字高外移,几何上环内环间天然不重叠。单词弯排弧
 * 度超 [RING_ARC_CAP] 时:空环先扩环半径再装,非空环则封环把词留给更外环;
 * 扩到可用半径仍放不下则丢弃。起始角随环序交错(TAU/6 步进)避免各环词径向
 * 对齐;外环超出可用半径后剩余词丢弃(与螺线一致,丢弃集中在最小的词)。
 */
private fun layoutRingCloud(
    measurer: TextMeasurer,
    words: List<TrendCloudWord>,
    canvasWidth: Float,
    canvasHeight: Float,
    tierStyles: List<TextStyle>,
    wordGapPx: Float,
    edgeMarginPx: Float
): List<PlacedCloudWord> {
    if (words.isEmpty() || canvasWidth <= 0f || canvasHeight <= 0f) return emptyList()
    val cx = canvasWidth / 2f
    val cy = canvasHeight / 2f
    // 圆环必须整体落进画布:外缘 = 半径 + 字高一半,不超出短边的一半(扣边距)
    val maxR = minOf(cx, cy) - edgeMarginPx
    if (maxR <= 0f) return emptyList()
    val tau = (2.0 * Math.PI).toFloat()
    val track = wordGapPx * 0.4f  // 弯排字距(小于词间隙,弧上观感更透气)
    val ranked = words.sortedByDescending { it.total }
    // 预量逐字布局:按 code point 拆字(防代理对截断),单字测量丢 kerning 可接受
    val items = ranked.mapIndexed { rank, w ->
        val tier = tierIndexFor(rank, ranked.size)
        val style = tierStyles[tier]
        val chars = mutableListOf<TextLayoutResult>()
        var idx = 0
        while (idx < w.display.length) {
            val cp = w.display.codePointAt(idx)
            chars += measurer.measure(String(Character.toChars(cp)), style)
            idx += Character.charCount(cp)
        }
        val arc = chars.sumOf { it.size.width.toDouble() }.toFloat() +
            track * (chars.size - 1).coerceAtLeast(0)
        RingWord(tier, chars, arc, chars.maxOf { it.size.height.toFloat() })
    }
    val result = mutableListOf<PlacedCloudWord>()
    var ringR = maxR * 0.30f
    var ringIndex = 0
    var i = 0
    while (i < items.size) {
        // 收集本环词条:占角累计到 92% 圆周封环;弯排弧度超限时空环扩半径、
        // 非空环封环(词留给更外环)
        val ring = mutableListOf<RingWord>()
        var usedAngle = 0f
        var ringMaxH = 0f
        while (i < items.size) {
            val cand = items[i]
            val padded = cand.arcPx + wordGapPx * 2f
            if (padded / ringR > RING_ARC_CAP) {
                if (ring.isEmpty()) {
                    val needR = padded / RING_ARC_CAP
                    if (needR + cand.maxCharH > maxR) {
                        i++  // 扩到头仍放不下,丢弃(多为无实体信息量的超长词)
                        continue
                    }
                    ringR = needR
                } else break
            }
            val angW = padded / ringR
            if (ring.isNotEmpty() && usedAngle + angW > tau * 0.92f) break
            ring += cand
            usedAngle += angW
            ringMaxH = maxOf(ringMaxH, cand.maxCharH)
            i++
        }
        if (ring.isEmpty()) break
        // 环内均匀分布:剩余角均摊为额外间隙,词心落在自身占角中央;逐字沿弧
        // 弯排 —— 上半环字心角递增(θ+90° 正立),下半环递减(θ-90° 翻转正立)
        val extra = ((tau - usedAngle).coerceAtLeast(0f)) / ring.size
        var theta = ringIndex * (tau / 6f)
        ring.forEach { cand ->
            val padded = cand.arcPx + wordGapPx * 2f
            val angW = padded / ringR
            val centerTheta = theta + angW / 2f
            val topSide = sin(centerTheta) < 0f
            val charsArcAng = cand.arcPx / ringR
            var a = if (topSide) centerTheta - charsArcAng / 2f else centerTheta + charsArcAng / 2f
            val glyphs = cand.chars.map { c ->
                val halfAng = (c.size.width / 2f) / ringR
                if (topSide) a += halfAng else a -= halfAng
                val g = CloudGlyph(
                    layout = c,
                    center = Offset(cx + ringR * cos(a), cy + ringR * sin(a)),
                    rotationDeg = (if (topSide) a + tau / 4f else a - tau / 4f) * RAD_TO_DEG
                )
                if (topSide) a += halfAng + track / ringR else a -= halfAng + track / ringR
                g
            }
            result += PlacedCloudWord(glyphs = glyphs, tier = cand.tier)
            theta += angW + extra
        }
        // 下一环:外移一整个本环最大字高 + 双倍间隙;外缘放不下则丢弃剩余词
        ringR += ringMaxH + wordGapPx * 2f
        val nextH = items.getOrNull(i)?.maxCharH?.div(2f) ?: 0f
        if (i < items.size && ringR + nextH > maxR) break
        ringIndex++
    }
    return result
}
