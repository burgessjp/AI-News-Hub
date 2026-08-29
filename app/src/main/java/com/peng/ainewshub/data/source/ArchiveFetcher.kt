package com.peng.ainewshub.data.source

import com.peng.ainewshub.data.AppException
import com.peng.ainewshub.data.net.HttpClients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 归档取数的网络 + 磁盘兜底骨架 —— [ArchiveHttpClient] 及各缓存组件共用。
 *
 * 职责:
 *  - 持有唯一的 gitcode 客户端(共享 base 派生 + 内存 CookieJar 抗 WAF);
 *  - [fetchJsonWithDiskFallback]:网络取 JSON → write-through 落盘 → 传输层失败读盘兜底;
 *  - [offlineMode]:进程内离线状态流,任一请求网络成功复位、走盘上兜底时置 true。
 *
 * 客户端配置对齐 App 其余 Repository:connect 15s / read 20s / 跟随重定向。
 */
internal object ArchiveFetcher {

    private val client by lazy {
        // 共享 base 派生:连接池/线程池与全 App 复用,仅覆盖 cookieJar
        HttpClients.base.newBuilder()
            // gitcode raw 背后是华为云 WAF,会下发 HWWAFSESID 等会话 cookie;不带 cookie
            // 的裸请求容易被 WAF 判为可疑流量返回 403。配 cookieJar 让客户端像浏览器一样
            // 记住并回传会话 cookie,降低被拦概率。内存存储(进程级,随 App 生命周期)。
            .cookieJar(InMemoryCookieJar())
            .build()
    }

    /**
     * 离线兜底状态(进程内):true = 最近一次取数走了盘上旧数据(网络失败但兜底命中)。
     * 任一请求网络成功后复位为 false。UI(AiNewsHubApp)订阅它在离线切换时提示用户
     * 「正在展示缓存数据」。进程级内存态,不做持久化。
     */
    private val _offlineMode = MutableStateFlow(false)

    /** 公开只读离线状态(经 [ArchiveHttpClient.offlineMode] 对外)。 */
    val offlineMode: StateFlow<Boolean> = _offlineMode.asStateFlow()

    /**
     * 网络取 JSON + 磁盘兜底的统一骨架(index / 快照 / 根级独立文件共用):
     *  1. 网络成功 → 解析 → write-through 落盘(静默失败)→ 复位 [offlineMode]
     *  2. 传输层失败([IOException]:连不上/DNS/读超时)→ 读盘兜底:命中且未过期
     *     ([ArchiveDiskCache] 7 天 TTL)则置 [offlineMode] 为 true 并返回盘上旧数据;
     *     未命中或盘上数据损坏则抛回原异常(走原错误态)
     *  3. [tolerateMissing] 语义不变:404 返回 null,不落盘也不兜底(「成功才写」的
     *     文件尚未生成时盘上本就不会有)
     *
     * 不兜底的失败:HTTP 层错误(非 2xx、空响应,服务端已应答)抛 [AppException.Network]、
     * 解析失败抛 [AppException.ServerError] —— 服务端故障不能伪装成「离线」拿旧数据
     * 顶上,须如实走 Error 态。
     *
     * [allowDiskFallback] 为 false(通知自查/冷启动弹窗的 networkOnly 探测)时:
     * 传输层失败也不读盘,直接抛 —— 调用方拿「失败」当信号走补查/放弃,绝不把盘上
     * 旧数据当成新批次。
     *
     * @param cacheKey 磁盘缓存键(与内存缓存同键:index.json / source/relPath / 根级文件名)
     */
    suspend fun fetchJsonWithDiskFallback(
        cacheKey: String,
        url: String,
        hint: String,
        tolerateMissing: Boolean = false,
        allowDiskFallback: Boolean = true
    ): JSONObject? {
        val text = try {
            getRaw(url, hint, tolerateMissing) ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // 传输层失败(连不上):读盘兜底(盘上是上次网络成功时落下的旧数据);
            // networkOnly 探测不兜底,失败即抛
            if (!allowDiskFallback) throw e
            val disk = withContext(Dispatchers.IO) { ArchiveDiskCache.read(cacheKey) }
                ?: throw e
            return runCatching { JSONObject(disk) }
                .map { parsed -> parsed.also { _offlineMode.value = true } }
                .getOrElse { throw e }
        }
        // AppException(HTTP 错误/空响应)与其他非预期异常不捕获,直接向上抛
        val parsed = runCatching { JSONObject(text) }
            .getOrElse { throw AppException.ServerError() }
        withContext(Dispatchers.IO) { ArchiveDiskCache.write(cacheKey, text) }
        _offlineMode.value = false
        return parsed
    }

    /**
     * GET 一个 URL,返回响应正文;非 2xx 或空响应抛 [AppException.Network]。
     * [hint] 仅用于日志诊断(toUiError 会把原始异常记入 logcat)。
     *
     * [tolerateMissing] 为 true 时 404 → null(语义:文件尚未生成,调用方走 NoData;
     * 仅 trends.json 的「成功才写」暂态语义用),其余非 2xx 照常抛错。
     *
     * suspend 自管 [Dispatchers.IO]:所有调用方(含各缓存的 Mutex 锁内)
     * 均无需再外层切 IO,与 [HttpClients.get] 行为一致。
     */
    private suspend fun getRaw(
        url: String,
        @Suppress("UNUSED_PARAMETER") hint: String,
        tolerateMissing: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.DEFAULT_BROWSER_UA)
            .header("Accept", "application/json,text/plain,*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 && tolerateMissing -> null
                !resp.isSuccessful -> throw AppException.Network()
                else -> resp.body?.string()?.takeIf { it.isNotBlank() }
                    ?: throw AppException.Network()
            }
        }
    }
}

/**
 * 进程级内存 CookieJar —— 记住 gitcode WAF 下发的会话 cookie 并在后续请求回传。
 *
 * 仅用于 [ArchiveFetcher](gitcode raw 域名),进程内单实例,App 退出即清空。
 * 实现极简:不做过期清理(cookie 量极小,WAF 会话 cookie 短命),用 ConcurrentHashMap
 * 保证多源并发请求时的线程安全。
 *
 * 不持久化:cookie 仅用于降低 WAF 拦截概率,无登录态意义,无需跨进程保留。
 */
private class InMemoryCookieJar : CookieJar {

    private val store: MutableMap<String, MutableList<Cookie>> = ConcurrentHashMap()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val list = store.getOrPut(url.host) { mutableListOf() }
        synchronized(list) {
            // 同名 cookie 替换(更新值/有效期),新增的追加
            for (c in cookies) {
                list.removeAll { it.name == c.name }
                list.add(c)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = store[url.host] ?: return emptyList()
        return synchronized(list) {
            // 过滤掉已过期 cookie,顺带清理
            val now = System.currentTimeMillis()
            list.filter { it.expiresAt > now }.also { valid ->
                if (valid.size != list.size) list.retainAll(valid)
            }
        }
    }
}
