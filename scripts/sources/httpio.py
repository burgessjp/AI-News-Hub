"""流水线 HTTP 基建 —— 全部抓取器共用的会话与 GET 入口。

UA / 超时对齐 App 端 OkHttp 配置 + 浏览器 UA(避免被 nginx/CF 403)。
所有源的 HTTP(含 Product Hunt 的 POST)一律经本模块的 SESSION 发出:
测试里 requests-mock 挂传输层即可全量拦截,不必逐源打桩。
"""

import requests


UA = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Mobile Safari/537.36"
)

# 对齐 App:connectTimeout 15s, readTimeout 20s, followRedirects(true)
SESSION = requests.Session()
SESSION.headers.update({"User-Agent": UA})
TIMEOUT = (15, 20)


def fetch_text(url, extra_headers=None, expect_json=False):
    """
    GET 一个 URL,返回响应正文文本。

    带 Cloudflare 挑战页检测:返回正文含 "Just a moment" 或(期望 JSON 时)
    以 '<' 开头,说明被 CF 拦截,抛 RuntimeError 而非让后续解析报含糊错误。
    对齐 App 端各 Repository 的 CF 检测套路。
    """
    headers = dict(extra_headers or {})
    resp = SESSION.get(url, headers=headers, timeout=TIMEOUT, allow_redirects=True)
    resp.raise_for_status()
    text = resp.text or ""
    if "Just a moment" in text:
        raise RuntimeError("被 Cloudflare 拦截,请稍后重试")
    if expect_json:
        stripped = text.lstrip()
        if stripped.startswith("<"):
            raise RuntimeError("被 Cloudflare 拦截(返回 HTML 而非 JSON)")
    return text
