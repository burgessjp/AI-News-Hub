package com.peng.ainewshub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.peng.ainewshub.ui.components.AppTab
import com.peng.ainewshub.ui.i18n.AppLocale
import com.peng.ainewshub.ui.nav.AiNewsHubApp

class MainActivity : ComponentActivity() {

    companion object {
        /** Intent extra:为 true 时启动后自动进入「设置」页(系统选中翻译的「去设置」入口)。 */
        const val EXTRA_OPEN_SETTINGS = "com.peng.ainewshub.extra.OPEN_SETTINGS"

        /** Intent extra:桌面小组件深链 —— 启动后在内置 WebView 打开该 URL(配 _TITLE / _SOURCE)。 */
        const val EXTRA_OPEN_URL = "com.peng.ainewshub.extra.OPEN_URL"
        const val EXTRA_OPEN_URL_TITLE = "com.peng.ainewshub.extra.OPEN_URL_TITLE"
        const val EXTRA_OPEN_URL_SOURCE = "com.peng.ainewshub.extra.OPEN_URL_SOURCE"
    }

    /** 外部深链目标(ainewshub:// scheme,解析见 [deepLink])。 */
    private sealed interface DeepLink {
        /** 直达内置 WebView(与小组件 extras 深链同构,复用 pendingOpenUrl 消费链)。 */
        data class Web(val url: String, val title: String, val source: String?) : DeepLink

        /** 切到指定根 tab(清空该 tab 二级栈)。 */
        data class Tab(val tab: AppTab) : DeepLink

        /** 直达设置页(冷/热启动均可,经 openSettingsRequest 消费一次)。 */
        data object Settings : DeepLink
    }

    /** 待消费的小组件/深链 WebView 请求(Compose 状态:onCreate/onNewIntent 写入,UI 层消费后经回调清空)。 */
    private var pendingOpenUrl by mutableStateOf<Triple<String, String, String?>?>(null)

    /** 待消费的深链 tab 切换请求(同上范式)。 */
    private var pendingTab by mutableStateOf<AppTab?>(null)

    /** 待消费的「直达设置」请求(冷启动 extras 与深链共用;消费后复位以支持热启动重复触发)。 */
    private var openSettingsRequest by mutableStateOf(false)

    /** 应用内语言(设置页「语言」):非「跟随系统」时按用户选择包裹配置。 */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 冷启动带深链(小组件 extras / ainewshub:// uri):仅在全新启动时消费,
        // 避免旋转重建后重复 push
        if (savedInstanceState == null) {
            if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
                openSettingsRequest = true
            }
            applyDeepLinks(intent)
        }
        setContent {
            AiNewsHubApp(
                openSettingsOnLaunch = openSettingsRequest,
                onSettingsConsumed = { openSettingsRequest = false },
                pendingOpenUrl = pendingOpenUrl,
                onPendingUrlConsumed = { pendingOpenUrl = null },
                pendingTab = pendingTab,
                onPendingTabConsumed = { pendingTab = null }
            )
        }
    }

    /** 热启动(singleTask,Activity 已在栈内):小组件点击 / 外部深链经此直达。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyDeepLinks(intent)
    }

    /**
     * 从 intent 提取深链请求并写入待消费状态:小组件 extras 优先,其次 ainewshub:// uri。
     * 两种来源映射到同一组状态(pendingOpenUrl / pendingTab / openSettingsRequest),
     * UI 层(AiNewsHubApp)统一消费。
     */
    private fun applyDeepLinks(intent: Intent) {
        intent.openUrlRequest()?.let {
            pendingOpenUrl = it
            return
        }
        when (val d = intent.deepLink()) {
            is DeepLink.Web -> pendingOpenUrl = Triple(d.url, d.title, d.source)
            is DeepLink.Tab -> pendingTab = d.tab
            DeepLink.Settings -> openSettingsRequest = true
            null -> Unit
        }
    }

    /** 解析小组件深链 extra:url 缺失/为空视为无深链。 */
    private fun Intent.openUrlRequest(): Triple<String, String, String?>? {
        val url = getStringExtra(EXTRA_OPEN_URL)?.takeIf { it.isNotBlank() } ?: return null
        return Triple(
            url,
            getStringExtra(EXTRA_OPEN_URL_TITLE) ?: "",
            getStringExtra(EXTRA_OPEN_URL_SOURCE)
        )
    }

    /**
     * 解析 ainewshub:// 深链(非本 scheme 或路由不认识返回 null):
     *  - ainewshub://web?url=<encoded>&title=<encoded>&source=<encoded> → 内置 WebView
     *    (url 仅接受 http/https,防 file:// 等本地 scheme 注入)
     *  - ainewshub://tab/<overview|summary|follows|trends|more> → 切根 tab
     *  - ainewshub://settings → 设置页
     */
    private fun Intent.deepLink(): DeepLink? {
        val data = data ?: return null
        if (!data.scheme.equals("ainewshub", ignoreCase = true)) return null
        return when (data.host?.lowercase()) {
            "web" -> {
                val url = data.getQueryParameter("url")?.trim()
                    ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                    ?: return null
                DeepLink.Web(
                    url = url,
                    title = data.getQueryParameter("title").orEmpty(),
                    source = data.getQueryParameter("source")
                )
            }
            "settings" -> DeepLink.Settings
            "tab" -> tabOf(data.lastPathSegment?.lowercase())?.let { DeepLink.Tab(it) }
            else -> null
        }
    }

    /** 深链 tab 名 → [AppTab];未知名称返回 null(视为无深链)。 */
    private fun tabOf(name: String?): AppTab? = when (name) {
        "overview" -> AppTab.Overview
        "summary" -> AppTab.Summary
        "follows" -> AppTab.Follows
        "trends" -> AppTab.Trends
        "more" -> AppTab.More
        else -> null
    }
}
