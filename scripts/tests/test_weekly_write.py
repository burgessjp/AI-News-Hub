"""weekly_digest 写盘回归(tmp_path,零网络;AI 经 monkeypatch 桩掉)。

钉住 write_weekly 的完整行为矩阵:
 - 幂等闸:索引已含该周且非 force → 跳过(不调 AI);
 - coverage 闸 / AI 未配置 → 返回 False 不落盘不抛异常;
 - AI 全败 → 返回 False 不写任何文件;
 - 成功 → 三件套齐写(归档 + 索引合并倒序截 52 + 根级 weekly.json 同构),
   回填条目来自真实候选池;
 - force 重生成覆盖指针。
"""

import json
from datetime import datetime

import pytest

import weekly_digest as wd
from common import BEIJING_TZ

from test_weekly_pure import (
    MONDAY, WEEK, _ai_ok, _full_week_input, _mk_item, _mk_repo_day,
)


@pytest.fixture
def repo_with_week(tmp_path):
    """备好一个含完整上周 overview 归档的 repo(每天 2 条,候选池 14 条)。"""
    for i, d in enumerate(WEEK):
        _mk_repo_day(tmp_path, d, [_mk_item(f"https://x/{i}", breaking=(i == 0)),
                                   _mk_item(f"https://x/b{i}")],
                     digest=f"{d}综述")
    (tmp_path / "overview_history.json").write_text(
        json.dumps({d: f"{d}/08-00-data.json" for d in WEEK}, ensure_ascii=False),
        encoding="utf-8")
    return tmp_path


@pytest.fixture
def ai_ready(monkeypatch):
    """配好 AI 环境 + 桩 call_llm 返回合法输出(按候选池现拼)。"""
    monkeypatch.setattr(wd, "config_ready", lambda: True)
    monkeypatch.setenv(wd.ENV_BASE_URL, "https://api.test")
    monkeypatch.setenv(wd.ENV_MODEL, "test-model")
    monkeypatch.setenv(wd.ENV_API_KEY, "test-key")
    calls = []

    def fake_call(system, user, base, model, key, **kw):
        calls.append((system, user))
        pool = fake_call.pool
        return _ai_ok(pool)

    fake_call.pool = []
    fake_call.calls = calls
    monkeypatch.setattr(wd.ai_client, "call_llm", fake_call)
    return fake_call


FROZEN = datetime(2026, 10, 19, 8, 30, tzinfo=BEIJING_TZ)  # 周一早批


def test_write_weekly_成功三件套齐写(repo_with_week, ai_ready, monkeypatch):
    monkeypatch.setattr(wd, "now_cst", lambda: FROZEN)
    # 桩的候选池要从真实 repo 读出来:借 _load_week_input
    ai_ready.pool = wd._load_week_input(repo_with_week, WEEK[0], WEEK[6])["pool"]
    assert wd.write_weekly(repo_with_week) is True

    rel = f"{MONDAY}/{FROZEN.strftime('%H-%M')}-data.json"
    arc = repo_with_week / "weekly" / rel
    assert arc.is_file()
    weekly = json.loads(arc.read_text(encoding="utf-8"))
    assert weekly["weekStart"] == MONDAY
    assert weekly["issueNo"] == 4
    assert 3 <= len(weekly["sections"]) <= 6

    history = json.loads((repo_with_week / "weekly_history.json").read_text(encoding="utf-8"))
    assert history == {MONDAY: rel}

    root = json.loads((repo_with_week / "weekly.json").read_text(encoding="utf-8"))
    assert root == weekly  # 根级 latest 与归档同构


def test_write_weekly_幂等闸已生成跳过不再调AI(repo_with_week, ai_ready, monkeypatch):
    monkeypatch.setattr(wd, "now_cst", lambda: FROZEN)
    ai_ready.pool = wd._load_week_input(repo_with_week, WEEK[0], WEEK[6])["pool"]
    assert wd.write_weekly(repo_with_week) is True
    n = len(ai_ready.calls)
    assert wd.write_weekly(repo_with_week) is True  # 第二次:幂等跳过
    assert len(ai_ready.calls) == n  # 没有再调 AI


def test_write_weekly_force重生成覆盖指针(repo_with_week, ai_ready, monkeypatch):
    monkeypatch.setattr(wd, "now_cst", lambda: FROZEN)
    ai_ready.pool = wd._load_week_input(repo_with_week, WEEK[0], WEEK[6])["pool"]
    assert wd.write_weekly(repo_with_week) is True
    later = datetime(2026, 10, 19, 18, 30, tzinfo=BEIJING_TZ)
    monkeypatch.setattr(wd, "now_cst", lambda: later)
    assert wd.write_weekly(repo_with_week, force=True) is True
    history = json.loads((repo_with_week / "weekly_history.json").read_text(encoding="utf-8"))
    assert history[MONDAY] == f"{MONDAY}/18-30-data.json"
    assert len(history) == 1  # 同周覆盖,不叠键


