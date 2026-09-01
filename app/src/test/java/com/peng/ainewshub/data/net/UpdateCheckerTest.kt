package com.peng.ainewshub.data.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [UpdateChecker.parseReleases] 纯解析回归(离线,不走网络)。
 *
 * 钉住更新检查的关键语义:
 *  - 只有比当前新的正式发布才算更新(预发布剔除、旧版本忽略、坏 tag 跳过);
 *  - 跨版本更新时说明按「新 → 旧」全量聚合(用户一次看全自当前版以来的变化);
 *  - APK 直链取第一个 .apk 资产(发版流水线资产改名不脆断);
 *  - 「宁可不提示,不误报」:无有效新版本或输入坏一律 null,绝不抛错。
 */
class UpdateCheckerTest {

    /** 构造单条 Release JSON;apkUrl 传 null 表示未挂 .apk 资产。 */
    private fun release(
        tag: String,
        body: String = "",
        prerelease: Boolean = false,
        apkUrl: String? = "https://example.com/app-release.apk",
        extraAssets: List<Pair<String, String>> = emptyList()
    ): JSONObject {
        val assets = JSONArray()
        apkUrl?.let {
            assets.put(JSONObject().put("name", "app-release.apk").put("browser_download_url", it))
        }
        extraAssets.forEach { (name, url) ->
            assets.put(JSONObject().put("name", name).put("browser_download_url", url))
        }
        return JSONObject()
            .put("tag_name", "v$tag")
            .put("prerelease", prerelease)
            .put("html_url", "https://github.com/r/v$tag")
            .put("body", body)
            .put("assets", assets)
    }

    @Test
    fun `跨版本更新按新到旧聚合全部说明且资产直链取自最新版`() {
        // 故意乱序:钉住「不依赖接口返回顺序,按版本号自行排序」
        val json = JSONArray()
            .put(release("1.2", body = "### 改进\n- **B** — 说明"))
            .put(release("1.3", body = "### 新增\n- **A** — 说明", apkUrl = "https://example.com/newest.apk"))
            .put(release("1.1", body = "### 修复\n- **旧** — 不该出现"))
            .toString()

        val info = UpdateChecker.parseReleases(json, currentVersion = "1.1")!!

        assertEquals("1.3", info.version)
        assertEquals("https://example.com/newest.apk", info.downloadUrl)
        assertEquals(listOf("1.3", "1.2"), info.notes.map { it.version })
        assertEquals("### 新增\n- **A** — 说明", info.notes.first().markdown)
    }

    @Test
    fun `预发布与不比当前新的版本被剔除`() {
        val json = JSONArray()
            .put(release("2.0-beta", prerelease = true, body = "预发布不该出现"))
            .put(release("1.1", body = "与当前同代不该出现"))
            .put(release("1.0", body = "旧版本不该出现"))
            .put(release("1.2", body = "### 新增\n- **A** — 说明"))
            .toString()

        val info = UpdateChecker.parseReleases(json, currentVersion = "1.1")!!

        assertEquals("1.2", info.version)
        assertEquals(listOf("1.2"), info.notes.map { it.version })
    }

    @Test
    fun `空 body 的版本不进说明列表但仍构成更新`() {
        val json = JSONArray().put(release("1.3", body = "")).toString()

        val info = UpdateChecker.parseReleases(json, currentVersion = "1.2")!!

        assertEquals("1.3", info.version)
        assertEquals("https://example.com/app-release.apk", info.downloadUrl)
        assertEquals(emptyList<UpdateChecker.UpdateNote>(), info.notes)
    }

    @Test
    fun `版本号含非数字段的 Release 被跳过而非整体失败`() {
        val json = JSONArray()
            .put(release("next", body = "坏 tag 整条跳过"))
            .put(release("1.3", body = "### 新增\n- **A** — 说明"))
            .toString()

        val info = UpdateChecker.parseReleases(json, currentVersion = "1.2")!!

        assertEquals("1.3", info.version)
        assertEquals(listOf("1.3"), info.notes.map { it.version })
    }

    @Test
    fun `无资产时 downloadUrl 为 null 供网页兜底`() {
        val json = JSONArray().put(release("1.3", apkUrl = null)).toString()

        val info = UpdateChecker.parseReleases(json, currentVersion = "1.2")!!

        assertEquals("1.3", info.version)
        assertNull(info.downloadUrl)
    }

    @Test
    fun `多资产时取第一个 apk 资产`() {
        val json = JSONArray()
            .put(
                release(
                    "1.3",
                    extraAssets = listOf(
                        "checksums.txt" to "https://example.com/checksums.txt",
                        "other.apk" to "https://example.com/other.apk"
                    )
                )
            )
            .toString()

        val info = UpdateChecker.parseReleases(json, currentVersion = "1.2")!!

        assertEquals("https://example.com/app-release.apk", info.downloadUrl)
    }

    @Test
    fun `已是最新或输入异常一律返回 null 不抛错`() {
        val upToDate = JSONArray().put(release("1.2")).put(release("1.1")).toString()
        assertNull(UpdateChecker.parseReleases(upToDate, currentVersion = "1.2"))

        assertNull(UpdateChecker.parseReleases("not a json", currentVersion = "1.2"))
        // 对象而非数组(接口形态变化)同样视为失败
        assertNull(UpdateChecker.parseReleases(JSONObject().put("message", "rate limited").toString(), "1.2"))
        assertNull(UpdateChecker.parseReleases(JSONArray().toString(), "1.2"))
    }
}
