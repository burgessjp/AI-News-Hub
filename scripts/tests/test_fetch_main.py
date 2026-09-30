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


def _run(monkeypatch, tmp_path, sources, extra_args=(), no_summary=True):
    """替身源 + 冻结时间 + 去退避跑 main,返回退出码。"""
    import conftest

    argv = ["fetch_data.py", "--out-dir", str(tmp_path)]
    if no_summary:
        argv.append("--no-summary")
    monkeypatch.setattr(sys, "argv", [*argv, *extra_args])
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


# ===== 摘要继承:top-N 指纹与上一期快照完全一致时沿用 ai_summary_v2 =====
#
# 2026-09 数据仓实测:同日两批(08:00→18:00)top-N (标题,URL) 指纹完全一致的
# 源占 6/8(命中率 54%~84%),重跑 AI 只会换皮重写 + 白花调用费。继承的任何
# 环节失败(上一期快照拉不到 / 上期无摘要 / 指纹不符 / 上期 prompt 版本与当前
# 不同,快照顶层 summary_prompt_version)都必须退回正常摘要。

_PREV_SUMMARY = [{"title": "旧标题", "desc": "旧描述", "url": "https://x.dev/1"}]
_SAME_ITEMS = [{"id": 1, "title": "t", "url": "https://x.dev/1"}]
_PREV_SNAP_URL = "https://example.test/hackernews/2026-08-28/22-00-data.json"


def _summary_stubs(monkeypatch):
    """打开摘要阶段:config 放行 + summarize_source 记录调用并返回固定卡片。"""
    monkeypatch.setattr(fd.ai_summary, "config_ready", lambda: True)
    calls = []

    def fake_summarize(source, items):
        calls.append(source)
        return [{"title": f"新-{source}", "desc": "d", "url": "https://x.dev/new"}]

    monkeypatch.setattr(fd.ai_summary, "summarize_source", fake_summarize)
    return calls


def test_main_摘要继承_指纹一致不调AI(monkeypatch, tmp_path, requests_mock):
    requests_mock.get(_PREV_SNAP_URL, json={"source": "hackernews",
                                            "items": list(_SAME_ITEMS),
                                            "ai_summary_v2": _PREV_SUMMARY,
                                            "summary_prompt_version": fd.ai_summary.PROMPT_VERSION})
    calls = _summary_stubs(monkeypatch)
    sources = {"hackernews": lambda limit=20: (list(_SAME_ITEMS), {})}
    rc = _run(monkeypatch, tmp_path, sources,
              extra_args=_mock_previous(monkeypatch, requests_mock,
                                        {"hackernews": "2026-08-28/22-00-data.json"}),
              no_summary=False)
    assert rc == 0
    assert calls == []  # 未调 AI
    snap = _read(tmp_path / "hackernews" / "2026-08-29" / "11-01-data.json")
    assert snap["ai_summary_v2"] == _PREV_SUMMARY  # 继承落进本次快照
    assert snap["summary_prompt_version"] == fd.ai_summary.PROMPT_VERSION


def test_main_摘要继承_指纹不符退回重新摘要(monkeypatch, tmp_path, requests_mock):
    changed = [{"id": 2, "title": "t2", "url": "https://x.dev/2"}]
    requests_mock.get(_PREV_SNAP_URL, json={"source": "hackernews",
                                            "items": changed,
                                            "ai_summary_v2": _PREV_SUMMARY,
                                            "summary_prompt_version": fd.ai_summary.PROMPT_VERSION})
    calls = _summary_stubs(monkeypatch)
    sources = {"hackernews": lambda limit=20: (list(_SAME_ITEMS), {})}
    rc = _run(monkeypatch, tmp_path, sources,
              extra_args=_mock_previous(monkeypatch, requests_mock,
                                        {"hackernews": "2026-08-28/22-00-data.json"}),
              no_summary=False)
    assert rc == 0 and calls == ["hackernews"]
    snap = _read(tmp_path / "hackernews" / "2026-08-29" / "11-01-data.json")
    assert snap["ai_summary_v2"][0]["title"] == "新-hackernews"


