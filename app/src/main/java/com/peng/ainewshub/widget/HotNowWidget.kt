package com.peng.ainewshub.widget

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.peng.ainewshub.MainActivity
import com.peng.ainewshub.R
import com.peng.ainewshub.data.SummaryRepository
import com.peng.ainewshub.ui.theme.DarkBackground
import com.peng.ainewshub.ui.theme.DarkErrorContainer
import com.peng.ainewshub.ui.theme.DarkOnBackground
import com.peng.ainewshub.ui.theme.DarkOnErrorContainer
import com.peng.ainewshub.ui.theme.DarkOnPrimaryContainer
import com.peng.ainewshub.ui.theme.DarkOnSurfaceVariant
import com.peng.ainewshub.ui.theme.DarkPrimary
import com.peng.ainewshub.ui.theme.DarkPrimaryContainer
import com.peng.ainewshub.ui.theme.LightBackground
import com.peng.ainewshub.ui.theme.LightErrorContainer
import com.peng.ainewshub.ui.theme.LightOnBackground
import com.peng.ainewshub.ui.theme.LightOnErrorContainer
import com.peng.ainewshub.ui.theme.LightOnPrimary
import com.peng.ainewshub.ui.theme.LightOnSurfaceVariant
import com.peng.ainewshub.ui.theme.LightPrimary
import com.peng.ainewshub.ui.theme.LightPrimaryContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「今日热点」小组件 —— 展示流水线预生成的今日总览 Top10(latest_overview)。
 *
 * 刷新三路:
 *  1. 系统周期(res/xml/hot_now_widget_info.xml 的 updatePeriodMillis=30min)→ provideGlance 重跑;
 *  2. 头部刷新按钮 → [RefreshHotNowAction](force);
 *  3. App 内总览刷新成功 → [HotNowWidgetUpdater.refreshFromApp] 联动。
 *
 * provideGlance 补网策略:无缓存,或距上次成功超过 [STALE_MS] → 挂起拉一次再渲染;
 * 其余情况直接渲染本地缓存(秒出)。失败保留旧数据(见 HotNowWidgetUpdater 注释)。
 *
 * 设计:配色直接取 App 设计令牌(ui/theme/Color.kt 顶层常量)组 day/night ColorProvider,
 * 做成 App 的「迷你版」(品牌观感一致),不用 GlanceTheme 的壁纸动态色;条目极简 ——
 * 排名 + 标题,breaking 胶囊与标题「内联」(测量断行,见 [splitAroundCapsule]);
 * 行间靠留白分隔(无分隔线)。
 * 字体:Glance 1.1.1 不支持 res/font 自定义字体,层级靠字号 + 字重(Normal/Medium/Bold)建立。
 */
class HotNowWidget : GlanceAppWidget() {

    /** 数据过期阈值:超过则 provideGlance 时补一次网络(与系统 30min 周期配合,每次周期刷新补一次)。 */
    private val STALE_MS = 25L * 60 * 1000

