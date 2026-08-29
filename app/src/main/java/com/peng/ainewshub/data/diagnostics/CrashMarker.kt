package com.peng.ainewshub.data.diagnostics

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 最近一次崩溃的本地标记 —— [DiagnosticsLog.buildReport]「最近崩溃」小节的数据源。
 *
 * 零遥测立场下的崩溃可见性:不接任何崩溃上报 SDK,只在默认
 * UncaughtExceptionHandler 链上垫一层写盘,把崩溃现场存进应用私有目录
 * `filesDir/last_crash.txt`。选 filesDir 而非 cacheDir:CacheManager「清理缓存」
 * 会整删 cacheDir,诊断数据必须在那之后仍可导出。数据只在本机,用户在
 * 设置 → 诊断信息 主动复制/分享才离开设备。
 *
 * 要点:
 *  - 崩溃时进程将死,不能走协程:同步阻塞写,全部 runCatching 兜底,
 *    写失败也绝不吞掉真正的崩溃流程;
 *  - 写完必须委托 previous handler,保系统默认崩溃行为(进程退出/系统对话框);
 *  - 只保留最近一次(新崩溃直接覆盖),文件内时间戳可判断新鲜度;
 *  - ANR 不在 UncaughtExceptionHandler 覆盖范围,明确不支持。
 */
object CrashMarker {

    private const val FILE_NAME = "last_crash.txt"

    /** 单次崩溃现场写入上限:OOM 等巨型栈截断,防止占满私有目录。 */
    private const val MAX_CHARS = 16 * 1024

    private val handler = CrashHandler()

    @Volatile
    private var file: File? = null

    /** 安装崩溃钩子(Application.onCreate 最先调用;幂等,重复调用不重复垫层)。 */
    fun install(context: Context) {
        file = File(context.applicationContext.filesDir, FILE_NAME)
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current === handler) return
        handler.previous = current
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    /** 读最近一次崩溃现场;未安装/无记录/读失败返回 null(供诊断报告组装)。 */
    internal fun read(): String? = runCatching {
        file?.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 删除崩溃标记(诊断「清除记录」)。 */
    internal fun clear() {
        runCatching { file?.delete() }
    }

    private fun write(t: Thread, e: Throwable) {
        val f = file ?: return
        val head = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()) +
            "  thread=${t.name}\n"
        val stack = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val body = if (head.length + stack.length > MAX_CHARS) {
            head + stack.take(MAX_CHARS - head.length) + "\n…(已截断)"
        } else {
            head + stack
        }
        f.writeText(body)
    }

    /** 垫在默认 handler 之前的写盘层;委托链保系统收尾。 */
    private class CrashHandler : Thread.UncaughtExceptionHandler {

        @Volatile
        var previous: Thread.UncaughtExceptionHandler? = null

        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching { write(t, e) }
            // 委托本身失败也不许吞崩溃:再包一层,交系统默认 handler 收尾
            runCatching { previous?.uncaughtException(t, e) }
        }
    }
}
