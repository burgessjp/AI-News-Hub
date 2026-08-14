package com.peng.ainewshub.ui.webview

import android.webkit.WebView
import com.peng.ainewshub.data.AiConfig
import com.peng.ainewshub.data.TranslationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * 阅读模式 + AI 整页翻译 —— WebView 页内功能,全部经 JS 注入实现。
 *
 * 工作流程:
 *  1. [extractReaderArticle]:把 assets/readability.js(Mozilla Readability,Apache-2.0)
 *     与抽取脚本拼成一次 [WebView.evaluateJavascript] 调用,在原页 DOM 上解析出
 *     标题/署名/正文 HTML([ReaderArticle]);
 *  2. [buildReaderHtml]:把正文套进自带亮/暗双主题的干净模板,由调用方
 *     `loadDataWithBaseURL(原 URL + [READER_SENTINEL], …)` 渲染——baseUrl 带哨兵
 *     fragment,WebView 回调里凭 URL 后缀即可识别"当前是阅读页"(图片等相对路径
 *     仍按原 URL 正常解析,fragment 不参与);
 *  3. 翻译:[extractBlockTexts] 取出阅读页所有文本块 → 逐块调
 *     [TranslationRepository],译文**不改写页面 DOM**,由调用方在底部弹层
 *     (ModalBottomSheet)里与原文对照展示。
 */

/** 阅读页 baseUrl 的哨兵 fragment。WebView 各回调的 url 参数原样保留它,据此判定阅读模式。 */
const val READER_SENTINEL = "#ainewshub-reader"

/** 阅读页内参与翻译的文本块选择器(抽取与写回必须用同一选择器,保证下标对齐)。 */
private const val BLOCK_SELECTOR =
    "#ainewshub-reader h1,#ainewshub-reader h2,#ainewshub-reader h3,#ainewshub-reader h4," +
        "#ainewshub-reader p,#ainewshub-reader li,#ainewshub-reader blockquote,#ainewshub-reader figcaption"

/** 单次整页翻译的原文总字符上限:超出部分的块保留原文,防长文烧光额度。 */
private const val MAX_TRANSLATE_CHARS = 12000

/** 翻译并发批大小:每批块数,批间渐进写回,用户能看到逐段变中文。 */
private const val TRANSLATE_BATCH = 4

/** Readability 抽取结果。contentHtml 为 Readability 清洗后的正文 HTML(可直接渲染)。 */
data class ReaderArticle(
    val title: String,
    val byline: String,
    val contentHtml: String
)

/**
 * 挂起版 [WebView.evaluateJavascript]。返回 JS 完成值的 JSON 编码
 * (字符串结果带引号;null 结果为 "null"),解析交给各调用方。
 */
suspend fun evaluateJs(webView: WebView, js: String): String? =
    suspendCancellableCoroutine { cont ->
        webView.post {
            webView.evaluateJavascript(js) { result -> cont.resume(result) }
        }
    }

/**
 * 在当前页 DOM 上跑 Readability,解析出正文。返回 null 表示该页不是文章页
 * (或页面结构不兼容),调用方据此提示「未能提取正文」。
 *
 * @param readabilityJs assets/readability.js 的文件内容(与抽取脚本拼接后一次性注入)
 */
suspend fun extractReaderArticle(webView: WebView, readabilityJs: String): ReaderArticle? {
    val extractJs = """
        ;(function(){
          try {
            var article = new Readability(document.cloneNode(true)).parse();
            if (!article || !article.content) return null;
            return JSON.stringify({
              title: article.title || '',
              byline: article.byline || '',
              content: article.content
            });
          } catch (e) { return null; }
        })();
    """.trimIndent()
    val raw = evaluateJs(webView, readabilityJs + extractJs) ?: return null
    // 解码 + JSON 解析挪到 Default 线程:evaluateJavascript 回调在主线程,长文正文
    // 可达数百 KB,主线程解析会卡住「阅读模式」点击瞬间
    return withContext(Dispatchers.Default) {
        // evaluateJavascript 的返回值是 JSON 编码:字符串结果外面还包一层引号,先解包再解析
        val inner = (JSONTokener(raw).nextValue() as? String) ?: return@withContext null
        val obj = runCatching { JSONObject(inner) }.getOrNull() ?: return@withContext null
        val content = obj.optString("content")
        if (content.isBlank()) return@withContext null
        ReaderArticle(
            title = obj.optString("title"),
            byline = obj.optString("byline"),
            contentHtml = content
        )
    }
}

/**
 * 阅读页 HTML 模板。
 *
 * 关键:`color-scheme: light dark` meta + `@media (prefers-color-scheme: dark)`。
 * WebView 算法深色检测到页面自带暗色主题后会改用页面自身样式,不再叠加算法处理,
 * 避免「模板已暗 + 算法再暗」的双重变暗。正文字号不另做缩放——WebView
 * `settings.textZoom`(跟随 App 字号档位)对 loadData 内容同样生效。
 */