def test_main_摘要继承_上期无摘要退回重新摘要(monkeypatch, tmp_path, requests_mock):
    # 上期快照无 ai_summary_v2(上次 AI 摘要失败):指纹一致也无从继承
    requests_mock.get(_PREV_SNAP_URL, json={"source": "hackernews",
                                            "items": list(_SAME_ITEMS)})
    calls = _summary_stubs(monkeypatch)
    sources = {"hackernews": lambda limit=20: (list(_SAME_ITEMS), {})}
    rc = _run(monkeypatch, tmp_path, sources,
              extra_args=_mock_previous(monkeypatch, requests_mock,
                                        {"hackernews": "2026-08-28/22-00-data.json"}),
              no_summary=False)
    assert rc == 0 and calls == ["hackernews"]


def test_main_摘要继承_上期prompt版本不符退回重新摘要(monkeypatch, tmp_path, requests_mock):
    # prompt 升级后(PROMPT_VERSION bump):旧快照无版本字段(或版本不同)时即使指纹
    # 一致也不继承 —— 旧摘要按旧 prompt 写成,沿用会让新旧风格在同批快照间混排
    requests_mock.get(_PREV_SNAP_URL, json={"source": "hackernews",
                                            "items": list(_SAME_ITEMS),
                                            "ai_summary_v2": _PREV_SUMMARY})  # 无版本字段
    calls = _summary_stubs(monkeypatch)
    sources = {"hackernews": lambda limit=20: (list(_SAME_ITEMS), {})}
    rc = _run(monkeypatch, tmp_path, sources,
              extra_args=_mock_previous(monkeypatch, requests_mock,
                                        {"hackernews": "2026-08-28/22-00-data.json"}),
              no_summary=False)
    assert rc == 0 and calls == ["hackernews"]
    snap = _read(tmp_path / "hackernews" / "2026-08-29" / "11-01-data.json")
    assert snap["ai_summary_v2"][0]["title"] == "新-hackernews"
    assert snap["summary_prompt_version"] == fd.ai_summary.PROMPT_VERSION  # 新摘要带当前版本


def test_main_摘要继承_上一期快照拉取失败退回(monkeypatch, tmp_path, requests_mock):
    # 上一期快照 URL 未注册 → 拉取异常:继承是纯优化,任何失败退回正常摘要
    calls = _summary_stubs(monkeypatch)
    sources = {"hackernews": lambda limit=20: (list(_SAME_ITEMS), {})}
    rc = _run(monkeypatch, tmp_path, sources,
              extra_args=_mock_previous(monkeypatch, requests_mock,
                                        {"hackernews": "2026-08-27/22-00-data.json"}),
              no_summary=False)
    assert rc == 0 and calls == ["hackernews"]


# ===== 源健康哨兵:条目数骤降的 stderr 告警(不阻断、不改产物) =====

def test_main_源健康哨兵_条目数骤降告警(monkeypatch, tmp_path, capsys):
    sources = {"hackernews": lambda limit=20: ([{"id": i, "title": f"t{i}"} for i in range(3)], {})}
    rc = _run(monkeypatch, tmp_path, sources, extra_args=("--no-previous-index",))
    assert rc == 0
    err = capsys.readouterr().err
    assert "源健康" in err and "hackernews" in err
    # 快照照常落盘(哨兵只告警不拦截)
    assert (tmp_path / "hackernews" / "2026-08-29" / "11-01-data.json").is_file()


def test_main_源健康哨兵_豁免源零条目不告警(monkeypatch, tmp_path, capsys):
    sources = {"openai-anthropic-news": lambda: ([], {})}
    rc = _run(monkeypatch, tmp_path, sources, extra_args=("--no-previous-index",))
    assert rc == 0
    assert "源健康" not in capsys.readouterr().err


# ===== manifest 的 AI 用量观测 =====

def test_main_manifest记录AI用量(monkeypatch, tmp_path):
    fake_usage = {"calls": 2, "prompt_tokens": 100, "completion_tokens": 40}
    monkeypatch.setattr(fd.ai_client, "usage_snapshot", lambda: dict(fake_usage))
    rc = _run(monkeypatch, tmp_path, ALL_OK_SOURCES, extra_args=("--no-previous-index",))
    assert rc == 0
    assert _read(tmp_path / "manifest.json")["ai_usage"] == fake_usage


def test_main_manifest无AI调用不写用量字段(monkeypatch, tmp_path):
    monkeypatch.setattr(fd.ai_client, "usage_snapshot",
                        lambda: {"calls": 0, "prompt_tokens": 0, "completion_tokens": 0})
    rc = _run(monkeypatch, tmp_path, ALL_OK_SOURCES, extra_args=("--no-previous-index",))
    assert rc == 0
    assert "ai_usage" not in _read(tmp_path / "manifest.json")
