package com.peng.ainewshub.widget

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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
import androidx.glance.appwidget.background
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
import com.peng.ainewshub.ui.i18n.AppLocale
import com.peng.ainewshub.ui.theme.DarkErrorContainer
import com.peng.ainewshub.ui.theme.DarkOnBackground
import com.peng.ainewshub.ui.theme.DarkOnErrorContainer
import com.peng.ainewshub.ui.theme.DarkOnPrimary
import com.peng.ainewshub.ui.theme.DarkOnPrimaryContainer
import com.peng.ainewshub.ui.theme.DarkOnSurfaceVariant
import com.peng.ainewshub.ui.theme.DarkOnTertiary
import com.peng.ainewshub.ui.theme.DarkOnTertiaryContainer
import com.peng.ainewshub.ui.theme.DarkPrimary
import com.peng.ainewshub.ui.theme.DarkPrimaryContainer
import com.peng.ainewshub.ui.theme.DarkSurfaceContainerHigh
import com.peng.ainewshub.ui.theme.DarkTertiary
import com.peng.ainewshub.ui.theme.DarkTertiaryContainer
import com.peng.ainewshub.ui.theme.LightErrorContainer
import com.peng.ainewshub.ui.theme.LightOnBackground
import com.peng.ainewshub.ui.theme.LightOnErrorContainer
import com.peng.ainewshub.ui.theme.LightOnPrimary
import com.peng.ainewshub.ui.theme.LightOnSurfaceVariant
import com.peng.ainewshub.ui.theme.LightOnTertiary
import com.peng.ainewshub.ui.theme.LightOnTertiaryContainer
import com.peng.ainewshub.ui.theme.LightPrimary
import com.peng.ainewshub.ui.theme.LightSurfaceContainerHigh
import com.peng.ainewshub.ui.theme.LightTertiary
import com.peng.ainewshub.ui.theme.LightTertiaryContainer
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
 * 设计:App 内「今日热点」卡(HotTopicsSection)的迷你版 ——
 *  - 背景:卡片色 drawable(surfaceContainerLow + 1dp outlineVariant 描边,widget_bg day/night 变体),
 *    对齐 App 卡「描边分层不靠阴影」;
 *  - 头部:品牌渐变 Hero(widget_header_gradient,BrandGradient 同源)—— 标题行(标题 + 「截至」时间 +
 *    半透明刷新圆钮,onPrimary 18% overlay 同 App 渐变头上 chip 工艺)+ 「今日综述」正文(≤2 行,
 *    空串不渲染,与总览 Tab digest Hero 同构);
 *  - 条目:迷你排名徽章(18dp,分档同 App RankBadge:1 名 tertiary 实心/2-3 tertiaryContainer/其余灰)+
 *    Bold 标题,行间发丝线;breaking 胶囊与标题「内联」(测量断行,见 [splitAroundCapsule]);
 *    来源(源名/品牌色)与互动指标刻意不上小组件 —— 桌面场景只留「什么新闻、有多急」。
 * 配色取 ui/theme/Color.kt 顶层令牌组 day/night ColorProvider,不用 GlanceTheme 的壁纸动态色。
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
        // 小组件无 attachBaseContext:取词统一经 AppLocale.wrap 后的 context(下传各子组件)
        val ctx = AppLocale.wrap(context)
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                // 卡片背景(卡面色 + 1dp 描边 + 16dp 圆角)由 drawable 承担,day/night 资源限定符自动切换
                .background(ImageProvider(R.drawable.widget_bg))
        ) {
            Header(ctx, state)
            if (state.hasData) {
                ItemList(ctx, state.items)
            } else {
                EmptyBody(ctx)
            }
        }
    }

    /**
     * 头部:品牌渐变 Hero —— 标题行(标题 + 「数据截至」+ 半透明刷新圆钮)+
     * 「今日综述」正文(≤2 行截断;空串 = 旧归档无此字段,不渲染,退化为单行头)。
     * 与总览 Tab 的 digest Hero(BrandGradient 通栏)同构,是 widget 上的信息密度担当。
     */
    @Composable
    private fun Header(context: Context, state: HotNowWidgetState) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ImageProvider(R.drawable.widget_header_gradient))
        ) {
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, top = 8.dp, end = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = context.getString(R.string.widget_hot_now_title),
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = WidgetColors.headerText
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight()
                        .clickable(actionStartActivity(openAppIntent(context)))
                )
                if (state.dataFetchedAt > 0) {
                    Text(
                        text = formatDataTime(context, state.dataFetchedAt),
                        style = TextStyle(fontSize = 10.sp, color = WidgetColors.headerMeta),
                        maxLines = 1,
                        modifier = GlanceModifier
                            .padding(start = 8.dp)
                            .clickable(actionStartActivity(openAppIntent(context)))
                    )
                }
                Spacer(GlanceModifier.width(8.dp))
                // 刷新:渐变头上的半透明圆钮(onPrimary 18% overlay 底),比大块品牌蓝实心钮更融入渐变
                Box(
                    modifier = GlanceModifier.size(26.dp)
                        .background(WidgetColors.headerBtnBg)
                        .cornerRadius(13.dp)
                        .clickable(actionRunCallback<RefreshHotNowAction>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_refresh),
                        contentDescription = context.getString(R.string.common_refresh),
                        colorFilter = ColorFilter.tint(WidgetColors.headerText),
                        modifier = GlanceModifier.size(14.dp)
                    )
                }
            }
            if (state.digest.isNotBlank()) {
                Text(
                    text = state.digest,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = WidgetColors.headerText
                    ),
                    maxLines = 2,
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, top = 0.dp, end = 14.dp, bottom = 10.dp)
                        .clickable(actionStartActivity(openAppIntent(context)))
                )
            }
        }
    }

    @Composable
    private fun ItemList(context: Context, items: List<HotNowWidgetState.Item>) {
        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
            items(items.size) { index ->
                Column {
                    ItemRow(context, rank = index + 1, item = items[index])
                    // 行间发丝线(App HairlineDivider 观感):缩进对齐标题列,最后一条不画
                    if (index < items.lastIndex) {
                        Box(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .padding(start = 38.dp, end = 12.dp)
                                .height(1.dp)
                                .background(WidgetColors.divider)
                        ) {}
                    }
                }
            }
        }
    }

    /**
     * 单条:迷你排名徽章 + 标题(≤2 行,Bold;Glance 无 SemiBold 档,以 Bold 承担强调)。
     * breaking 条目:胶囊与标题内联 —— 实测标题在胶囊旁能放下的前缀作第 1 行,
     * 余量全宽作第 2 行,视觉上是文本绕胶囊自然换行(而非整体右缩的 tag 前缀)。
     */
    @Composable
    private fun ItemRow(context: Context, rank: Int, item: HotNowWidgetState.Item) {
        val titleStyle = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = WidgetColors.onBackground
        )
        Row(
            modifier = GlanceModifier.fillMaxWidth()
                .clickable(actionStartActivity(openItemIntent(context, item)))
                .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RankBadgeMini(rank)
            Spacer(GlanceModifier.width(8.dp))
            if (item.breaking) {
                val width = LocalSize.current.width
                val (first, rest) = remember(width, item.title) {
                    splitAroundCapsule(context, width, item.title)
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BreakingCapsule(context)
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

    /**
     * 迷你排名徽章:App RankBadge(ui/components/RankBadge.kt)的 18dp 桌面版,分档配色一致 ——
     * 第 1 名 tertiary 实心(唯一强强调)/ 2-3 名 tertiaryContainer / 其余 surfaceContainerHigh 低对比。
     */
    @Composable
    private fun RankBadgeMini(rank: Int) {
        val (bg, fg) = when {
            rank == 1 -> WidgetColors.badgeTopBg to WidgetColors.badgeTopFg
            rank <= 3 -> WidgetColors.badgeMidBg to WidgetColors.badgeMidFg
            else -> WidgetColors.badgeRestBg to WidgetColors.badgeRestFg
        }
        Box(
            modifier = GlanceModifier.size(18.dp)
                .background(bg)
                .cornerRadius(5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$rank",
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = fg
                ),
                maxLines = 1
            )
        }
    }

    /** breaking 实心胶囊(errorContainer 底)—— 与标题第 1 行行内排列。 */
    @Composable
    private fun BreakingCapsule(context: Context, modifier: GlanceModifier = GlanceModifier) {
        Box(
            modifier = modifier
                .background(WidgetColors.breakingBg)
                .cornerRadius(4.dp)
                .padding(start = 4.dp, top = 1.dp, end = 4.dp, bottom = 1.dp)
        ) {
            Text(
                text = context.getString(R.string.widget_breaking),
                style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = WidgetColors.breakingText
                ),
                maxLines = 1
            )
        }
    }

    /** 空态(无缓存且拉取失败/今日尚未生成):文案 + 品牌蓝胶囊按钮重试(渐变头保留,观感统一)。 */
    @Composable
    private fun EmptyBody(context: Context) {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = context.getString(R.string.widget_empty_not_generated),
                style = TextStyle(fontSize = 13.sp, color = WidgetColors.onBackground)
            )
            Spacer(GlanceModifier.height(8.dp))
            Box(
                modifier = GlanceModifier
                    .background(WidgetColors.emptyActionBg)
                    .cornerRadius(14.dp)
                    .clickable(actionRunCallback<RefreshHotNowAction>())
                    .padding(start = 14.dp, top = 6.dp, end = 14.dp, bottom = 6.dp)
            ) {
                Text(
                    text = context.getString(R.string.widget_refresh_retry),
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = WidgetColors.emptyActionText
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
            .putExtra(MainActivity.EXTRA_OPEN_URL_SOURCE, SummaryRepository.titleOf(context, item.source))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 点头部标题区:仅把 App 带到前台(总览为默认首屏)。 */
    private fun openAppIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 「数据截至」行文案:日期模式取 date_fmt_month_day 资源(每次组合新建实例,避免 SimpleDateFormat 线程问题)。 */
    private fun formatDataTime(context: Context, ts: Long): String {
        val d = Date(ts)
        val date = SimpleDateFormat(context.getString(R.string.date_fmt_month_day), Locale.getDefault()).format(d)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
        return context.getString(R.string.widget_data_as_of, date, time)
    }

    companion object {
        // 内联断行的宽度预算(dp):行左右 padding 12*2、徽章列 18+8、胶囊约 30、胶囊后间距 6。
        private const val H_PADDING_DP = 24f
        private const val RANK_WIDTH_DP = 26f
        private const val CAPSULE_WIDTH_DP = 30f
        private const val CAPSULE_GAP_DP = 6f

        /** 测量安全余量:OEM 字体度量差异 + Bold 比 DEFAULT 略宽。 */
        private const val SAFE_MARGIN_DP = 8f

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
            // scaledDensity 自 API 34 起废弃,等价改用 density * Configuration.fontScale;后者在多窗口场景下也更准
            val scaledDensity = metrics.density * context.resources.configuration.fontScale
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 14f * scaledDensity   // 与标题 TextStyle(14.sp)一致
                // 标题现为 Bold 档,测量字体同步加粗,否则前缀实测偏窄导致换行错位
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
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
 * 背景与渐变头是 drawable 资源(widget_bg / widget_header_gradient 的 day/night 限定符变体),不在此列。
 */
private object WidgetColors {
    /** 标题/空态正文。 */
    val onBackground = ColorProvider(day = LightOnBackground, night = DarkOnBackground)

    // —— 渐变头(BrandGradient 同源:light 深渐变白字,night 亮渐变深字)——

    /** 头部标题/图标前景(onPrimary)。 */
    val headerText = ColorProvider(day = LightOnPrimary, night = DarkOnPrimary)

    /** 头部「截至」时间(onPrimary 85% 弱化,对应 App Alpha.primaryEmphasis)。 */
    val headerMeta = ColorProvider(day = Color(0xD9FFFFFF), night = Color(0xD9002C9A))

    /** 头部刷新圆钮底(onPrimary 18% overlay,对应 AppAlpha.onPrimaryOverlay)。 */
    val headerBtnBg = ColorProvider(day = Color(0x2EFFFFFF), night = Color(0x2E002C9A))

    // —— 排名徽章(分档同 App RankBadge)——

    /** 第 1 名:tertiary 实心(唯一强强调)。 */
    val badgeTopBg = ColorProvider(day = LightTertiary, night = DarkTertiary)
    val badgeTopFg = ColorProvider(day = LightOnTertiary, night = DarkOnTertiary)

    /** 第 2-3 名:tertiaryContainer。 */
    val badgeMidBg = ColorProvider(day = LightTertiaryContainer, night = DarkTertiaryContainer)
    val badgeMidFg = ColorProvider(day = LightOnTertiaryContainer, night = DarkOnTertiaryContainer)

    /** 其余:surfaceContainerHigh 低对比。 */
    val badgeRestBg = ColorProvider(day = LightSurfaceContainerHigh, night = DarkSurfaceContainerHigh)
    val badgeRestFg = ColorProvider(day = LightOnSurfaceVariant, night = DarkOnSurfaceVariant)

    /** 行间发丝线(outlineVariant 50%,App HairlineDivider 观感)。 */
    val divider = ColorProvider(day = Color(0x80C3C5D9), night = Color(0x80434656))

    /** 空态重试胶囊:品牌蓝实心 + 对比前景。 */
    val emptyActionBg = ColorProvider(day = LightPrimary, night = DarkPrimaryContainer)
    val emptyActionText = ColorProvider(day = LightOnPrimary, night = DarkOnPrimaryContainer)

    /** breaking 胶囊:errorContainer 底 + onErrorContainer 字。 */
    val breakingBg = ColorProvider(day = LightErrorContainer, night = DarkErrorContainer)
    val breakingText = ColorProvider(day = LightOnErrorContainer, night = DarkOnErrorContainer)
}
