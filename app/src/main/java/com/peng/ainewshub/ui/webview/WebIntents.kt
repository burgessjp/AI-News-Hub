package com.peng.ainewshub.ui.webview

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.peng.ainewshub.R
import com.peng.ainewshub.ui.components.shareText

/**
 * WebView 的系统交互出口 —— 分享与外部 App 唤起。
 */

/** 系统分享:把当前页 URL 作为纯文本交给系统分享面板(通用出口见 ui/components/ShareText.kt)。 */
internal fun shareUrl(context: Context, title: String, url: String) =
    shareText(context, title, url)

/**
 * 唤起外部 App —— 处理网页发起的非 http(s) 协议链接。
 *
 * 分两类:
 *  - intent:// URI:按 Chrome 规范解析 Intent。优先解析其中的 fallback URL
 *    (S.browser_fallback_url),目标 App 不存在时用它(通常跳应用市场/网页)。
 *  - 普通自定义 scheme(weixin://、mailto:、tel:…):直接构造 ACTION_VIEW 唤起。
 *
 * 任何无法解析 / 无 App 接收的异常都用 Toast 提示,绝不崩溃。
 */
internal fun handleExternalUri(context: Context, uri: Uri) {
    val scheme = uri.scheme?.lowercase()
    try {
        if (scheme == "intent") {
            // Chrome intent:// 规范 —— Intent.parseUri 解析,带 fallback URL
            val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                .addCategory(Intent.CATEGORY_BROWSABLE)
            // 目标 App 未安装时,优先用网页声明的 fallback URL
            val fallback = intent.getStringExtra("browser_fallback_url")
            // fallback 仅允许 http(s):防止恶意页面用 file:///、content:// 作 fallback 泄漏本地文件
            val fallbackScheme = fallback?.let { runCatching { Uri.parse(it).scheme?.lowercase() }.getOrNull() }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else if (!fallback.isNullOrBlank() &&
                (fallbackScheme == "http" || fallbackScheme == "https")
            ) {
                // 仅 http(s) 网址,交给系统浏览器/市场打开
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(fallback))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                )
            } else {
                Toast.makeText(context, context.getString(R.string.webview_toast_no_app_for_link), Toast.LENGTH_SHORT).show()
            }
        } else {
            // weixin://、mailto:、tel:、sms: 等普通自定义 scheme
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            )
        }
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.webview_toast_no_app_for_link), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.webview_toast_open_link_failed), Toast.LENGTH_SHORT).show()
    }
}
