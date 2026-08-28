package com.peng.ainewshub.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.peng.ainewshub.MainActivity
import com.peng.ainewshub.R
import com.peng.ainewshub.data.PipelineSchedule
import com.peng.ainewshub.data.source.ArchiveHttpClient
import com.peng.ainewshub.ui.i18n.AppLocale
import com.peng.ainewshub.ui.more.SettingsStore
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 每日更新通知 —— 流水线新批次数据就绪后给用户发一条本地通知,点击直达 App(默认总览 tab)。
 *
 * 纯本地方案(无 FCM / 服务端):WorkManager 轮询归档 index.json,三个角色都在本文件:
 *  - [DailyUpdateNotifier]:建渠道 + 发通知
 *  - [DailyNotifyScheduler]:调度(自查链时刻表 + 开关同步)
 *  - [DailyUpdateWorker]:检测新批次并决定是否发
 *
 * 检测指纹:`index.json` 顶层 `latest_overview.generatedAt`(流水线生成时刻)。不用
 * `updated_at_ms`:总览 AI 生成失败时 latest_overview 继承旧值,generatedAt 不变,不误报。
 *
 * 频率:每天(北京时间)至多 1 条 —— 当天首次检测到新批次时发,之后批次静默。
 * 通知正文 digest 始终为中文(流水线内容,与小组件同策略),标题/渠道名随界面语言。
 */

/** 通知渠道 id(系统设置里按此分组,不可变;名称随语言取词,变更语言后系统侧旧名保留,可接受)。 */
private const val CHANNEL_ID = "daily_update"

/** 固定通知 id:极端情况下同日重发覆盖旧通知而非堆叠。 */
private const val NOTIFY_ID = 1001

object DailyUpdateNotifier {

    /**
     * 发「今日热点已更新」通知。
     *
     * @param digest 今日综述(可空/空串 → 用 fallback 文案);始终中文,见文件头说明
     */
    fun post(context: Context, digest: String?) {
        ensureChannel(context)
        val localized = AppLocale.wrap(context)
        val text = digest?.takeIf { it.isNotBlank() }
            ?: localized.getString(R.string.notify_daily_text_fallback)
        // 直达 MainActivity(singleTask):无 extra,落默认「总览」tab;已有任务则带回前台
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(localized.getString(R.string.notify_daily_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        // 权限被用户在系统设置收回时系统静默丢弃,无需自行检查
        NotificationManagerCompat.from(context).notify(NOTIFY_ID, notification)
    }

    /** 懒建通知渠道(幂等);API 26 以下无渠道概念直接跳过。 */
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val name = AppLocale.wrap(context).getString(R.string.notify_daily_channel_name)
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

object DailyNotifyScheduler {

    /** inputData key:本档内已补查次数(0 = 首次正点检查)。 */
    const val KEY_ATTEMPT = "attempt"

    private const val UNIQUE_CHECK = "daily_update_check"
    private const val UNIQUE_RETRY = "daily_update_retry"

    /** 档内最多补查次数(批次延迟时 40 分钟后再查)。 */
    private const val MAX_RETRY_ATTEMPTS = 2
    private val RETRY_DELAY_MS = TimeUnit.MINUTES.toMillis(40)

    /**
     * 检查时刻表(北京时间,小时 to 分钟)—— 由 [PipelineSchedule.BATCH_SLOTS]
     * 各 +40 分钟余量派生(GitHub cron 漂移 + 抓取与 AI 生成耗时)。
     * ⚠️ 流水线批次时间变更只改 PipelineSchedule(批次唯一真相源),本表自动跟随
     * (AGENTS.md / docs/agents/data-layer.md 有同样提醒)。
     */
    private val CHECK_SLOTS = PipelineSchedule.BATCH_SLOTS.map { (h, m) -> h to m + 40 }

    /**
     * 设置页开关同步入口:开 → 从下一档开始续链;关 → 取消全部,链终止。
     * WorkManager 任务持久化跨重启,无需 boot receiver。
     */
    fun sync(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(UNIQUE_CHECK)
            wm.cancelUniqueWork(UNIQUE_RETRY)
            return
        }
        enqueueNext(context)
    }

    /**
     * 排「下一个未来档位」的正点检查(REPLACE:重复调用只保留最新一次)。
     * Worker 每次跑完都会调它续链 —— 链不依赖单次任务存活,doze 延迟/重启后跑到即自愈。
     */
    fun enqueueNext(context: Context) {
        val request = buildRequest(nextSlotDelayMillis(), attempt = 0)
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_CHECK, ExistingWorkPolicy.REPLACE, request)
    }

    /** 档内补查:[attempt] 超过 [MAX_RETRY_ATTEMPTS] 不再补(交给下一档)。 */
    fun enqueueRetry(context: Context, attempt: Int) {
        if (attempt > MAX_RETRY_ATTEMPTS) return
        val request = buildRequest(RETRY_DELAY_MS, attempt)
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_RETRY, ExistingWorkPolicy.REPLACE, request)
    }

