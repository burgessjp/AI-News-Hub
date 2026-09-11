"""trend_keywords.refine_keywords_with_ai 回归(全桩:config_ready / ai_client.call_llm / time.sleep)。

钉住:配置缺失不发起调用;调用失败业务层恰重试 1 次后回退统计榜;结果校验
矩阵(数量/term/display/absorb)任一不过整批回退;成功时 absorb 合并统计、
未选满按池序补齐、候选池耗尽才允许少于 10。精修是锦上添花——一切失败
路径都必须原样保留统计回退榜且返回 False,绝不阻断主流程。
"""

import time

import trend_keywords as tk


def _entry(term, daily=None):
    daily = [0] * 11 + [1, 1, 1] if daily is None else daily
    return {
        "term": term, "display": term, "total": sum(daily),
        "daysActive": sum(1 for h in daily if h > 0), "daily": daily,
        "trend": tk._trend_of(daily),
        "items": [{"title": term, "url": f"https://x/{term}",
                   "source": "hackernews", "date": "2026-08-28"}],
    }


def _pool(terms):
    return [_entry(t) for t in terms]


def _selected(n, mutate=None):
    out = [{"term": f"t{i}", "display": f"D{i}", "absorb": []} for i in range(n)]
    return {"selected": mutate(out) if mutate else out}


def test_refine_配置缺失直接跳过不调用(monkeypatch):
    monkeypatch.setattr(tk, "config_ready", lambda: False)

    def _boom(*a, **k):
        raise AssertionError("配置未就绪不该发起 LLM 调用")

    monkeypatch.setattr(tk.ai_client, "call_llm", _boom)
    trends = {"keywords": []}
    assert tk.refine_keywords_with_ai(trends, _pool(["openai"])) is False


def test_refine_两次调用全败回退统计榜(monkeypatch):
    monkeypatch.setattr(tk, "config_ready", lambda: True)
    sleeps, attempts = [], []

    def boom(*a, **k):
        attempts.append(1)
        raise RuntimeError("超时")

    monkeypatch.setattr(tk.ai_client, "call_llm", boom)
    monkeypatch.setattr(time, "sleep", lambda s: sleeps.append(s))
    fallback = [{"term": "openai", "display": "OpenAI"}]
    trends = {"keywords": fallback}

    assert tk.refine_keywords_with_ai(trends, _pool(["openai"])) is False
    assert len(attempts) == 2      # 业务层重试恰好 1 次(传输层重试在 ai_client 内)
    assert sleeps == [10]          # 重试间隔 10s(测试里桩掉)
    assert trends["keywords"] is fallback  # 回退榜原对象原样保留


def test_refine_结果校验矩阵任一不过整批回退(monkeypatch):
    monkeypatch.setattr(tk, "config_ready", lambda: True)
    monkeypatch.setattr(time, "sleep", lambda s: None)
    pool = _pool([f"t{i}" for i in range(12)])
    bad_payloads = [
        {"selected": "not-a-list"},                                   # selected 非数组
        _selected(4),                                                 # 少于 5 个
        _selected(11),                                                # 多于 10 个
        _selected(5, lambda s: [{**s[0], "term": "ghost"}] + s[1:]),  # term 不在池
        _selected(6, lambda s: s + [dict(s[0])]),                     # term 重复
        _selected(5, lambda s: [{**s[0], "display": ""}] + s[1:]),    # display 空
        _selected(5, lambda s: [{**s[0], "display": "x" * 25}] + s[1:]),  # display 超 24 字
        _selected(5, lambda s: [{**s[0], "absorb": "no"}] + s[1:]),   # absorb 非数组
        _selected(5, lambda s: [{**s[0], "absorb": ["ghost"]}] + s[1:]),  # absorb 不在池
    ]
    for payload in bad_payloads:
        monkeypatch.setattr(tk.ai_client, "call_llm", lambda *a, **k: payload)
        fallback = [{"term": "fallback"}]
        trends = {"keywords": fallback}
        assert tk.refine_keywords_with_ai(trends, pool) is False, f"该载荷应回退:{payload}"
        assert trends["keywords"] == [{"term": "fallback"}]


def test_refine_成功路径合并吸收与池序补齐(monkeypatch, capsys):
    monkeypatch.setattr(tk, "config_ready", lambda: True)
    monkeypatch.setattr(time, "sleep", lambda s: None)
    pool = [
        _entry("openai", daily=[0] * 11 + [2, 2, 2]),
        _entry("gpt-5", daily=[0] * 11 + [1, 1, 0]),
        _entry("claude"), _entry("gemini"), _entry("grok"),
        _entry("mistral"), _entry("qwen"), _entry("kimi"),
        _entry("doubao"), _entry("sora"), _entry("cursor"),
    ]
    payload = {"selected": [
        {"term": "openai", "display": "OpenAI 全家", "absorb": ["gpt-5"]},
        {"term": "claude", "display": "Claude", "absorb": []},
        {"term": "gemini", "display": "Gemini", "absorb": []},
        {"term": "grok", "display": "Grok", "absorb": []},
        {"term": "mistral", "display": "Mistral", "absorb": []},
    ]}
    calls = {"n": 0}

    def flaky(*a, **k):
        calls["n"] += 1
        if calls["n"] == 1:
            raise RuntimeError("抖一下")  # 首败,10s 后重试成功
        return payload

    monkeypatch.setattr(tk.ai_client, "call_llm", flaky)
    trends = {"keywords": [{"term": "fallback"}]}

    assert tk.refine_keywords_with_ai(trends, pool) is True
    assert calls["n"] == 2
    kws = trends["keywords"]
    assert len(kws) == tk.TOP_KEYWORDS  # 5 个精选 + 5 个池序补齐,榜单恒满
    head = kws[0]
    assert head["term"] == "openai" and head["display"] == "OpenAI 全家"
    assert head["daily"] == [0] * 11 + [3, 3, 2]  # 吸收 gpt-5:按下标累加
    assert head["total"] == 8 and head["trend"] == "up"  # 派生字段随之重算
    assert [k["term"] for k in kws[5:]] == ["qwen", "kimi", "doubao", "sora", "cursor"]
    out = capsys.readouterr().out
    assert "AI 精修完成:10 个热词(合并吸收 1 个候选)" in out


def test_refine_候选池耗尽才允许少于10(monkeypatch):
    monkeypatch.setattr(tk, "config_ready", lambda: True)
    monkeypatch.setattr(time, "sleep", lambda s: None)
    pool = _pool(["openai", "gpt-5", "claude", "gemini", "grok"])  # 只有 5 个候选
    payload = {"selected": [
        {"term": t, "display": t, "absorb": []}
        for t in ("openai", "gpt-5", "claude", "gemini", "grok")
    ]}
    monkeypatch.setattr(tk.ai_client, "call_llm", lambda *a, **k: payload)
    trends = {"keywords": [{"term": "fallback"}]}

    assert tk.refine_keywords_with_ai(trends, pool) is True
    assert len(trends["keywords"]) == 5  # 池已用尽,不强凑 10
