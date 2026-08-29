"""fetch_data.py 继承语义核心回归 —— write_index / build_history / load_previous_index。

这是 docs/agents/pipeline.md「失败继承」条款的逐条代码守护:
 - 单源失败 → latest 指针继承上一次(index 永不缺有效数据);
 - 总览生成失败 → latest_overview 继承,当日归档不落盘(由调用方保证,此处钉 index 侧);
 - 历史索引合并(_retain_recent 已在 test_fetch_pure 钉矩阵,这里钉 SOURCES 键集);
 - 上一次 index 拉取失败 → fail-closed(宁可本轮不更新,不推降级索引);
 - history/overview_history 独立文件的迁移阶梯(独立优先→404 回退内联→丢失即中断)。
"""

import json
import os

import pytest

import fetch_data as fd
from conftest import load_fixture

INDEX_URL = "https://example.test/index.json"
HISTORY_URL = "https://example.test/history.json"
OV_HISTORY_URL = "https://example.test/overview_history.json"


def _read_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _touch(out_dir, source, date, time_):
    d = os.path.join(out_dir, source, date)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, f"{time_}-data.json"), "w", encoding="utf-8") as f:
        json.dump({"source": source}, f)


def _write_all_sources(out_dir, skip=(), date="2026-08-29", time_="11-01"):
    """给全部 8 源造本地快照(跳过 skip 里的源 = 模拟其抓取失败)。"""
    for name in fd.SOURCES:
        if name not in skip:
            _touch(out_dir, name, date, time_)


PREVIOUS_LATEST = {name: f"2026-08-28/22-00-data.json" for name in fd.SOURCES}


# ===== write_index:latest 指针继承 =====

def test_write_index_全成功_全本地无继承(frozen_now, tmp_path):
    out = str(tmp_path)
    _write_all_sources(out)
    path = fd.write_index(out, frozen_now, results={})
    index = _read_json(path)
    assert index["latest"] == {name: "2026-08-29/11-01-data.json" for name in fd.SOURCES}
    assert index["updated_at"] == "2026-08-29T11:01:00+0800"
    assert index["updated_at_ms"] == int(frozen_now.timestamp() * 1000)


def test_write_index_单源失败继承旧指向(frozen_now, tmp_path):
    out = tmp_path
    _write_all_sources(str(out), skip=("stormzhang-ai",))
    previous = dict(PREVIOUS_LATEST)
    previous["stormzhang-ai"] = "2026-08-27/18-00-data.json"
    fd.write_index(str(out), frozen_now, results={}, previous_latest=previous)
    latest = _read_json(out / "index.json")["latest"]
    # 失败源 = 上一次的指针原样继承;其余 7 源 = 本次本地扫描
    assert latest["stormzhang-ai"] == "2026-08-27/18-00-data.json"
    assert latest["hackernews"] == "2026-08-29/11-01-data.json"
    assert len(latest) == len(fd.SOURCES)


def test_write_index_上一索引缺源则整键省略(frozen_now, tmp_path):
    out = tmp_path
    _write_all_sources(str(out), skip=("producthunt",))
    # previous 只有 7 源(producthunt 从未成功过)
    previous = {k: v for k, v in PREVIOUS_LATEST.items() if k != "producthunt"}
    fd.write_index(str(out), frozen_now, results={}, previous_latest=previous)
    latest = _read_json(out / "index.json")["latest"]
    assert "producthunt" not in latest  # 无本地亦无历史 → 该键整个缺省
    assert len(latest) == len(fd.SOURCES) - 1


def test_write_index_全继承时_updated_at_仍是本轮时刻(frozen_now, tmp_path):
    out = tmp_path
    # 本地一个快照都没有(全部 8 源失败),previous 全量兜底
    fd.write_index(str(out), frozen_now, results={}, previous_latest=dict(PREVIOUS_LATEST))
    index = _read_json(out / "index.json")
    assert index["latest"] == PREVIOUS_LATEST  # 8 键全继承
    # 钉死「本地已写、由退出码拦截推送」事实:时间戳恒为本轮,不用旧值伪装
    assert index["updated_at_ms"] == int(frozen_now.timestamp() * 1000)


def test_write_index_results_参数当前不影响输出(frozen_now, tmp_path):
    """钉死事实:继承判定只看本地扫描,results 形参在函数体内未被使用。
    拆分阶段若想改用 results 驱动,必须是有意识的决策(行为等价性已被本测试锁定)。"""
    def run(results_value):
        out = str(tmp_path / ("r" + str(id(results_value))))
        os.makedirs(out, exist_ok=True)
        _write_all_sources(out, skip=("rundown-ai",))
        fd.write_index(out, frozen_now, results=results_value,
                       previous_latest=dict(PREVIOUS_LATEST))
        return _read_json(os.path.join(out, "index.json"))

    a = run(None)
    b = run({"rundown-ai": {"status": "fail", "error": "x"}})
    assert a == b


