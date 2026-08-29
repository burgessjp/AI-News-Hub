package com.peng.ainewshub.data.diagnostics

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.peng.ainewshub.data.AppException
import com.peng.ainewshub.data.source.ArchiveHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条错误记录:时刻 + 异常类别 + 摘要(完整堆栈不落盘,控制体积且防敏感串)。 */
data class DiagEntry(val atMs: Long, val kind: String, val detail: String)

/** 诊断报告的环境信息入参(纯值,便于 JVM 直测 [formatReport] 拼装)。 */
internal data class ReportEnv(
    val generatedAtMs: Long,
    val versionName: String,
    val versionCode: Long,
    val device: String,
    val android: String,
    val language: String,
    val offline: Boolean
)

/** 环形队列容量:最近错误只留这么多条,新→旧展示。 */
private const val MAX_ENTRIES = 20

/** 单条错误摘要上限。 */
private const val DETAIL_MAX_CHARS = 200

/** 报告中「最近崩溃」小节的体积上限(完整崩溃文件最多 16KB,报告里截半即可)。 */
private const val CRASH_SECTION_MAX_CHARS = 4 * 1024

/**
 * 本地错误环形记录 + 诊断报告组装 —— 零遥测立场的排障出口。
 *
 * 采集面刻意收窄:
 *  - 只记「已冒泡到 UI 的失败」:唯一喂入点是 [com.peng.ainewshub.ui.toUiError]
 *    统一漏斗(16 个 ViewModel 出口天然全覆盖);数据层静默 runCatching 吞掉的
 *    失败有意不记(那是另一个「补日志」课题);
 *  - [AppException.NoData] 属例行「暂无数据」,无诊断价值,直接跳过;
 *  - 崩溃现场由 [CrashMarker] 单独落盘,本类只管读。
 *
 * 持久化 `filesDir/diagnostics/recent_errors.json`(org.json,禁三方序列化库):
 * 进程被系统回收后「被杀前的报错」仍在。所有读写经 [mutex] 串行,
 * record 为同步入口(漏斗非 suspend),内部丢到自有 IO 协程 fire-and-forget。
 * 报告内容恒中文(受众是开发者,与流水线内容恒中文同理),不含任何用户密钥。
 */
object DiagnosticsLog {

    private const val DIR_NAME = "diagnostics"
    private const val FILE_NAME = "recent_errors.json"

    private val mutex = Mutex()
    private val entries = ArrayDeque<DiagEntry>()

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: File? = null
    private var loaded = false

    /** 最近一次写盘任务(供 [awaitWrites] 确定性排空,见其 KDoc)。 */
    @Volatile
    private var lastWrite: Job? = null

    /** 初始化(Application.onCreate 调用,幂等;异步加载历史记录)。 */
    fun init(context: Context) {
        if (file != null) return
        synchronized(this) {
            if (file == null) {
                val f = File(File(context.applicationContext.filesDir, DIR_NAME), FILE_NAME)
                file = f
                scope.launch { mutex.withLock { loadLocked() } }
            }
        }
    }

    /**
     * 记录一条异常。atMs 仅测试注入用(断言新→旧顺序需要可控时间戳),
     * 生产路径恒为当前时刻。未初始化(App 极早期 / 纯 JVM 单测)静默丢弃。
     */
    fun record(t: Throwable, atMs: Long = System.currentTimeMillis()) {
        if (t is AppException.NoData) return
        val f = file ?: return
        val entry = DiagEntry(
            atMs = atMs,
            kind = t::class.java.simpleName ?: "Throwable",
            detail = detailOf(t)
        )
        lastWrite = scope.launch {
            mutex.withLock {
                if (!loaded) loadLocked()
                entries.addLast(entry)
                while (entries.size > MAX_ENTRIES) entries.removeFirst()
                persistLocked(f)
            }
        }
    }

    /** 当前记录快照,新→旧排序。 */
    internal suspend fun snapshot(): List<DiagEntry> = mutex.withLock {
        if (!loaded) loadLocked()
        entries.sortedByDescending { it.atMs }
    }

