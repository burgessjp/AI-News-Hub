package com.peng.ainewshub.data.prefs

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [SettingsStore] 显示偏好(display_prefs)持久化回归 —— 以 themeMode 为样例钉住
 * 「写 store → prefsFlow 回推 → 主题层重组」的闭环(皮肤/动态取色键已随 v1.4.0
 * 纸墨日报改版移除,存量盘上旧值成为不可读残留,无副作用)。
 *
 * 用例合并为单方法:display_prefs 的 preferencesDataStore 委托是进程级静态单例,
 * Robolectric 同类内多方法会共享同一实例(状态跨方法泄漏),拆开反而不独立;
 * 单方法内按「默认 → 写入 → 读回」顺序自洽。
 */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    @Test
    fun `主题模式默认 System 且 updateTheme 持久化往返`() {
        val store = SettingsStore(RuntimeEnvironment.getApplication())
        // 全新 DataStore 无 theme_mode 键 → 默认 System
        assertEquals(ThemeMode.System, runBlocking { store.prefsFlow.first() }.themeMode)
        runBlocking { store.updateTheme(ThemeMode.Dark) }
        assertEquals(ThemeMode.Dark, runBlocking { store.prefsFlow.first() }.themeMode)
    }
}