# ===== write_index:latest_overview 继承 =====

def test_write_index_总览新生成优先(frozen_now, tmp_path):
    new_ov = {"generatedAt": 2, "items": [{"t": "new"}]}
    old_ov = {"generatedAt": 1, "items": [{"t": "old"}]}
    fd.write_index(str(tmp_path), frozen_now, results={},
                   previous_overview=old_ov, overview=new_ov)
    assert _read_json(tmp_path / "index.json")["latest_overview"] == new_ov


def test_write_index_总览失败继承上次(frozen_now, tmp_path):
    old_ov = {"generatedAt": 1, "items": [{"t": "old"}]}
    fd.write_index(str(tmp_path), frozen_now, results={},
                   previous_overview=old_ov, overview=None)
    assert _read_json(tmp_path / "index.json")["latest_overview"] == old_ov


def test_write_index_总览两侧皆无则字段缺省(frozen_now, tmp_path):
    fd.write_index(str(tmp_path), frozen_now, results={},
                   previous_overview=None, overview=None)
    assert "latest_overview" not in _read_json(tmp_path / "index.json")


# ===== build_history / build_overview_history =====

def test_build_history_键集恒为现存源_退役源不继承(tmp_path):
    out = str(tmp_path)
    _touch(out, "hackernews", "2026-08-29", "11-01")
    previous = {
        "hackernews": {"2026-08-28": "2026-08-28/22-00-data.json"},
        "linuxdo": {"2026-08-01": "2026-08-01/08-00-data.json"},  # 已下线源
    }
    history = fd.build_history(out, previous_history=previous)
    assert set(history.keys()) == set(fd.SOURCES)  # 每源必有键(空也占位)
    assert "linuxdo" not in history  # 退役源旧键自然淘汰
    # 本地扫描的 relpath 相对源目录(不带源前缀);previous 的值原样保留
    assert history["hackernews"]["2026-08-29"] == "2026-08-29/11-01-data.json"
    assert history["hackernews"]["2026-08-28"] == "2026-08-28/22-00-data.json"
    assert history["stormzhang-ai"] == {}  # 无历史无本地的源 = 空字典


def test_build_history_合并后截到_31_天(tmp_path):
    out = str(tmp_path)
    # 38 天历史(2026-07-25 ~ 2026-08-31)合并本地当日,超出 31 天保留期
    previous = {name: {f"2026-07-{d:02d}": f"{name}/{d}.json" for d in range(25, 32)}
                | {f"2026-08-{d:02d}": f"{name}/{d}.json" for d in range(1, 32)}
                for name in fd.SOURCES}
    _touch(out, "rundown-ai", "2026-08-31", "22-00")
    history = fd.build_history(out, previous_history=previous)
    assert len(history["rundown-ai"]) == fd.HISTORY_RETENTION_DAYS
    assert "2026-07-25" not in history["rundown-ai"]  # 最旧的 7 月档被截掉
    assert "2026-08-01" in history["rundown-ai"]
    assert list(history["rundown-ai"].keys())[0] == "2026-08-31"  # 倒序首键


def test_build_overview_history_失败日继承早批次(tmp_path):
    out = str(tmp_path)
    # 今日两次批次,总览只在早批次成功(晚批次失败不落盘)
    _touch(out, "overview", "2026-08-29", "11-01")
    previous = {"2026-08-28": "2026-08-28/22-00-data.json",
                "2026-08-29": "2026-08-29/08-00-data.json"}
    got = fd.build_overview_history(out, previous_overview_history=previous)
    assert got["2026-08-29"] == "2026-08-29/11-01-data.json"  # 本地覆盖早批次
    assert got["2026-08-28"] == "2026-08-28/22-00-data.json"
    assert len(got) == 2


# ===== load_previous_index + _load_split_index(网络层,requests-mock) =====

def _mock_index(requests_mock, index_payload):
    requests_mock.get(INDEX_URL, json=index_payload)


def test_load_previous_index_空_url_走首跑语义():
    assert fd.load_previous_index("", HISTORY_URL, OV_HISTORY_URL) == ({}, {}, None, {})


def test_load_previous_index_正常拉取与结构清洗(monkeypatch, requests_mock):
    monkeypatch.setattr("time.sleep", lambda s: None)
    good_latest = {name: f"2026-08-28/22-00-data.json" for name in fd.SOURCES}
    _mock_index(requests_mock, {
        "updated_at": "2026-08-28T22:00:00+0800",
        "latest": {**good_latest, "bad": 123, "bad2": ""},  # 非字符串/空串值被滤
        "latest_overview": {"generatedAt": 1, "items": []},
        "history": {"hackernews": {"2026-08-28": "h.json"}},
        "overview_history": {"2026-08-28": "o.json"},
    })
    requests_mock.get(HISTORY_URL, json={"hackernews": {"2026-08-27": "h27.json"}})
    requests_mock.get(OV_HISTORY_URL, json={"2026-08-27": "o27.json"})

    latest, history, prev_ov, ov_history = fd.load_previous_index(
        INDEX_URL, HISTORY_URL, OV_HISTORY_URL)

    # 清洗按值过滤(键不检查):非字符串值与空串值被丢弃
    assert latest == good_latest
    # 独立文件 200 优先(内联字段即使存在也不用,防旧批次写坏内联污染)
    assert history == {"hackernews": {"2026-08-27": "h27.json"}}
    assert ov_history == {"2026-08-27": "o27.json"}
    assert prev_ov == {"generatedAt": 1, "items": []}


