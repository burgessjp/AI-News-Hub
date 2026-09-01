package com.peng.ainewshub.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * App 更新检查 —— 查 GitHub Releases,与本地 versionName 逐段比较。
 *
 * 发版链路(docs/release.md):打 tag 触发 GitHub Actions 出 APK 到 GitHub Release,
 * 不上架商店 —— 用户感知新版本的唯一途径就是本检查(关于页手动触发)。命中新版本后
 * 经 [UpdateDownloader] 应用内直接下载 APK(后台由 [UpdateDownloadService] 承载)。
 *
 * 更新说明数据流:release.yml 从 CHANGELOG.md 提取对应版本节写入 Release body
 * (单一真相源)→ 本检查聚合所有「比当前新」的 Release body(跨版本更新时一次看全)
 * → 弹窗复用 [parseChangelog] 的解析渲染。历史 Release 的自动生成 body 解析不出
 * 条目时退纯文本展示,再不济不显示 —— 不留空白。
 *
 * 约束与取舍:
 *  - GitHub 匿名 API 限频 60 次/小时,仅手动触发,无压力;
 *  - 任何失败(网络/限频/解析)一律返回 null 不抛错 —— 「已是最新」与「检查失败」
 *    对用户可行动性相同(都无事可做),失败弹窗反而制造焦虑;
 *  - 版本比较:按 '.' 分段转数字逐段比;任一段非数字视为解析失败 → 跳过该
 *    Release(宁可不提示,不误报);
 *  - 用列表接口而非 /releases/latest:一次性拿到「当前 → 最新」之间的全部
 *    Release(拉平跨版本说明),请求次数不变;预发布(prerelease)剔除,与
 *    /latest 的语义对齐。
 */
object UpdateChecker {

    /** 发版仓库(响应数组内每项的 assets 带 APK 直链,html_url 是无资产时的网页兜底)。 */
    private const val RELEASES_LIST_URL =
        "https://api.github.com/repos/burgessjp/AI-News-Hub/releases?per_page=20"

    private const val RELEASES_PAGE_URL =
        "https://github.com/burgessjp/AI-News-Hub/releases/latest"

    /** 单个版本的更新说明(Release body 原文,CHANGELOG.md 版本节格式)。 */
    data class UpdateNote(
        val version: String, // 去掉 v 前缀,如 "1.2"
        val markdown: String // Release body(CHANGELOG 版本节,空 body 为空串)
    )

    /** 新版本信息(检查命中时返回)。 */
    data class UpdateInfo(
        val version: String,      // 最新可用版本(去 v 前缀,如 "1.2")
        val releaseUrl: String,   // 最新版 Release 页(无 APK 资产时的网页兜底)
        val downloadUrl: String?, // 最新版 APK 资产直链(browser_download_url;未挂资产时为 null)
        val notes: List<UpdateNote> // 更新说明,新 → 旧(覆盖当前之后的所有已发布版本)
    )

    /**
     * 检查更新。
     * @param currentVersion 本地 versionName(如 "1.2")
     * @return 有新版本返回 [UpdateInfo];已是最新或任何失败返回 null
     */
    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(RELEASES_LIST_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", HttpClients.DEFAULT_BROWSER_UA)
                .build()
            HttpClients.base.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                parseReleases(body, currentVersion)
            }
        }.getOrNull()
    }

    /**
     * 纯解析(无 IO,单测直接钉):Release 列表 JSON → [UpdateInfo]。
     *
     * 规则:剔除非正式发布(prerelease)与不比 [currentVersion] 新的版本,按版本
     * 新 → 旧排序;无符合条件的 Release 返回 null。任何一条的版本号解析失败只
     * 跳过该条,不整体失败。
     */
    internal fun parseReleases(bodyJson: String, currentVersion: String): UpdateInfo? {
        val releases = runCatching { JSONArray(bodyJson) }.getOrNull() ?: return null

        data class Entry(
            val version: String,
            val releaseUrl: String,
            val apkUrl: String?,
            val body: String
        )

        val newer = (0 until releases.length())
            .mapNotNull { releases.optJSONObject(it) }
            .filterNot { it.optBoolean("prerelease") }
            .mapNotNull { obj ->
                val tag = obj.optString("tag_name").removePrefix("v").trim()
                if (tag.isBlank() || compareVersions(tag, currentVersion) <= 0) return@mapNotNull null
                // 发版流水线(release.yml)固定挂单个 app-release.apk 资产;
                // 仍按「取第一个 .apk 资产」解析,资产改名不脆断
                val apkUrl = obj.optJSONArray("assets")?.run {
                    (0 until length()).firstNotNullOfOrNull { i ->
                        optJSONObject(i)?.takeIf {
                            it.optString("name").endsWith(".apk", ignoreCase = true)
                        }?.optString("browser_download_url")?.ifBlank { null }
                    }
                }
                Entry(
                    version = tag,
                    releaseUrl = obj.optString("html_url").ifBlank { RELEASES_PAGE_URL },
                    apkUrl = apkUrl,
                    body = obj.optString("body").trim()
                )
            }
            .sortedWith { a, b -> compareVersions(b.version, a.version) }

        if (newer.isEmpty()) return null
        val newest = newer.first()
        // 空说明(历史 Release 未写 body)不进列表:弹窗只为「有内容可展示」的版本渲染
        val notes = newer
            .filter { it.body.isNotBlank() }
            .map { UpdateNote(it.version, it.body) }
        return UpdateInfo(
            version = newest.version,
            releaseUrl = newest.releaseUrl,
            downloadUrl = newest.apkUrl,
            notes = notes
        )
    }

    /** 逐段数字比较:a > b 返回正数、a < b 负数、相等 0;任一段解析失败返回 0(视为不可比)。 */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: return 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: return 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
