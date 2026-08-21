package com.peng.ainewshub.playback

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.peng.ainewshub.R

/**
 * 语音速报 UI 启动辅助 —— 统一「通知权限请求 + 待播队列暂存 + 启动服务」三步,
 * 总览/关注两个入口共用。
 *
 * 权限策略(API 33+ 首次点播报时请求,与设置页每日通知同一权限):
 *  - 已授予 → 直接启动;
 *  - 拒绝 → Toast 提示后**仍然启动**(前台服务播报可用,仅通知栏控制不可见);
 *  - API < 33 → 无运行时权限,直接启动。
 *
 * 返回的 handler 可在点击回调里直接调用(remember 持有,权限回调暂存待播列表)。
 */
@Composable
fun rememberTtsStartHandler(): (List<TtsEntry>) -> Unit {
    val context = LocalContext.current
    // 权限请求期间的待播列表(授权回调里消费;拒绝也消费 —— 见函数头策略)
    var pendingEntries by remember { mutableStateOf<List<TtsEntry>?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val entries = pendingEntries
        pendingEntries = null
        if (!granted) {
            Toast.makeText(context, context.getString(R.string.tts_notify_denied), Toast.LENGTH_SHORT).show()
        }
        if (entries != null) TtsPlaybackService.start(context, entries)
    }
    return remember(permissionLauncher) {
        { entries: List<TtsEntry> ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                pendingEntries = entries
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                TtsPlaybackService.start(context, entries)
            }
        }
    }
}
