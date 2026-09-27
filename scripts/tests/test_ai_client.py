"""ai_client.call_llm 的 token 用量累计(fetch_data 聚合进 manifest 的观测源)。"""

import pytest

import ai_client


@pytest.fixture(autouse=True)
def _reset_usage():
    # 按值重置而非 clear():_accumulate_usage 对键做 +=,clear 掉键会连锁 KeyError
    zero = {"calls": 0, "prompt_tokens": 0, "completion_tokens": 0}
    ai_client._USAGE.update(zero)
    yield
    ai_client._USAGE.update(zero)


def _mock_llm(requests_mock, base, content, usage=None):
    body = {"choices": [{"message": {"content": content}}]}
    if usage is not None:
        body["usage"] = usage
    requests_mock.post(f"{base}/v1/chat/completions", json=body)


def test_usage_累计与快照(requests_mock):
    _mock_llm(requests_mock, "https://api.test", '["a"]',
              {"prompt_tokens": 100, "completion_tokens": 50})
    ai_client.call_llm("s", "u", "https://api.test", "m", "k",
                       timeout=(1, 1), temperature=0.1)
    snap = ai_client.usage_snapshot()
    assert snap["calls"] == 1
    assert snap["prompt_tokens"] == 100 and snap["completion_tokens"] == 50


def test_usage_缺失usage字段容错(requests_mock):
    _mock_llm(requests_mock, "https://api.test", '["a"]')
    ai_client.call_llm("s", "u", "https://api.test", "m", "k",
                       timeout=(1, 1), temperature=0.1)
    snap = ai_client.usage_snapshot()
    # calls 照计,tokens 不涨(端点不回 usage 时不崩、不误报)
    assert snap["calls"] == 1
    assert snap["prompt_tokens"] == 0 and snap["completion_tokens"] == 0


def test_usage_多次调用累加(requests_mock):
    _mock_llm(requests_mock, "https://api.test", '["a"]',
              {"prompt_tokens": 10, "completion_tokens": 5})
    for _ in range(3):
        ai_client.call_llm("s", "u", "https://api.test", "m", "k",
                           timeout=(1, 1), temperature=0.1)
    snap = ai_client.usage_snapshot()
    assert snap == {"calls": 3, "prompt_tokens": 30, "completion_tokens": 15}
