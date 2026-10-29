"""sources/httpio.py 的 GitCode API 认证头回归(requests-mock 拦截,零真实网络)。

钉死 _gitcode_auth 的三面护栏:
 - 设置了 GITCODE_TOKEN 时仅对 api.gitcode.com 附 Bearer 头(匿名读受
   GitCode 华为云 WAF 429 限流,认证请求走独立配额,2026-10-29 实锤);
 - token 绝不发给第三方源站(host 不匹配不加头);
 - 未设置 token 时对 gitcode 也不附加(纯匿名环境行为不变,CI/本地可选)。
"""

from sources import httpio

GITCODE_RAW = "https://api.gitcode.com/api/v5/repos/x/y/raw/index.json"
THIRD_PARTY = "https://hacker-news.firebaseio.com/v0/topstories.json"


def test_gitcode_请求附token认证头(requests_mock, monkeypatch):
    monkeypatch.setenv("GITCODE_TOKEN", "tok-123")
    requests_mock.get(GITCODE_RAW, json={})
    httpio.fetch_text(GITCODE_RAW, expect_json=True)
    assert requests_mock.last_request.headers["Authorization"] == "Bearer tok-123"


def test_第三方源站绝不带token(requests_mock, monkeypatch):
    monkeypatch.setenv("GITCODE_TOKEN", "tok-123")
    requests_mock.get(THIRD_PARTY, json=[])
    httpio.fetch_text(THIRD_PARTY, expect_json=True)
    assert "Authorization" not in requests_mock.last_request.headers


def test_未设token对gitcode也不附加(requests_mock, monkeypatch):
    monkeypatch.delenv("GITCODE_TOKEN", raising=False)
    requests_mock.get(GITCODE_RAW, json={})
    httpio.fetch_text(GITCODE_RAW, expect_json=True)
    assert "Authorization" not in requests_mock.last_request.headers


def test_调用方显式Authorization优先于自动注入(requests_mock, monkeypatch):
    monkeypatch.setenv("GITCODE_TOKEN", "tok-auto")
    requests_mock.get(GITCODE_RAW, json={})
    httpio.fetch_text(GITCODE_RAW, extra_headers={"Authorization": "Bearer tok-caller"})
    assert requests_mock.last_request.headers["Authorization"] == "Bearer tok-caller"
