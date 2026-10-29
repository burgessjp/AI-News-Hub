"""today_bundle.py 组装回归(tmp_path 仓库夹具,零真实网络)。

钉死聚合包语义:
 - 完整产物逐字段:overview 原样内嵌 / 各源 summary+fetchedAtMs / weekly 原样
   内嵌 / dataFetchedAt 取输入最大值;
 - 缺源省略(快照缺失 / 无 ai_summary_v2 的 v1 批次),其余源不受影响;
 - overview 或 weekly 缺失 → 对应键缺席,文件照写(App 分段回退);
 - index.json 缺失 / overview 与源全空 → 不写文件;
 - 组装异常保持上一版(「成功才写」,半截/覆盖不发生)。
"""

import json
import os

import today_bundle

SUMMARY_HN = [
    {"title": "Show HN: 构建系统重建", "desc": "从 Makefile 迁到自研 DSL", "url": "https://hn.dev/1"},
    {"title": "SQLite 队列压测", "desc": "单机 12 万任务/分钟", "url": "https://hn.dev/2"},
]
SUMMARY_GH = [
    {"title": "impeccable", "desc": "agent 设计词汇", "url": "https://gh.dev/1"},
]


def _write_repo(root, files):
    for rel, obj in files.items():
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            if isinstance(obj, str):
                f.write(obj)
            else:
                json.dump(obj, f, ensure_ascii=False)


def _bundle_files(overview=None, latest=None, snapshots=None, weekly=None):
    """最小合法仓库夹具:index + 快照 + weekly,可逐项覆盖/删除。"""
    files = {
        "index.json": {"latest": latest or {}, **({"latest_overview": overview} if overview is not None else {})},
        "weekly.json": weekly or {"weekStart": "2026-10-19", "issueNo": 202, "sections": []},
    }
    for (key, rel), snap in (snapshots or {}).items():
        files[os.path.join(key, rel)] = snap
    return files


def _snapshot(summary=None, fetched_ms=1000):
    snap = {"source": "x", "fetched_at_ms": fetched_ms, "items": [{"t": 1}]}
    if summary is not None:
        snap["ai_summary_v2"] = summary
    return snap


def test_完整产物逐字段(tmp_path):
    overview = {
        "generatedAt": 111, "dataFetchedAt": 1500, "digest": "综述",
        "items": [{"source": "hackernews", "title": "t", "url": "u", "comment": "c"}],
        "missingSources": [],
    }
    _write_repo(tmp_path, _bundle_files(
        overview=overview,
        latest={"hackernews": "2026-10-29/08-00-data.json",
                "github-trending": "2026-10-29/08-00-data.json"},
        snapshots={
            ("hackernews", "2026-10-29/08-00-data.json"): _snapshot(SUMMARY_HN, fetched_ms=1000),
            ("github-trending", "2026-10-29/08-00-data.json"): _snapshot(SUMMARY_GH, fetched_ms=1200),
        },
    ))

    assert today_bundle.write_today_bundle(str(tmp_path)) is True

    bundle = json.load(open(os.path.join(tmp_path, "today.json")))
    assert bundle["schema"] == 1
    assert bundle["overview"] == overview
    # dataFetchedAt = max(源快照 1200, overview 1500) = 1500
    assert bundle["dataFetchedAt"] == 1500
    assert bundle["sources"]["hackernews"] == {"fetchedAtMs": 1000, "summary": SUMMARY_HN}
    assert bundle["sources"]["github-trending"] == {"fetchedAtMs": 1200, "summary": SUMMARY_GH}
    assert bundle["weekly"] == {"weekStart": "2026-10-19", "issueNo": 202, "sections": []}


def test_缺源省略_其余不受影响(tmp_path):
    _write_repo(tmp_path, _bundle_files(
        overview={"digest": "d", "items": [], "dataFetchedAt": 100},
        latest={"hackernews": "2026-10-29/08-00-data.json",
                "github-trending": "2026-10-29/08-00-data.json"},  # 快照文件不存在
        snapshots={("hackernews", "2026-10-29/08-00-data.json"): _snapshot(SUMMARY_HN)},
    ))

    assert today_bundle.write_today_bundle(str(tmp_path)) is True

    bundle = json.load(open(os.path.join(tmp_path, "today.json")))
    assert set(bundle["sources"]) == {"hackernews"}


def test_v1纯文本批次_无v2摘要_键缺席(tmp_path):
    _write_repo(tmp_path, _bundle_files(
        overview={"digest": "d", "items": [], "dataFetchedAt": 100},
        latest={"hackernews": "2026-10-29/08-00-data.json"},
        snapshots={("hackernews", "2026-10-29/08-00-data.json"): _snapshot(summary=None)},
    ))

    assert today_bundle.write_today_bundle(str(tmp_path)) is True

    bundle = json.load(open(os.path.join(tmp_path, "today.json")))
    assert "sources" not in bundle
    assert bundle["overview"]["digest"] == "d"


def test_overview与weekly缺失_键缺席照写(tmp_path):
    files = _bundle_files(
        latest={"hackernews": "2026-10-29/08-00-data.json"},
        snapshots={("hackernews", "2026-10-29/08-00-data.json"): _snapshot(SUMMARY_HN)},
    )
    del files["weekly.json"]  # 无周刊(周一前/缺刊批次)
    _write_repo(tmp_path, files)

    assert today_bundle.write_today_bundle(str(tmp_path)) is True

    bundle = json.load(open(os.path.join(tmp_path, "today.json")))
    assert "overview" not in bundle
    assert "weekly" not in bundle
    assert bundle["sources"]["hackernews"]["summary"] == SUMMARY_HN
    assert bundle["dataFetchedAt"] == 1000


def test_index缺失_不写文件(tmp_path):
    assert today_bundle.write_today_bundle(str(tmp_path)) is False
    assert not os.path.exists(os.path.join(tmp_path, "today.json"))


def test_overview与源全空_不写文件(tmp_path):
    _write_repo(tmp_path, {"index.json": {"latest": {}}})

    assert today_bundle.write_today_bundle(str(tmp_path)) is False
    assert not os.path.exists(os.path.join(tmp_path, "today.json"))


def test_组装异常_保持上一版(tmp_path):
    _write_repo(tmp_path, {
        "index.json": "not-json{",  # 损坏的 index → _load_json None → 不写
        "today.json": {"schema": 1, "overview": {"old": True}},
    })

    assert today_bundle.write_today_bundle(str(tmp_path)) is False
    # 上一版原样保留(「成功才写」:组装失败不覆盖、不留半截)
    assert json.load(open(os.path.join(tmp_path, "today.json"))) == {"schema": 1, "overview": {"old": True}}
