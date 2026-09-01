package com.peng.ainewshub.data.prefs

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [SettingsStore] 皮肤偏好(display_prefs 的 skin 键)持久化回归。
 *
 * 用例合并为单方法:display_prefs 的 preferencesDataStore 委托是进程级静态单例,
 * Robolectric 同类内多方法会共享同一实例(状态跨方法泄漏),拆开反而不独立;
 * 单方法内按「默认 → 写入 → 读回」顺序自洽。
 */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    @Test
    fun `皮肤默认 Classic 且 updateSkin 持久化往返`() {
        val store = SettingsStore(RuntimeEnvironment.getApplication())
        // 全新 DataStore 无 skin 键 → 默认 Classic:存量用户升级后观感不变的保证
        assertEquals(AppSkin.Classic, runBlocking { store.prefsFlow.first() }.skin)
        // 设置页选择黑白后的即时生效闭环:写 store → prefsFlow 回推 → 主题层重组换色板
        runBlocking { store.updateSkin(AppSkin.Mono) }
        assertEquals(AppSkin.Mono, runBlocking { store.prefsFlow.first() }.skin)
    }
}
