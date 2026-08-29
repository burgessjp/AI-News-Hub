"""fetch_data.py main() 端到端回归(stub 源 + 冻结时间 + mock 上一索引,零真实网络)。

钉死 pipeline.md 的运行级语义:
 - ≥1 源成功 → 退出码 0,失败源 latest 指针继承上一索引;
 - 全部失败 → 退出码 1,但本地 index 照写(pipeline.sh 的 set -e 负责拦推送,
   「本地已写、绝不推送」的分工靠退出码,不靠跳过写盘);
 - 空结果:非豁免源按失败(不落盘、走继承),豁免源 openai-anthropic-news 正常
   落盘 0 条快照;
 - --only 未知源 → 2;--only 指定单源时其余源照样继承,latest 键集恒为全 8 源;
 - manifest.json 逐源 status/count/file(相对 out 根)。
"""

import json
import sys

import fetch_data as fd

IDX = "https://example.test/index.json"
HIST = "https://example.test/history.json"
OV_HIST = "https://example.test/overview_history.json"


def _read(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _ok_fetcher():
    return lambda: ([{"id": 1, "title": "t"}], {"metaKey": "v"})


def _hn_fetcher():
    # fetch_with_retry 对 hackernews 以关键字传 limit,签名必须兼容
    return lambda limit=20: ([{"id": 1, "title": "hn"}], {})


def _fail_fetcher():
    def boom():
        raise RuntimeError("net down")

    return boom


def _run(monkeypatch, tmp_path, sources, extra_args=()):
    """替身源 + 冻结时间 + 去退避跑 main,返回退出码。"""
    import conftest

    monkeypatch.setattr(sys, "argv", ["fetch_data.py", "--out-dir", str(tmp_path),
                                      "--no-summary", *extra_args])
    monkeypatch.setattr(fd, "SOURCES", dict(sources))
    monkeypatch.setattr(fd, "now_cst", lambda: conftest.FROZEN_NOW)

    def instant(fn, *, attempts=3, backoff_base=2, log_tag="RETRY", on_exhausted=None):
        return fn()

    monkeypatch.setattr(fd, "retry", instant)
    return fd.main()


def _mock_previous(monkeypatch, requests_mock, latest):
    requests_mock.get(IDX, json={"latest": latest})
    requests_mock.get(HIST, json={})
    requests_mock.get(OV_HIST, json={})
    monkeypatch.setattr("time.sleep", lambda s: None)
    return ("--previous-index-url", IDX,
            "--previous-history-url", HIST,
            "--previous-overview-history-url", OV_HIST)


ALL_OK_SOURCES = {name: (_hn_fetcher() if name == "hackernews" else _ok_fetcher())
                  for name in fd.SOURCES}
OLD_LATEST = {name: "2026-08-28/22-00-data.json" for name in fd.SOURCES}


def test_main_全成功_退出码0_全本地指向(monkeypatch, tmp_path):
    rc = _run(monkeypatch, tmp_path, ALL_OK_SOURCES)
    assert rc == 0
    index = _read(tmp_path / "index.json")
    assert index["latest"] == {name: "2026-08-29/11-01-data.json" for name in fd.SOURCES}
    assert index["updated_at_ms"] is not None
    assert (tmp_path / "hackernews" / "2026-08-29" / "11-01-data.json").is_file()

    manifest = _read(tmp_path / "manifest.json")
    assert manifest["run_at"] == "2026-08-29T11:01:00+0800"
    assert set(manifest["sources"].keys()) == set(fd.SOURCES)
    hn = manifest["sources"]["hackernews"]
    assert hn["status"] == "ok" and hn["count"] == 1
    # manifest 的 file 存相对 out 根路径(不带 out/ 前缀)
    assert hn["file"] == "hackernews/2026-08-29/11-01-data.json"

    history = _read(tmp_path / "history.json")
    assert set(history.keys()) == set(fd.SOURCES)


def test_main_一源成功其余失败_退出码0_七源继承(monkeypatch, tmp_path, requests_mock):
    sources = {name: (_hn_fetcher() if name == "hackernews" else _fail_fetcher())
               for name in fd.SOURCES}
    rc = _run(monkeypatch, tmp_path, sources,
              extra_args=_mock_previous(monkeypatch, requests_mock, OLD_LATEST))
    assert rc == 0

    index = _read(tmp_path / "index.json")["latest"]
    assert index["hackernews"] == "2026-08-29/11-01-data.json"  # 本次成功 → 本地
    for name in fd.SOURCES:
        if name != "hackernews":
            assert index[name] == "2026-08-28/22-00-data.json"  # 继承旧指向

    manifest = _read(tmp_path / "manifest.json")["sources"]
    assert manifest["hackernews"]["status"] == "ok"
    fails = [n for n, r in manifest.items() if r["status"] == "fail"]
    assert len(fails) == len(fd.SOURCES) - 1
    assert all("RuntimeError" in manifest[n]["error"] for n in fails)


def test_main_全部失败_退出码1_index照写全继承(monkeypatch, tmp_path, requests_mock):
    sources = {name: _fail_fetcher() for name in fd.SOURCES}
    rc = _run(monkeypatch, tmp_path, sources,
              extra_args=_mock_previous(monkeypatch, requests_mock, OLD_LATEST))
    assert rc == 1
    # 「本地已写、绝不推送」:退出码拦推送(下游 set -e),index 仍完整落盘
    index = _read(tmp_path / "index.json")
    assert index["latest"] == OLD_LATEST
    assert (tmp_path / "manifest.json").is_file()
    assert all(r["status"] == "fail"
               for r in _read(tmp_path / "manifest.json")["sources"].values())


def test_main_空结果非豁免源按失败_不落盘(monkeypatch, tmp_path, requests_mock):
    sources = dict(ALL_OK_SOURCES)
    sources["stormzhang-ai"] = lambda: ([], {})  # 抓取成功但空
    rc = _run(monkeypatch, tmp_path, sources,
              extra_args=_mock_previous(monkeypatch, requests_mock, OLD_LATEST))
    assert rc == 0  # 其余 7 源成功

    assert not (tmp_path / "stormzhang-ai").exists()  # 空结果不落盘
    index = _read(tmp_path / "index.json")["latest"]
    assert index["stormzhang-ai"] == "2026-08-28/22-00-data.json"  # 走继承
    manifest = _read(tmp_path / "manifest.json")["sources"]
    assert manifest["stormzhang-ai"]["status"] == "fail"
    assert "EmptyResultError" in manifest["stormzhang-ai"]["error"]  # 未重试路径


def test_main_空结果豁免源ok_落盘0条(monkeypatch, tmp_path, requests_mock):
    sources = dict(ALL_OK_SOURCES)
    sources["openai-anthropic-news"] = lambda: ([], {})  # 月级更新窗口无新文属正常
    rc = _run(monkeypatch, tmp_path, sources,
              extra_args=_mock_previous(monkeypatch, requests_mock, OLD_LATEST))
    assert rc == 0

    snap = _read(tmp_path / "openai-anthropic-news" / "2026-08-29" / "11-01-data.json")
    assert snap["count"] == 0 and snap["items"] == []
    assert _read(tmp_path / "index.json")["latest"]["openai-anthropic-news"] == \
        "2026-08-29/11-01-data.json"  # 本次 0 条也算成功,指向本次
    assert _read(tmp_path / "manifest.json")["sources"]["openai-anthropic-news"]["status"] == "ok"


def test_main_only_未知源_退出码2(monkeypatch, tmp_path):
    rc = _run(monkeypatch, tmp_path, ALL_OK_SOURCES, extra_args=("--only", "nope"))
    assert rc == 2
    # 中断于抓取前:除 makedirs 的空根目录外无任何产物
    assert not (tmp_path / "index.json").exists()


def test_main_only_单源_其余照样继承(monkeypatch, tmp_path, requests_mock):
    rc = _run(monkeypatch, tmp_path, ALL_OK_SOURCES,
              extra_args=_mock_previous(monkeypatch, requests_mock, OLD_LATEST) +
                         ("--only", "hackernews"))
    assert rc == 0
    # write_index 遍历全 SOURCES 而非 targets:--only 只影响抓取,latest 键集仍全 8 源
    latest = _read(tmp_path / "index.json")["latest"]
    assert set(latest.keys()) == set(fd.SOURCES)
    assert latest["hackernews"] == "2026-08-29/11-01-data.json"
    assert latest["rundown-ai"] == "2026-08-28/22-00-data.json"
    assert set(_read(tmp_path / "manifest.json")["sources"].keys()) == {"hackernews"}


def test_main_no_previous_index_失败源直接缺省(monkeypatch, tmp_path):
    sources = {name: _fail_fetcher() for name in fd.SOURCES}
    rc = _run(monkeypatch, tmp_path, sources, extra_args=("--no-previous-index",))
    assert rc == 1
    index = _read(tmp_path / "index.json")
    assert index["latest"] == {}  # 首跑语义:无继承来源,latest 空
