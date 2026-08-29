package com.peng.ainewshub.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.peng.ainewshub.R

/**
 * 文本分享与复制的通用出口 —— 诊断报告(设置页)与 WebView 页分享共用。
 * 平台 ClipboardManager 而非 Compose LocalClipboardManager:两处调用都在
 * 非组合回调(onClick)里,顺手保持与 WebView 原实现同路径。
 */

/** 系统分享:把任意纯文本交给系统分享面板。 */
internal fun shareText(context: Context, title: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.common_share)))
}

/** 复制到系统剪贴板并 toast 回执;无剪贴板服务(极端 ROM 场景)静默跳过。 */
internal fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("text", text))
    Toast.makeText(context, R.string.common_copied, Toast.LENGTH_SHORT).show()
}
