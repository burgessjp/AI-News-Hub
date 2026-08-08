#!/usr/bin/env python3
"""
数据流水线共享的 AI 调用客户端(OpenAI 兼容 `/v1/chat/completions`)。

`ai_summary.py`(单源要点)与 `overview_summary.py`(跨源总览)原本各持一份
逐字重复的「请求 + JSON 解析 + 重试」逻辑(~70 行 ×2),且各自裸 `requests.post`
不复用连接、无 429/限流感知、把 deepseek 专有的 `thinking:{type:disabled}`
硬编码进 body(破坏「OpenAI 兼容」可移植性)。本模块统一收口这些问题:

  - 模块级 `requests.Session` 复用 TCP/TLS(单轮跑批 9 次调用打到同一域名,
    不再每次握手)。
  - `call_llm(system, user, *, timeout, temperature, expect)` 一次封装请求 +
    取 content + 剥 markdown 围栏 + json.loads + 基础类型校验,返回原生对象。
  - `thinking:{type:disabled}` 由环境变量 `AI_NEWS_HUB_AI_DISABLE_THINKING`
    开关(默认 "1" 保持现状);关 deepseek 思维链以回到秒级响应且省 token,
    换标准 OpenAI/Anthropic 端点时设为 "0" 即不注入该专有字段。
  - 传输层 429/503 感知重试:读 `Retry-After`(缺省指数退避),单次调用内部
    最多快速重试 2 次;业务层的 3 次大重试仍由 summarize_source / generate_overview
    持有(两层重试:传输层快速 + 业务层长退避)。

只做「发一次请求、拿到解析后的 Python 对象」这一件事;具体的业务校验
(过滤空条目 / 校验 items 数组 / 截断)留给调用方,避免把两套差异较大的
校验语义塞进通用函数。配置入口(环境变量名 + config_ready)仍由
`ai_summary.py` 单点定义,本模块经调用方传入 base_url/model/api_key。
"""

import json
import os
import re
import sys
import time

import requests


# 是否注入 deepseek 专有的 thinking:{type:disabled}。
# 默认 "1"(开):与历史行为一致 —— 关掉 deepseek-v4-flash 默认的 thinking=high,
# 让信息归纳类请求从 60-100s 回到秒级,不再撞 read timeout。换标准 OpenAI/Anthropic
# 端点时设 "0"(关)即可,此时不注入该字段,保持「OpenAI 兼容」可移植性。
ENV_DISABLE_THINKING = "AI_NEWS_HUB_AI_DISABLE_THINKING"

# 传输层快速重试:仅针对瞬时不可用(429 限流 / 503 过载)。业务层的大重试仍由
# summarize_source / generate_overview 各自持有(它们负责解析失败、空结果等业务异常)。
_HTTP_RETRY_STATUSES = (429, 503)
_HTTP_MAX_RETRIES = 2
_HTTP_BACKOFF_BASE = 2  # 缺省退避基数(秒):2s, 4s

# 模块级共享 Session:复用连接池 / TLS 会话。requests.Session 在只发请求、
# 不跨线程修改 cookie/headers 的用法下线程安全(本模块每次调用显式传 headers)。
_SESSION = requests.Session()


def _want_disable_thinking():
    """读环境变量决定是否注入 thinking:{type:disabled}。
    空值 / "1" / "true" → 注入(默认行为);"0" / "false" → 不注入。"""
    val = (os.getenv(ENV_DISABLE_THINKING) or "").strip().lower()
    return val not in ("0", "false", "no", "off")


def _build_endpoint(base_url):
    """拼 `/v1/chat/completions` 端点。base_url 含 /v1 时不重复拼接
    (避免 base_url 填到 https://api.x.com/v1 时变成 .../v1/v1/...)。"""
    trimmed = base_url.rstrip("/")
    if trimmed.endswith("/v1"):
        return trimmed + "/chat/completions"
    return trimmed + "/v1/chat/completions"