    /** 清空内存与磁盘上的错误记录,连同崩溃标记([CrashMarker.clear])。 */
    suspend fun clear() {
        mutex.withLock {
            entries.clear()
            runCatching { file?.takeIf { it.isFile }?.delete() }
            loaded = true
        }
        CrashMarker.clear()
    }

    /** 组装完整诊断报告(设置 → 诊断信息 展示与复制/分享的内容)。 */
    suspend fun buildReport(context: Context): String {
        val app = context.applicationContext
        val (name, code) = runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            (info.versionName ?: "unknown") to PackageInfoCompat.getLongVersionCode(info)
        }.getOrDefault("unknown" to 0L)
        val env = ReportEnv(
            generatedAtMs = System.currentTimeMillis(),
            versionName = name,
            versionCode = code,
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            language = Locale.getDefault().toLanguageTag(),
            offline = ArchiveHttpClient.offlineMode.value
        )
        val crash = CrashMarker.read()?.take(CRASH_SECTION_MAX_CHARS)
        return formatReport(env, crash, snapshot())
    }

    /** 单测专用:重置全部状态并指向新文件(null = 恢复未初始化态)。 */
    internal fun reconfigureForTest(newFile: File?) {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        entries.clear()
        file = newFile
        loaded = false
        lastWrite = null
    }

    /**
     * 单测专用:等在途写入真实完成。仅靠空取 Mutex 排队不够 —— record 的
     * launch 可能尚未被 IO 线程调度(锁上还没有排队者),测试线程会瞬间空手
     * 而归;先 join 最近一次写盘 Job 再取锁,才是确定性排空。
     */
    internal suspend fun awaitWrites() {
        lastWrite?.join()
        mutex.withLock { }
    }

    private fun loadLocked() {
        loaded = true
        val text = runCatching { file?.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return
        entries.clear()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            entries.addLast(DiagEntry(o.optLong("at"), o.optString("k"), o.optString("d")))
        }
    }

    private fun persistLocked(f: File) {
        runCatching {
            val arr = JSONArray()
            entries.forEach {
                arr.put(JSONObject().put("at", it.atMs).put("k", it.kind).put("d", it.detail))
            }
            f.parentFile?.mkdirs()
            f.writeText(arr.toString())
        }
    }

    private fun detailOf(t: Throwable): String {
        val msg = t.message?.takeIf { it.isNotBlank() } ?: "(no message)"
        val cause = t.cause?.let { c ->
            " ← ${c::class.java.simpleName}${c.message?.let { m -> ": $m" }.orEmpty()}"
        }.orEmpty()
        return (msg + cause).take(DETAIL_MAX_CHARS)
    }
}

/**
 * 纯拼装(无 Android 依赖):环境信息 + 最近崩溃 + 最近错误 → 恒中文报告文本。
 * 拆成顶层函数是为让 JVM 单测直测格式与空分支。
 */
internal fun formatReport(env: ReportEnv, lastCrash: String?, errors: List<DiagEntry>): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    return buildString {
        appendLine("AI News Hub 诊断信息")
        appendLine("生成时间: ${fmt.format(Date(env.generatedAtMs))}")
        appendLine("版本: ${env.versionName} (${env.versionCode})")
        appendLine("设备: ${env.device}")
        appendLine("系统: ${env.android}")
        appendLine("语言: ${env.language}")
        appendLine("离线兜底中: ${if (env.offline) "是" else "否"}")
        appendLine()
        appendLine("最近崩溃:")
        appendLine(lastCrash?.trim().takeIf { !it.isNullOrBlank() } ?: "无")
        appendLine()
        appendLine("最近错误(最多 $MAX_ENTRIES 条,新→旧):")
        if (errors.isEmpty()) {
            appendLine("无")
        } else {
            errors.forEach {
                appendLine("${fmt.format(Date(it.atMs))} ${it.kind}: ${it.detail}")
            }
        }
    }.trim() + "\n"
}