    /**
     * Exact:内联断行([splitAroundCapsule])需要精确可用宽度。
     * 代价是每次尺寸变化都重组一次 —— 列表轻量,且缩放是低频用户操作,可接受。
     */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        var state = HotNowWidgetStore.read(context)
        val now = System.currentTimeMillis()
        if (!state.hasData || now - state.lastSuccessAt > STALE_MS) {
            // triggerUpdate=false:本轮 provideGlance 自己会继续 provideContent,无需再触发一轮
            HotNowWidgetUpdater.refresh(context, force = false, triggerUpdate = false)
            state = HotNowWidgetStore.read(context)
        }
        provideContent {
            GlanceTheme {
                Content(context, state)
            }
        }
    }

    @Composable
    private fun Content(context: Context, state: HotNowWidgetState) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetColors.background)
                .cornerRadius(16.dp)
        ) {
            Header(context, state)
            if (state.hasData) {
                ItemList(context, state.items)
            } else {
                EmptyBody()
            }
        }
    }

    /** 头部:标题行(「AI 热点」+ 品牌蓝圆形刷新按钮)+ 「数据截至」行。 */
    @Composable
    private fun Header(context: Context, state: HotNowWidgetState) {
        Row(
            modifier = GlanceModifier.fillMaxWidth()
                .padding(start = 14.dp, top = 10.dp, end = 10.dp, bottom = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AI 热点",
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = WidgetColors.onBackground
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight()
                    .clickable(actionStartActivity(openAppIntent(context)))
            )
            // 刷新:品牌蓝圆底按钮(比裸图标更像可点控件)
            Box(
                modifier = GlanceModifier.size(30.dp)
                    .background(WidgetColors.refreshBg)
                    .cornerRadius(15.dp)
                    .clickable(actionRunCallback<RefreshHotNowAction>()),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_refresh),
                    contentDescription = "刷新",
                    colorFilter = ColorFilter.tint(WidgetColors.refreshIcon),
                    modifier = GlanceModifier.size(16.dp)
                )
            }
        }
        if (state.dataFetchedAt > 0) {
            Text(
                text = formatDataTime(state.dataFetchedAt),
                style = TextStyle(fontSize = 11.sp, color = WidgetColors.meta),
                maxLines = 1,
                modifier = GlanceModifier
                    .padding(start = 14.dp, top = 0.dp, end = 14.dp, bottom = 8.dp)
                    .clickable(actionStartActivity(openAppIntent(context)))
            )
        } else {
            Spacer(GlanceModifier.height(8.dp))
        }
    }

    @Composable
    private fun ItemList(context: Context, items: List<HotNowWidgetState.Item>) {
        // 无分隔线:条目仅 2~3 个元素,靠行距分隔更干净(这个密度下分隔线是噪音)
        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
            items(items.size) { index ->
                ItemRow(context, rank = index + 1, item = items[index])
            }
        }
    }

    /**
     * 单条:排名 + 标题(≤2 行,绝对主角)。
     * breaking 条目:胶囊与标题内联 —— 实测标题在胶囊旁能放下的前缀作第 1 行,
     * 余量全宽作第 2 行,视觉上是文本绕胶囊自然换行(而非整体右缩的 tag 前缀)。
     * 来源(源名/品牌色)与互动指标均不上小组件 —— 桌面场景只留「什么新闻、有多急」。
     */
    @Composable
    private fun ItemRow(context: Context, rank: Int, item: HotNowWidgetState.Item) {
        val top3 = rank <= 3
        val titleStyle = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = WidgetColors.onBackground
        )
        Row(
            modifier = GlanceModifier.fillMaxWidth()
                .clickable(actionStartActivity(openItemIntent(context, item)))
                .padding(start = 14.dp, top = 9.dp, end = 14.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$rank",
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = if (top3) FontWeight.Bold else FontWeight.Medium,
                    color = if (top3) WidgetColors.rankTop else WidgetColors.meta
                ),
                modifier = GlanceModifier.width(20.dp)
            )
            if (item.breaking) {
                val width = LocalSize.current.width
                val (first, rest) = remember(width, item.title) {
                    splitAroundCapsule(context, width, item.title)
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BreakingCapsule()
                        Spacer(GlanceModifier.width(6.dp))
                        Text(text = first, style = titleStyle, maxLines = 1)
                    }
                    if (rest != null) {
                        Text(text = rest, style = titleStyle, maxLines = 1)
                    }
                }
            } else {
                Text(text = item.title, style = titleStyle, maxLines = 2)
            }
        }
    }

    /** breaking 实心胶囊(errorContainer 底)—— 与标题第 1 行行内排列。 */
    @Composable
    private fun BreakingCapsule(modifier: GlanceModifier = GlanceModifier) {
        Box(
            modifier = modifier
                .background(WidgetColors.breakingBg)
                .cornerRadius(4.dp)
                .padding(start = 4.dp, top = 1.dp, end = 4.dp, bottom = 1.dp)
        ) {
            Text(
                text = "突发",
                style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = WidgetColors.breakingText
                ),
                maxLines = 1
            )
        }
    }

    /** 空态(无缓存且拉取失败/今日尚未生成):文案 + 品牌蓝胶囊按钮重试。 */
    @Composable
    private fun EmptyBody() {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "今日热点尚未生成",
                style = TextStyle(fontSize = 13.sp, color = WidgetColors.onBackground)
            )
            Spacer(GlanceModifier.height(8.dp))
            Box(
                modifier = GlanceModifier
                    .background(WidgetColors.refreshBg)
                    .cornerRadius(14.dp)
                    .clickable(actionRunCallback<RefreshHotNowAction>())
                    .padding(start = 14.dp, top = 6.dp, end = 14.dp, bottom = 6.dp)
            ) {
                Text(
                    text = "刷新重试",
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = WidgetColors.refreshIcon
                    ),
                    maxLines = 1
                )
            }
        }
    }

    /**
     * 点条目:深链打开 App 内置 WebView(EXTRA_OPEN_URL 三件套,MainActivity 统一消费)。
     * setData 使每条 intent 互不相同 —— PendingIntent 等价判断忽略 extras,
     * 不设 data 会导致各条目复用同一个 PendingIntent 而拿到错误 extras。
     */
    private fun openItemIntent(context: Context, item: HotNowWidgetState.Item): Intent =
        Intent(context, MainActivity::class.java)
            .setData(Uri.parse("ainewshub://hotnow/open?url=" + Uri.encode(item.url)))
            .putExtra(MainActivity.EXTRA_OPEN_URL, item.url)
            .putExtra(MainActivity.EXTRA_OPEN_URL_TITLE, item.title)
            .putExtra(MainActivity.EXTRA_OPEN_URL_SOURCE, SummaryRepository.titleOf(item.source))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 点头部标题区:仅把 App 带到前台(总览为默认首屏)。 */
    private fun openAppIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 「数据截至」行文案:M月d日 · 截至 HH:mm(每次组合新建实例,避免 SimpleDateFormat 线程问题)。 */
    private fun formatDataTime(ts: Long): String {
        val d = Date(ts)
        val date = SimpleDateFormat("M月d日", Locale.getDefault()).format(d)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
        return "$date · 截至 $time"
    }

    companion object {
        // 内联断行的宽度预算(dp):行左右 padding 14*2、排名列 20、胶囊约 30、胶囊后间距 6。
        private const val H_PADDING_DP = 28f
        private const val RANK_WIDTH_DP = 20f
        private const val CAPSULE_WIDTH_DP = 30f
        private const val CAPSULE_GAP_DP = 6f

        /** 测量安全余量:OEM 字体度量差异 + FontWeight.Medium 比 DEFAULT 略宽。 */
        private const val SAFE_MARGIN_DP = 6f

        /**
         * 把标题切成「胶囊旁第 1 行前缀」+「全宽第 2 行余量」,模拟文本绕胶囊内联换行。
         * 用 [Paint.breakText] 按系统默认字体实测(px),不猜字宽;第 2 行仍放不下时
         * 按容量截断并补「…」。返回 (first, rest);rest = null 表示胶囊旁一行即放得下。
         */
        private fun splitAroundCapsule(
            context: Context,
            widthDp: Dp,
            title: String
        ): Pair<String, String?> {
            val metrics = context.resources.displayMetrics
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 14f * metrics.scaledDensity   // 与标题 TextStyle(14.sp)一致
                typeface = Typeface.DEFAULT
            }
            val fullWidthPx =
                (widthDp.value - H_PADDING_DP - RANK_WIDTH_DP - SAFE_MARGIN_DP) * metrics.density
            val firstWidthPx = fullWidthPx - (CAPSULE_WIDTH_DP + CAPSULE_GAP_DP) * metrics.density
            // 胶囊旁一行即放得下:不拆
            if (paint.measureText(title) <= firstWidthPx) return title to null
            val firstCount = paint.breakText(title, true, firstWidthPx, null)
            // 极端窄宽等异常:不拆,交还单行由系统截断
            if (firstCount <= 0) return title to null
            val first = title.substring(0, firstCount)
            var rest = title.substring(firstCount)
            if (paint.measureText(rest) > fullWidthPx) {
                val ellipsis = "…"
                val keep = paint.breakText(
                    rest, true, fullWidthPx - paint.measureText(ellipsis), null
                )
                rest = rest.substring(0, keep.coerceAtLeast(0)) + ellipsis
            }
            return first to rest
        }
    }
}

