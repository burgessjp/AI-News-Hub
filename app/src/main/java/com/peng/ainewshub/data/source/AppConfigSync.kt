package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.PipelineSchedule
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * 远程应用配置同步 —— 数据仓库根级 `app_config.json` → App 运行时配置的唯一入口。
 *
 * 当前仅承载批次时刻表:`{"batch_slots": ["08:00", "18:00"]}`(北京时间,
 * 人工维护;调整流水线批次时间时须同步改该文件,见 docs/agents/pipeline.md)。
 * schema 向前兼容:未知字段忽略;缺失 batch_slots 视为「暂无远程配置」,保持
 * [PipelineSchedule] 当前生效表(内置默认或上次成功值)。
 *
 * 触发点(都经 ArchiveJsonCache 的 2 分钟 TTL + 并发去重,重复调用廉价):
 *  - 每次进程启动(ui/nav 的 AppConfigSyncHost,进程级闸门);
 *  - 每日通知 Worker 运行前(覆盖进程被杀后 WorkManager 唤醒的无 UI 入口)。
 *
 * 失败一律静默 —— 配置拉不到不是错误,内置默认表兜底,不打扰用户(对齐冷启动
 * 探测类动作的既有哲学,如 NewDataPromptHost)。
 */
internal object AppConfigSync {

    /** `batch_slots` 条目格式:`H:mm` / `HH:mm`(单位数小时与分钟都兼容)。 */
    private val SLOT_PATTERN = Regex("""^(\d{1,2}):(\d{1,2})$""")

    /**
     * 拉取并应用远程配置;任何失败静默保持现状。
     *
     * @return true 表示生效批次表发生了变化(调用方可据此重排依赖时刻表的任务,
     *         如每日通知的 WorkManager 检查链)
     */
    suspend fun refresh(): Boolean {
        val json = try {
            ArchiveHttpClient.fetchAppConfig()
        } catch (e: CancellationException) {
            // 调用方作用域销毁的取消要放行重抛,不能当「拉取失败」吞掉
            // (破坏结构化取消语义,对齐 NewDataPromptHost 的处理)
            throw e
        } catch (e: Exception) {
            null // 断网 / HTTP 错误 / JSON 解析失败:静默保持当前值
        } ?: return false
        val slots = parseBatchSlots(json) ?: return false
        return PipelineSchedule.applyBatchSlots(slots)
    }

    /**
     * 解析 `batch_slots` 为 (hour, minute) 列表(北京时间,保持原始顺序,
     * 去重排序由 [PipelineSchedule.applyBatchSlots] 归一)。
     * 字段缺失 / 空数组 / 任一条目格式或范围非法(hour 0..23、minute 0..59)
     * 整体拒绝返回 null —— 宁可不应用也不部分采纳。
     */
    internal fun parseBatchSlots(json: JSONObject): List<Pair<Int, Int>>? {
        val arr = json.optJSONArray("batch_slots") ?: return null
        if (arr.length() == 0) return null
        val result = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until arr.length()) {
            val match = SLOT_PATTERN.find(arr.optString(i).trim()) ?: return null
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            if (hour !in 0..23 || minute !in 0..59) return null
            result += hour to minute
        }
        return result
    }
}
