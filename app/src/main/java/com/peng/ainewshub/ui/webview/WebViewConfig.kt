package com.peng.ainewshub.ui.webview

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.peng.ainewshub.data.prefs.FontScale
import kotlin.math.roundToInt

/**
 * WebView 实例配置 —— 设置项与深色主题的唯一装配点。
 */

/** 应用网页深色模式:优先用网页自带深色主题,无则算法深色(自动转深)。 */
internal fun applyDarkTheme(settings: WebSettings, darkTheme: Boolean) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, darkTheme)
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.configureWebSettings(darkTheme: Boolean, fontScale: FontScale) {
    layoutParams = android.view.ViewGroup.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        android.view.ViewGroup.LayoutParams.MATCH_PARENT
    )
    settings.apply {
        javaScriptEnabled = true          // AI HOT 站内页是 Next.js,需要 JS
        domStorageEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        cacheMode = WebSettings.LOAD_DEFAULT
        // 字号跟随「设置 → 字号档位」(100=标准)
        textZoom = (fontScale.scale * 100).roundToInt()
        // 安全加固:显式关闭文件/内容访问(默认 false,此处声明防后续误开)
        allowFileAccess = false          // 禁止 file:// 内容访问
        allowContentAccess = false       // 禁止 content:// 访问(本 App 无需)
        mediaPlaybackRequiresUserGesture = true  // 禁止页面自动播放音视频
        // UA 以系统 WebView 自带 UA 为基础(版本随 WebView 自动更新,不再硬编码老化),
        // 去掉 "; wv" 与 "Version/4.0" 标记伪装成移动版 Chrome —— 部分站点
        // (如 Google 登录)会拒绝原生 WebView UA
        userAgentString = WebSettings.getDefaultUserAgent(context)
            .replace("; wv", "")
            .replace(Regex("Version/\\d+(\\.\\d+)*\\s+"), "")
    }
    applyDarkTheme(settings, darkTheme)
}