def _strip_fence_and_extract(content, expect):
    """
    把 AI 返回的文本剥掉 markdown 围栏(```json ... ``` / ``` ... ```),
    再截取最外层括号之间的内容做 json.loads。

    expect="array" → 截首个 '[' 到末个 ']';
    expect="object" → 截首个 '{' 到末个 '}'。

    模型常把 JSON 包在代码块里、或在首尾混入解释性文字,这里统一兜底。
    返回解析后的原生 Python 对象(list / dict);解析失败抛 RuntimeError。
    """
    if not content:
        raise RuntimeError("AI 响应 content 为空")
    stripped = re.sub(r"^```(?:json)?\s*", "", content.strip(),
                      flags=re.IGNORECASE).rstrip("`").strip()
    open_ch, close_ch, kind = ("[", "]", "数组") if expect == "array" else ("{", "}", "对象")
    start, end = stripped.find(open_ch), stripped.rfind(close_ch)
    if start == -1 or end == -1 or end <= start:
        raise RuntimeError(f"AI 响应未找到 JSON {kind}:{content[:120]}")
    payload = stripped[start:end + 1]
    try:
        return json.loads(payload)
    except json.JSONDecodeError as e:
        raise RuntimeError(f"AI 响应 JSON 解析失败:{e}:{content[:120]}")


def _sleep_for_status(resp):
    """从 429/503 响应算退避时长(秒):优先 Retry-After 头,缺省返回 0(由调用方指数退避)。
    返回 sleep_seconds(float;0 表示无 Retry-After 头,调用方自行按重试次数退避)。"""
    sleep_s = 0
    retry_after = resp.headers.get("Retry-After") or resp.headers.get("retry-after")
    if retry_after:
        try:
            sleep_s = float(retry_after)
        except (TypeError, ValueError):
            sleep_s = 0
    return sleep_s


def call_llm(system_prompt, user_prompt, base_url, model, api_key, *,
             timeout, temperature, expect="array"):
    """
    发起一次 OpenAI 兼容 `/v1/chat/completions` 请求,返回解析后的原生对象
    (expect="array" → list;expect="object" → dict)。

    失败抛异常(由调用方的业务层重试捕获)。含传输层 429/503 快速重试。
    业务校验(过滤空条目 / 校验 items)由调用方在拿到返回值后自行处理。
    """
    url = _build_endpoint(base_url)
    body = {
        "model": model,
        "temperature": temperature,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
    }
    if _want_disable_thinking():
        # deepseek-v4-flash 默认 thinking=enabled + effort=high,信息归纳类请求
        # 会因思维链耗时 60-100s 撞穿 read timeout;显式关闭回到秒级且更省 token。
        body["thinking"] = {"type": "disabled"}
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    # 传输层快速重试:仅 429/503(瞬时不可用),其余错误(含 4xx 业务错、超时)直接抛。
    last_resp = None
    for http_attempt in range(_HTTP_MAX_RETRIES + 1):
        resp = _SESSION.post(url, json=body, headers=headers, timeout=timeout)
        if resp.status_code not in _HTTP_RETRY_STATUSES:
            break
        last_resp = resp
        if http_attempt < _HTTP_MAX_RETRIES:
            wait = _sleep_for_status(resp) or (_HTTP_BACKOFF_BASE ** (http_attempt + 1))
            print(f"[AI-HTTP] {resp.status_code} 限流/过载,{wait:.0f}s 后重试"
                  f"(第 {http_attempt + 1}/{_HTTP_MAX_RETRIES} 次)", file=sys.stderr)
            time.sleep(wait)
    resp.raise_for_status()
    data = resp.json()
    choices = data.get("choices") or []
    if not choices:
        raise RuntimeError(f"AI 响应无 choices:{str(data)[:120]}")
    content = (((choices[0].get("message") or {}).get("content")) or "").strip()
    parsed = _strip_fence_and_extract(content, expect)
    # 基础类型校验:array→非空 list,object→非空 dict。业务语义校验留给调用方。
    if expect == "array":
        if not isinstance(parsed, list) or not parsed:
            raise RuntimeError(f"AI 响应非非空数组:{content[:120]}")
    else:
        if not isinstance(parsed, dict) or not parsed:
            raise RuntimeError(f"AI 响应非非空对象:{content[:120]}")
    return parsed