def test_load_previous_index_index_拉取全败_fail_closed(monkeypatch, requests_mock):
    monkeypatch.setattr("time.sleep", lambda s: None)
    requests_mock.get(INDEX_URL, status_code=500)
    with pytest.raises(SystemExit) as e:
        fd.load_previous_index(INDEX_URL, HISTORY_URL, OV_HISTORY_URL)
    assert e.value.code == 1  # 宁可本轮不更新,不推降级 index


def test_load_previous_index_独立文件_404_回退内联(monkeypatch, requests_mock):
    monkeypatch.setattr("time.sleep", lambda s: None)
    _mock_index(requests_mock, {
        "latest": {"hackernews": "2026-08-28/22-00-data.json"},
        "history": {"hackernews": {"2026-08-28": "inline-h.json"}},
        "overview_history": {"2026-08-28": "inline-o.json"},
    })
    requests_mock.get(HISTORY_URL, status_code=404)
    requests_mock.get(OV_HISTORY_URL, status_code=404)

    latest, history, _, ov_history = fd.load_previous_index(
        INDEX_URL, HISTORY_URL, OV_HISTORY_URL)
    assert latest == {"hackernews": "2026-08-28/22-00-data.json"}
    assert history == {"hackernews": {"2026-08-28": "inline-h.json"}}
    assert ov_history == {"2026-08-28": "inline-o.json"}


def test_load_previous_index_独立文件丢失且有历史信号_fail_closed(monkeypatch, requests_mock):
    monkeypatch.setattr("time.sleep", lambda s: None)
    # latest 有数据(仓库并非首跑)但 history.json 404 且 index 无内联 → 疑似文件丢失
    _mock_index(requests_mock, {"latest": {"hackernews": "2026-08-28/22-00-data.json"}})
    requests_mock.get(HISTORY_URL, status_code=404)
    requests_mock.get(OV_HISTORY_URL, status_code=404)
    with pytest.raises(SystemExit) as e:
        fd.load_previous_index(INDEX_URL, HISTORY_URL, OV_HISTORY_URL)
    assert e.value.code == 1


def test_load_previous_index_首跑_404_无内联得空(monkeypatch, requests_mock):
    monkeypatch.setattr("time.sleep", lambda s: None)
    # latest 为空 → 无历史数据信号,独立文件不存在不算事故
    _mock_index(requests_mock, {"latest": {}})
    requests_mock.get(HISTORY_URL, status_code=404)
    requests_mock.get(OV_HISTORY_URL, status_code=404)
    latest, history, prev_ov, ov_history = fd.load_previous_index(
        INDEX_URL, HISTORY_URL, OV_HISTORY_URL)
    assert (latest, history, prev_ov, ov_history) == ({}, {}, None, {})


def test_load_previous_index_总览校验失败视为无(monkeypatch, requests_mock):
    monkeypatch.setattr("time.sleep", lambda s: None)
    _mock_index(requests_mock, {
        "latest": {"hackernews": "2026-08-28/22-00-data.json"},
        "latest_overview": {"generatedAt": 1},  # 缺 items 列表 → 不可继承
    })
    requests_mock.get(HISTORY_URL, json={})
    requests_mock.get(OV_HISTORY_URL, json={})
    _, _, prev_ov, _ = fd.load_previous_index(INDEX_URL, HISTORY_URL, OV_HISTORY_URL)
    assert prev_ov is None


# ===== 真实批次 fixture 冒烟:裁剪件与生产结构同构 =====

def test_fixture_index_与生产结构同构():
    """fixtures/index.json 是 out/ 真实批次裁剪件;若上游改了 index 结构,
    这里第一时间暴露,提示同步 fixture。"""
    index = load_fixture("index.json")
    assert set(index.keys()) == {"updated_at", "updated_at_ms", "latest", "latest_overview"}
    assert set(index["latest"].keys()) == set(fd.SOURCES)
    ov = index["latest_overview"]
    assert isinstance(ov.get("items"), list) and ov["items"]
    snap = load_fixture("hackernews/2026-08-29/11-01-data.json")
    assert snap["source"] == "hackernews"
    assert snap["count"] == len(snap["items"]) == 2
    assert snap["ai_summary_v2"]  # 裁剪时保留摘要字段