fun buildReaderHtml(article: ReaderArticle): String {
    val bylineBlock = if (article.byline.isNotBlank()) {
        "<div class=\"byline\">${htmlEscape(article.byline)}</div>"
    } else {
        ""
    }
    return """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>${htmlEscape(article.title)}</title>
<style>
:root { color-scheme: light dark; --fg:#1b1b1f; --bg:#ffffff; --muted:#5f5f66;
        --line:rgba(0,0,0,.14); --accent:#003EC7; --code-bg:rgba(0,0,0,.06); }
@media (prefers-color-scheme: dark) {
  :root { --fg:#e4e1e6; --bg:#141218; --muted:#a5a1ab;
          --line:rgba(255,255,255,.18); --accent:#8fafff; --code-bg:rgba(255,255,255,.10); }
}
body { margin:0; padding:20px 22px 56px; background:var(--bg); color:var(--fg);
       font:16px/1.8 -apple-system,"PingFang SC","Noto Sans CJK SC",system-ui,sans-serif;
       word-break:break-word; }
#ainewshub-reader { max-width:680px; margin:0 auto; }
#ainewshub-reader h1 { font-size:1.45em; line-height:1.4; }
#ainewshub-reader h2,#ainewshub-reader h3,#ainewshub-reader h4 { line-height:1.4; }
#ainewshub-reader .byline { color:var(--muted); font-size:.85em; margin:-0.6em 0 1.6em; }
#ainewshub-reader img,#ainewshub-reader video { max-width:100%; height:auto; border-radius:8px; }
#ainewshub-reader a { color:var(--accent); text-decoration:none; }
#ainewshub-reader pre { overflow-x:auto; padding:12px 14px; background:var(--code-bg);
                    border-radius:10px; font-size:.88em; }
#ainewshub-reader code { font-family:ui-monospace,Menlo,monospace; }
#ainewshub-reader blockquote { margin:1em 0; padding-left:1em;
                           border-left:3px solid var(--line); color:var(--muted); }
</style>
</head>
<body>
<main id="ainewshub-reader">
<h1>${htmlEscape(article.title)}</h1>
$bylineBlock
${article.contentHtml}
</main>
</body>
</html>"""
}

/** 取阅读页全部文本块(innerText),与 [applyTranslations] 共用 [BLOCK_SELECTOR]。 */
suspend fun extractBlockTexts(webView: WebView): List<String>? {
    val js = """
        (function(){
          var els = document.querySelectorAll('$BLOCK_SELECTOR');
          var arr = [];
          for (var i = 0; i < els.length; i++) arr.push(els[i].innerText || '');
          return JSON.stringify(arr);
        })();
    """.trimIndent()
    val raw = evaluateJs(webView, js) ?: return null
    // 与 extractReaderArticle 同理:块文本合计可达几十 KB,解析挪到 Default 线程
    return withContext(Dispatchers.Default) {
        // evaluateJavascript 的返回值是 JSON 编码:字符串结果外面还包一层引号,先解包再解析
        val inner = (JSONTokener(raw).nextValue() as? String) ?: return@withContext null
        val arr = runCatching { JSONArray(inner) }.getOrNull() ?: return@withContext null
        List(arr.length()) { arr.optString(it) }
    }
}

/**
 * 整页翻译:逐块调 [TranslationRepository.translate](自带 sha256 缓存与用量统计),
 * 每批 [TRANSLATE_BATCH] 块并发,批间经 [onBatch] 回传当前完整结果数组,
 * 调用方据此渐进刷新翻译弹层,用户能看到逐段出译文。
 *
 * 跳过策略:空白块、仓库判定的过短内容([com.peng.ainewshub.data.ShortContentException]
 * 等失败)以及总字符超过 [MAX_TRANSLATE_CHARS] 之后的块,一律保留原文(结果为 null)。
 *
 * @param onBatch 每批完成后的回调:(当前结果数组, 已处理块数, 待翻译总块数)
 * @return 与 [texts] 等长的译文数组,null 表示该块未翻译(保留原文)
 */
suspend fun translateReaderBlocks(
    repo: TranslationRepository,
    config: AiConfig,
    texts: List<String>,
    onBatch: (partial: List<String?>, done: Int, total: Int) -> Unit
): List<String?> {
    val results = arrayOfNulls<String>(texts.size)
    // 预算内的可翻译块下标:非空且累计字符不超限
    val budget = run {
        val indices = mutableListOf<Int>()
        var chars = 0
        texts.forEachIndexed { i, t ->
            if (t.isNotBlank() && chars < MAX_TRANSLATE_CHARS) {
                indices += i
                chars += t.length
            }
        }
        indices
    }
    if (budget.isEmpty()) return results.toList()

    var done = 0
    try {
        // 批量翻译用 deferPersist:每条只更新内存副本,避免 N 条全量重写缓存文件的
        // 写放大(O(N²) → O(N));整个流程结束后统一 flush 一次。
        supervisorScope {
            budget.chunked(TRANSLATE_BATCH).forEach { batch ->
                batch.map { i ->
                    async(Dispatchers.Default) {
                        results[i] = repo.translate(texts[i], config, deferPersist = true).getOrNull()
                    }
                }.awaitAll()
                done += batch.size
                onBatch(results.toList(), done, budget.size)
            }
        }
    } finally {
        // 无论成功还是取消,把已翻译的缓存落盘(进程被杀前兜底)
        runCatching { repo.flush() }
    }
    return results.toList()
}

private fun htmlEscape(text: String): String = buildString(text.length) {
    text.forEach { c ->
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}
