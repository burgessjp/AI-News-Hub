package com.peng.ainewshub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * App 更新检查 —— 查 GitHub Releases 最新版本,与本地 versionName 逐段比较。
 *
 * 发版链路(docs/release.md):打 tag 触发 GitHub Actions 出 APK 到 GitHub Release,
 * 不上架商店 —— 用户感知新版本的唯一途径就是本检查(关于页手动触发)。
 *
 * 约束与取舍:
 *  - GitHub 匿名 API 限频 60 次/小时,仅手动触发,无压力;
 *  - 任何失败(网络/限频/解析)一律返回 null 不抛错 —— 「已是最新」与「检查失败」
 *    对用户可行动性相同(都无事可做),失败弹窗反而制造焦虑;
 *  - 版本比较:按 '.' 分段转数字逐段比;任一段非数字视为解析失败 → 无更新
 *    (宁可不提示,不误报)。
 */
object UpdateChecker {

    /** 发版仓库(release 页即 APK 下载页;WebView 内点资产链接走 DownloadManager 下载)。 */
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/burgessjp/AI-News-Hub/releases/latest"

    private const val RELEASES_PAGE_URL =
        "https://github.com/burgessjp/AI-News-Hub/releases/latest"

    /** 新版本信息(检查命中时返回)。 */
    data class UpdateInfo(
        val version: String,    // 去掉 v 前缀,如 "1.2.2"
        val releaseUrl: String, // Release 页(「去下载」打开)
        val notes: String       // 更新说明(body,可为空)
    )

    /**
     * 检查更新。
     * @param currentVersion 本地 versionName(如 "1.2.2")
     * @return 有新版本返回 [UpdateInfo];已是最新或任何失败返回 null
     */
    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", HttpClients.DEFAULT_BROWSER_UA)
                .build()
            HttpClients.base.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val root = JSONObject(body)
                val tag = root.optString("tag_name").removePrefix("v").trim()
                if (tag.isBlank() || !isNewer(tag, currentVersion)) null
                else UpdateInfo(
                    version = tag,
                    releaseUrl = root.optString("html_url").ifBlank { RELEASES_PAGE_URL },
                    notes = root.optString("body").trim()
                )
            }
        }.getOrNull()
    }

    /** 逐段数字比较:a 严格大于 b 才算新版本;任一段解析失败整体视为无更新。 */
    private fun isNewer(a: String, b: String): Boolean {
        val pa = a.split('.').map { it.toIntOrNull() ?: return false }
        val pb = b.split('.').map { it.toIntOrNull() ?: return false }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
