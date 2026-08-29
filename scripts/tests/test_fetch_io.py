"""fetch_data.py 落盘与扫描回归(tmp_path,零网络)。

钉死:快照/总览的目录版式与 payload 结构、ai_summary_v2 的「非空才写」、
_iter_snapshots 的定宽格式过滤、_scan_latest 跨天取最大、_scan_history 逐日取末班。
这些是 write_index 继承判定的事实前提(继承 = 本地扫不到)。
"""

import json
import os

import fetch_data as fd


def _read_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _touch(out_dir, source, date, time_):
    d = os.path.join(out_dir, source, date)
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, f"{time_}-data.json")
    with open(p, "w", encoding="utf-8") as f:
        json.dump({"source": source}, f)
    return p


# ===== write_snapshot =====

def test_write_snapshot_目录版式与_payload(frozen_now, tmp_path):
    items = [{"id": 1, "title": "t"}]
    meta = {"pageDate": "2026-08-29"}
    path = fd.write_snapshot(str(tmp_path), "hackernews", items, meta, frozen_now)

    assert path == str(tmp_path / "hackernews" / "2026-08-29" / "11-01-data.json")
    payload = _read_json(path)
    assert payload["source"] == "hackernews"
    assert payload["fetched_at"] == "2026-08-29T11:01:00+0800"
    assert payload["count"] == 1
    assert payload["items"] == items
    assert payload["pageDate"] == "2026-08-29"  # meta 拍扁进顶层
    assert "ai_summary_v2" not in payload  # 未传摘要 → 字段省略


def test_write_snapshot_摘要非空才写入(frozen_now, tmp_path):
    summary = [{"title": "a", "desc": "d", "url": "u"}]
    path = fd.write_snapshot(str(tmp_path), "rundown-ai", [], {}, frozen_now,
                             ai_summary_v2=summary)
    payload = _read_json(path)
    assert payload["ai_summary_v2"] == summary
    assert payload["count"] == 0  # 空结果豁免源也照常落盘 0 条


def test_write_snapshot_同日同刻覆盖旧文件(frozen_now, tmp_path):
    fd.write_snapshot(str(tmp_path), "stormzhang-ai", [{"old": 1}], {}, frozen_now)
    fd.write_snapshot(str(tmp_path), "stormzhang-ai", [{"new": 1}], {}, frozen_now)
    payload = _read_json(tmp_path / "stormzhang-ai" / "2026-08-29" / "11-01-data.json")
    assert payload["items"] == [{"new": 1}]


# ===== patch_ai_summary_v2(两阶段:抓取落盘后回填) =====

def test_patch_ai_summary_v2_空值跳过非空回填(frozen_now, tmp_path):
    path = fd.write_snapshot(str(tmp_path), "hackernews", [{"id": 1}], {}, frozen_now)
    fd.patch_ai_summary_v2(path, None)
    fd.patch_ai_summary_v2(path, [])
    assert "ai_summary_v2" not in _read_json(path)

    summary = [{"title": "a", "desc": "d", "url": ""}]
    fd.patch_ai_summary_v2(path, summary)
    assert _read_json(path)["ai_summary_v2"] == summary
    assert _read_json(path)["items"] == [{"id": 1}]  # 原字段不动


# ===== write_overview_snapshot =====

def test_write_overview_snapshot_与快照同构版式(frozen_now, tmp_path):
    overview = {"generatedAt": 1, "digest": "综述", "items": []}
    path = fd.write_overview_snapshot(str(tmp_path), overview, frozen_now)
    assert path == str(tmp_path / "overview" / "2026-08-29" / "11-01-data.json")
    assert _read_json(path) == overview


# ===== _iter_snapshots:定宽格式是字典序==时间序的前提,畸形一律跳过 =====

def test_iter_snapshots_只认定宽日期目录与文件名(tmp_path):
    out = str(tmp_path)
    valid = _touch(out, "s", "2026-08-28", "08-00")
    _touch(out, "s", "2026-08-28", "22-00")
    # 畸形目录名/文件名全家桶:全部不该被扫到
    _touch(out, "s", "2026-8-28", "08-00")  # 非定宽日期
    _touch(out, "s", "not-a-date", "08-00")
    _touch(out, "s", "2026-08-28", "8-00")  # 非定宽时刻
    os.makedirs(os.path.join(out, "s", "2026-08-28", "sub"), exist_ok=True)
    _touch(out, "s", "2026-08-28", "23-59")  # 合法第三个
    with open(os.path.join(out, "s", "stray.txt"), "w") as f:
        f.write("x")  # 源目录下的散文件

    got = sorted(fd._iter_snapshots(out, "s"))
    assert got == [("2026-08-28", "08-00"), ("2026-08-28", "22-00"), ("2026-08-28", "23-59")]
    assert os.path.isfile(valid)


def test_iter_snapshots_目录不存在直接空(tmp_path):
    assert list(fd._iter_snapshots(str(tmp_path), "nope")) == []


# ===== _scan_latest:latest 指针的事实来源 =====

def test_scan_latest_跨天跨刻取字典序最大(tmp_path):
    out = str(tmp_path)
    _touch(out, "s", "2026-08-27", "22-00")
    _touch(out, "s", "2026-08-28", "08-00")
    _touch(out, "s", "2026-08-28", "09-30")
    assert fd._scan_latest(out, "s") == "2026-08-28/09-30-data.json"


def test_scan_latest_无任何文件返回_none(tmp_path):
    assert fd._scan_latest(str(tmp_path), "s") is None


# ===== _scan_history:每日期末班 =====

def test_scan_history_每日取最后一次(tmp_path):
    out = str(tmp_path)
    _touch(out, "s", "2026-08-27", "08-00")
    _touch(out, "s", "2026-08-27", "18-00")
    _touch(out, "s", "2026-08-28", "22-00")
    assert fd._scan_history(out, "s") == {
        "2026-08-27": "2026-08-27/18-00-data.json",
        "2026-08-28": "2026-08-28/22-00-data.json",
    }