    private fun buildRequest(delayMs: Long, attempt: Int): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DailyUpdateWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setInputData(Data.Builder().putInt(KEY_ATTEMPT, attempt).build())
            .build()

    /** 距下一个档位的毫秒数(今天档位已过 → 明天第一档)。 */
    private fun nextSlotDelayMillis(): Long {
        val now = Calendar.getInstance(PipelineSchedule.BEIJING)
        for ((hour, minute) in CHECK_SLOTS) {
            val candidate = now.clone() as Calendar
            candidate.set(Calendar.HOUR_OF_DAY, hour)
            candidate.set(Calendar.MINUTE, minute)
            candidate.set(Calendar.SECOND, 0)
            candidate.set(Calendar.MILLISECOND, 0)
            if (candidate.after(now)) return candidate.timeInMillis - now.timeInMillis
        }
        val tomorrowFirst = now.clone() as Calendar
        tomorrowFirst.add(Calendar.DAY_OF_YEAR, 1)
        tomorrowFirst.set(Calendar.HOUR_OF_DAY, CHECK_SLOTS.first().first)
        tomorrowFirst.set(Calendar.MINUTE, CHECK_SLOTS.first().second)
        tomorrowFirst.set(Calendar.SECOND, 0)
        tomorrowFirst.set(Calendar.MILLISECOND, 0)
        return tomorrowFirst.timeInMillis - now.timeInMillis
    }

    /** [epochMs] 是否落在今天(北京时间);epochMs <= 0(从未通知)恒为 false。 */
    fun isSameBeijingDay(epochMs: Long): Boolean {
        if (epochMs <= 0L) return false
        val now = Calendar.getInstance(PipelineSchedule.BEIJING)
        val then = Calendar.getInstance(PipelineSchedule.BEIJING).apply { timeInMillis = epochMs }
        return now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    }
}

class DailyUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    /**
     * 检测新批次,必要时发通知。
     *
     * 调度纪律:
     *  - 除「开关已关」外,所有路径都先续链(enqueueNext)再干活 —— 任何失败不断链;
     *  - 不用 Result.retry():WorkManager 退避重试与自查链语义冲突,失败一律 success + 自排下一跳;
     *  - 每次运行记 `last_notify_check_at`(设置页「上次检查」),用于区分
     *    「链被系统后台限制(One UI 休眠等)拦住没跑」和「跑了但档内没新数据」。
     */
    override suspend fun doWork(): Result {
        val store = SettingsStore(applicationContext)
        // 开关已关:链终止(正常路径 sync 已 cancel 全部任务,此为竞态兜底)
        if (!store.prefsFlow.first().dailyNotify) return Result.success()
        DailyNotifyScheduler.enqueueNext(applicationContext)
        store.setLastNotifyCheckAt(System.currentTimeMillis())

        val attempt = inputData.getInt(DailyNotifyScheduler.KEY_ATTEMPT, 0)
        // 拉最新总览:networkOnly 探测 —— 必须真实打网络(绕过内存缓存与磁盘兜底),
        // 断网/服务端故障一律失败,与「当日尚未生成」(null)同样走档内补查,不区分;
        // 绝不能把盘上旧数据当新批次发通知
        val json = runCatching { ArchiveHttpClient.fetchLatestOverview(networkOnly = true) }.getOrNull()
        if (json == null) {
            DailyNotifyScheduler.enqueueRetry(applicationContext, attempt + 1)
            return Result.success()
        }

        val generatedAt = json.optLong("generatedAt", 0L)
        val lastNotified = store.lastNotifiedOverviewAt()
        when {
            // 新批次且今天(北京时间)还没发过 → 发通知并记录指纹
            generatedAt > lastNotified && !DailyNotifyScheduler.isSameBeijingDay(lastNotified) -> {
                val digest = json.optString("digest").orEmpty().trim().takeIf { it.isNotEmpty() }
                DailyUpdateNotifier.post(applicationContext, digest)
                store.setLastNotifiedOverviewAt(generatedAt)
            }
            // 新批次但今天已发过 → 静默
            generatedAt > lastNotified -> Unit
            // 批次还没推上来 → 档内补查
            else -> DailyNotifyScheduler.enqueueRetry(applicationContext, attempt + 1)
        }
        return Result.success()
    }
}
