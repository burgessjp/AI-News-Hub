package com.peng.ainewshub.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.peng.ainewshub.data.OverviewRepository

/**
 * 「今日热点」小组件的取数与刷新入口。
 *
 * 数据永远只读归档 `latest_overview`(与总览页同源同语义,与 SourceMode 无关):
 * [OverviewRepository.loadDigest] 内部经 ArchiveHttpClient 读 index.json,
 * 自带 2 分钟内存缓存 —— App 刚浏览过总览时这里几乎零网络开销。
 *
 * 失败语义:保留旧缓存数据不清空(界面上数据仍在,仅时间不前进),与 App「归档失败
 * 显示错误态」不同 —— 小组件无错误交互入口,留旧数据比空白更可取。
 */
object HotNowWidgetUpdater {

    /** 两次拉取尝试的最小间隔(防止手动刷新失败后 provideGlance 立刻重复打网络)。 */
    private const val ATTEMPT_THROTTLE_MS = 5L * 60 * 1000

    /**
     * 拉取最新总览并写入小组件缓存。
     *
     * @param force 手动刷新 / App 联动为 true(跳过节流;网络仍受 ArchiveHttpClient
     *   2 分钟缓存保护);系统周期刷新 / provideGlance 补网为 false
     * @param triggerUpdate 成功后是否 updateAll 触发重渲染;provideGlance 内部调用传 false
     *   (它自己会继续 provideContent,再 updateAll 会多跑一轮 provideGlance)
     * @return true = 有有效数据;false = 本次拉取失败(旧缓存保留)
     */
    suspend fun refresh(
        context: Context,
        force: Boolean = false,
        triggerUpdate: Boolean = true
    ): Boolean {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        if (!force && now - HotNowWidgetStore.read(appContext).lastAttemptAt < ATTEMPT_THROTTLE_MS) {
            return HotNowWidgetStore.read(appContext).hasData
        }
        HotNowWidgetStore.markAttempt(appContext, now)
        return OverviewRepository().loadDigest().fold(
            onSuccess = { digest ->
                HotNowWidgetStore.write(
                    appContext,
                    items = digest.items.map {
                        HotNowWidgetState.Item(
                            source = it.source,
                            title = it.title,
                            url = it.url,
                            breaking = it.breaking
                        )
                    },
                    generatedAt = digest.generatedAt,
                    dataFetchedAt = digest.dataFetchedAt,
                    successAt = now
                )
                if (triggerUpdate) HotNowWidget().updateAll(appContext)
                true
            },
            onFailure = { e ->
                Log.w("HotNowWidget", "小组件数据刷新失败: ${e.message ?: "(no message)"}")
                false
            }
        )
    }

    /**
     * App 内总览刷新成功后的联动入口(OverviewViewModel 调用)。
     * 同进程命中 ArchiveHttpClient 2 分钟内存缓存,零额外网络;失败静默,不影响 App UI 态。
     */
    suspend fun refreshFromApp(context: Context) {
        runCatching { refresh(context.applicationContext, force = true) }
    }
}
