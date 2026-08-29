"""common.py 回归:8 源 key 顺序契约 + 北京时区 + 通用重试骨架。

重试语义是 fetch 阶段「单源失败跳过」与 index 拉取 fail-closed 的公共底座,
在这里钉死后,fetch_data 侧测试才能放心把 retry 直调化(no_retry_backoff)。
"""

from datetime import timedelta

import pytest

import common


def test_source_keys_八源且有序():
    assert common.SOURCE_KEYS == (
        "hackernews",
        "github-trending",
        "openai-anthropic-news",
        "huggingface-papers",
        "producthunt",
        "rundown-ai",
        "aihot-featured",
        "stormzhang-ai",
    )
    # 固定展示序:与 App 端 SourceMeta.DEFAULT_SOURCE_ORDER 对齐,重复/乱序都是事故
    assert len(set(common.SOURCE_KEYS)) == 8


def test_beijing_tz_恒定_utc_plus_8():
    assert common.BEIJING_TZ.utcoffset(None) == timedelta(hours=8)
    assert common.now_cst().utcoffset() == timedelta(hours=8)


def test_retry_首试成功不重试不睡(monkeypatch):
    sleeps = []
    monkeypatch.setattr("time.sleep", lambda s: sleeps.append(s))
    assert common.retry(lambda: "ok", attempts=3) == "ok"
    assert sleeps == []


def test_retry_失败间指数退避且最终成功(monkeypatch):
    sleeps = []
    monkeypatch.setattr("time.sleep", lambda s: sleeps.append(s))
    calls = {"n": 0}

    def flaky():
        calls["n"] += 1
        if calls["n"] < 3:
            raise RuntimeError("boom")
        return "ok"

    assert common.retry(flaky, attempts=3, backoff_base=2) == "ok"
    # 第 n 次失败后 sleep(backoff_base ** n):2s / 4s
    assert sleeps == [2, 4]


def test_retry_耗尽无回调则抛最后异常(monkeypatch):
    monkeypatch.setattr("time.sleep", lambda s: None)

    def always_fail():
        raise ValueError("fatal")

    with pytest.raises(ValueError, match="fatal"):
        common.retry(always_fail, attempts=3)


def test_retry_耗尽有回调则走回调且返回_none(monkeypatch):
    monkeypatch.setattr("time.sleep", lambda s: None)
    got = []

    def on_exhausted(exc):
        got.append(exc)

    def always_fail():
        raise ValueError("fatal")

    assert common.retry(always_fail, attempts=2, on_exhausted=on_exhausted) is None
    assert isinstance(got[0], ValueError)