def test_write_weekly_coverage不足返回False不调AI(tmp_path, ai_ready, monkeypatch):
    # 只有 4 天归档(< MIN_COVERAGE_DAYS)
    for d in WEEK[:4]:
        _mk_repo_day(tmp_path, d, [_mk_item(f"https://x/{d}")])
    monkeypatch.setattr(wd, "now_cst", lambda: FROZEN)
    assert wd.write_weekly(tmp_path) is False
    assert len(ai_ready.calls) == 0
    assert not (tmp_path / "weekly.json").exists()


def test_write_weekly_AI未配置返回False(repo_with_week, monkeypatch):
    monkeypatch.setattr(wd, "config_ready", lambda: False)
    monkeypatch.setattr(wd, "now_cst", lambda: FROZEN)
    assert wd.write_weekly(repo_with_week) is False
    assert not (repo_with_week / "weekly.json").exists()


def test_write_weekly_AI全败不落盘不抛异常(repo_with_week, monkeypatch):
    monkeypatch.setattr(wd, "config_ready", lambda: True)
    monkeypatch.setenv(wd.ENV_BASE_URL, "https://api.test")
    monkeypatch.setenv(wd.ENV_MODEL, "test-model")
    monkeypatch.setenv(wd.ENV_API_KEY, "test-key")

    def boom(*a, **kw):
        raise RuntimeError("AI 挂了")

    monkeypatch.setattr(wd.ai_client, "call_llm", boom)
    monkeypatch.setattr(wd, "now_cst", lambda: FROZEN)
    # 重试 sleep 桩掉,不等墙钟
    monkeypatch.setattr(wd.time, "sleep", lambda s: None)
    assert wd.write_weekly(repo_with_week) is False
    assert not (repo_with_week / "weekly.json").exists()
    assert not (repo_with_week / "weekly_history.json").exists()
    assert not (repo_with_week / "weekly").exists()


def test_write_weekly_索引合并保留旧键且倒序(tmp_path, ai_ready, monkeypatch):
    # 既有索引:新一周(10-19 周)+ 旧一周(10-05 周)
    (tmp_path / "weekly_history.json").write_text(json.dumps({
        "2026-10-05": "2026-10-05/08-00-data.json",
    }, ensure_ascii=False), encoding="utf-8")
    for i, d in enumerate(WEEK):
        _mk_repo_day(tmp_path, d, [_mk_item(f"https://x/{i}"), _mk_item(f"https://x/b{i}")],
                     digest=f"{d}综述")
    (tmp_path / "overview_history.json").write_text(
        json.dumps({d: f"{d}/08-00-data.json" for d in WEEK}, ensure_ascii=False),
        encoding="utf-8")
    monkeypatch.setattr(wd, "now_cst", lambda: FROZEN)
    ai_ready.pool = wd._load_week_input(tmp_path, WEEK[0], WEEK[6])["pool"]
    assert wd.write_weekly(tmp_path) is True
    history = json.loads((tmp_path / "weekly_history.json").read_text(encoding="utf-8"))
    assert list(history) == [MONDAY, "2026-10-05"]  # 倒序 + 旧键保留


def test_write_weekly_索引超52期截断(repo_with_week, ai_ready, monkeypatch):
    # 预置 52 个旧期指针:新期写入后总数 53,应截到 52(最新在前,最旧被摘)
    old = {}
    base = datetime.strptime(MONDAY, "%Y-%m-%d").date()
    for i in range(52):
        monday = (base - timedelta_weeks(52 - i)).isoformat()
        old[monday] = f"{monday}/08-00-data.json"
    (repo_with_week / "weekly_history.json").write_text(
        json.dumps(old, ensure_ascii=False), encoding="utf-8")
    monkeypatch.setattr(wd, "now_cst", lambda: FROZEN)
    ai_ready.pool = wd._load_week_input(repo_with_week, WEEK[0], WEEK[6])["pool"]
    assert wd.write_weekly(repo_with_week) is True
    history = json.loads((repo_with_week / "weekly_history.json").read_text(encoding="utf-8"))
    assert len(history) == 52
    assert list(history)[0] == MONDAY  # 最新在前


def timedelta_weeks(n):
    from datetime import timedelta
    return timedelta(weeks=n)
