package com.peng.ainewshub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    /** 待消费的小组件深链(Compose 状态:onCreate/onNewIntent 写入,UI 层消费后经回调清空)。 */
    private var pendingOpenUrl by mutableStateOf<Triple<String, String, String?>?>(null)

    /** 应用内语言(设置页「语言」):非「跟随系统」时按用户选择包裹配置。 */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val openSettings = savedInstanceState == null &&
            intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)
        // 冷启动带深链(小组件点击):仅在全新启动时消费,避免旋转重建后重复 push
        if (savedInstanceState == null) intent.openUrlRequest()?.let { pendingOpenUrl = it }
        setContent {
            AiNewsHubApp(
                openSettingsOnLaunch = openSettings,
                pendingOpenUrl = pendingOpenUrl,
                onPendingUrlConsumed = { pendingOpenUrl = null }
            )
        }
    }

    /** 热启动(singleTask,Activity 已在栈内):小组件点击经此带深链直达。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.openUrlRequest()?.let { pendingOpenUrl = it }
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
}