/**
 * 小组件配色 —— App 设计令牌(ui/theme/Color.kt)的 day/night ColorProvider 封装。
 * 不用 GlanceTheme 壁纸动态色,保证小组件与 App 观感同源。
 */
private object WidgetColors {
    val background = ColorProvider(day = LightBackground, night = DarkBackground)
    val onBackground = ColorProvider(day = LightOnBackground, night = DarkOnBackground)
    /** 元信息/次级文字(「截至」行、非 Top3 排名)。 */
    val meta = ColorProvider(day = LightOnSurfaceVariant, night = DarkOnSurfaceVariant)
    /** Top3 排名品牌蓝(Future Blue;深色取 primary-fixed-dim 保对比)。 */
    val rankTop = ColorProvider(day = LightPrimary, night = DarkPrimary)
    /** 刷新按钮/重试胶囊:品牌蓝圆底 + 对比前景。 */
    val refreshBg = ColorProvider(day = LightPrimary, night = DarkPrimaryContainer)
    val refreshIcon = ColorProvider(day = LightOnPrimary, night = DarkOnPrimaryContainer)
    /** breaking 胶囊:errorContainer 底 + onErrorContainer 字。 */
    val breakingBg = ColorProvider(day = LightErrorContainer, night = DarkErrorContainer)
    val breakingText = ColorProvider(day = LightOnErrorContainer, night = DarkOnErrorContainer)
}
