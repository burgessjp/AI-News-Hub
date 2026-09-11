"""trend_keywords 归档与写盘回归(tmp_path,零网络;AI 精修经 config_ready=False 桩跳过)。

钉住:trends_history.json 的自愈读(缺/坏/畸形一律 {});rankChange 的基准 =
严格早于 today 的最近一期(同日多批不漂移——必须先附变化再写今日归档);
归档索引倒序截前 90 天;write_trends 的产物矩阵与降级语义。
"""

import json
import os
import sys
from datetime import date, timedelta

import trend_keywords as tk


def _seed(repo, source, d, hm, items):
    p_dir = os.path.join(repo, source, d)
    os.makedirs(p_dir, exist_ok=True)
    p = os.path.join(p_dir, f"{hm}-data.json")
    with open(p, "w", encoding="utf-8") as f:
        json.dump({"items": items}, f, ensure_ascii=False)
    return p


def _hn(title, n):
    return {"title": title, "url": f"https://x/{n}"}


def _seed_baseline(repo, d, terms, hm="22-00"):
    """落一份昨日归档 + 指向它的 trends_history.json。"""
    rel = f"{d}/{hm}-data.json"
    p = os.path.join(repo, "trends", *rel.split("/"))
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        json.dump({"keywords": [{"term": t} for t in terms]}, f, ensure_ascii=False)
    with open(os.path.join(repo, "trends_history.json"), "w", encoding="utf-8") as f:
        json.dump({d: rel}, f, ensure_ascii=False)
    return p


def _read(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _no_ai(monkeypatch):
    """write_trends 内部会尝试 AI 精修,桩掉配置让测试不依赖环境变量。"""
    monkeypatch.setattr(tk, "config_ready", lambda: False)


# ===== _load_trends_history:坏索引自愈 =====

def test_load_history_缺失坏件畸形一律空(tmp_path):
    repo = str(tmp_path)
    assert tk._load_trends_history(repo) == {}  # 文件不存在(首期运行)

    with open(os.path.join(repo, "trends_history.json"), "w") as f:
        f.write("not-json")
    assert tk._load_trends_history(repo) == {}  # 损坏:按无历史处理,下批自愈

    with open(os.path.join(repo, "trends_history.json"), "w") as f:
        json.dump(["not", "a", "dict"], f)
    assert tk._load_trends_history(repo) == {}

    with open(os.path.join(repo, "trends_history.json"), "w") as f:
        json.dump({"d": "ok", "x": None, "y": 3}, f)
    assert tk._load_trends_history(repo) == {"d": "ok"}  # 非字符串值过滤


# ===== attach_rank_changes:基准与标记 =====

def test_attach_在榜算rankChange新词标isNewEntry(tmp_path):
    repo = str(tmp_path)
    _seed_baseline(repo, "2026-08-28", ["aaa", "bbb"])
    trends = {"keywords": [{"term": "bbb"}, {"term": "ccc"}]}

    assert tk.attach_rank_changes(trends, repo, "2026-08-29") == "2026-08-28"
    assert trends["keywords"][0]["rankChange"] == 1   # 第2名 → 第1名
    assert trends["keywords"][1]["isNewEntry"] is True
    assert "rankChange" not in trends["keywords"][1]
    assert "isNewEntry" not in trends["keywords"][0]


def test_attach_基准排除今日早批(tmp_path):
    repo = str(tmp_path)
    # 今日早批已写归档(zzz 第一),但基准必须取昨日最后一期
    _seed_baseline(repo, "2026-08-29", ["zzz"], hm="08-00")
    _seed_baseline(repo, "2026-08-28", ["aaa", "bbb"])
    trends = {"keywords": [{"term": "bbb"}]}
    with open(os.path.join(repo, "trends_history.json"), "w") as f:
        json.dump({"2026-08-29": "2026-08-29/08-00-data.json",
                   "2026-08-28": "2026-08-28/22-00-data.json"}, f)

    assert tk.attach_rank_changes(trends, repo, "2026-08-29") == "2026-08-28"
    assert trends["keywords"][0]["rankChange"] == 1  # 按 08-28 榜(bbb 第2),非今日 zzz 榜


def test_attach_无基准坏归档空基准榜均不加字段(tmp_path):
    repo = str(tmp_path)
    trends = {"keywords": [{"term": "x"}]}
    assert tk.attach_rank_changes(trends, repo, "2026-08-29") is None  # 无索引
    assert trends["keywords"][0] == {"term": "x"}

    _seed_baseline(repo, "2026-08-29", ["today-only"])  # 只有今日,无更早基准
    assert tk.attach_rank_changes(trends, repo, "2026-08-29") is None
    assert trends["keywords"][0] == {"term": "x"}

    p = _seed_baseline(repo, "2026-08-28", ["aaa"])
    with open(p, "w") as f:
        f.write("broken")  # 基准归档损坏
    assert tk.attach_rank_changes(trends, repo, "2026-08-29") is None
    assert trends["keywords"][0] == {"term": "x"}

    _seed_baseline(repo, "2026-08-27", [])  # 基准榜为空:无可比排名
    with open(os.path.join(repo, "trends_history.json"), "w") as f:
        json.dump({"2026-08-28": "2026-08-28/22-00-data.json",
                   "2026-08-27": "2026-08-27/22-00-data.json"}, f)
    assert tk.attach_rank_changes(trends, repo, "2026-08-29") is None


# ===== _write_trends_archive:版式与保留期 =====

def test_write_archive_版式同日覆盖与90天保留(frozen_now, tmp_path):
    repo = str(tmp_path)
    base = date(2026, 5, 26)
    seeded = {(base + timedelta(days=i)).strftime("%Y-%m-%d"):
              (base + timedelta(days=i)).strftime("%Y-%m-%d") + "/22-00-data.json"
              for i in range(95)}  # 05-26 ~ 08-28
    seeded["2026-08-29"] = "2026-08-29/08-00-data.json"  # 今日早批指针
    with open(os.path.join(repo, "trends_history.json"), "w") as f:
        json.dump(seeded, f)

    tk._write_trends_archive(repo, {"keywords": []}, frozen_now)

    arc = os.path.join(repo, "trends", "2026-08-29", "11-01-data.json")
    assert _read(arc) == {"keywords": []}
    idx = _read(os.path.join(repo, "trends_history.json"))
    assert idx["2026-08-29"] == "2026-08-29/11-01-data.json"  # 同日指针被本批覆盖
    assert len(idx) == tk.TRENDS_RETENTION_DAYS               # 倒序截前 90 天
    assert "2026-06-01" in idx and "2026-05-31" not in idx    # 最旧 6 天被裁


# ===== write_trends:端到端 =====

def test_write_trends_首跑产物矩阵与内容一致(frozen_now, monkeypatch, tmp_path):
    _no_ai(monkeypatch)
    repo = str(tmp_path)
    _seed(repo, "hackernews", "2026-08-27", "22-00",
          [_hn("OpenAI a", 1), _hn("OpenAI b", 2), _hn("Claude c", 3)])
    _seed(repo, "hackernews", "2026-08-28", "22-00",
          [_hn("OpenAI c", 4), _hn("Claude d", 5), _hn("Claude e", 6)])

    assert tk.write_trends(repo) is True
    trends = _read(os.path.join(repo, "trends.json"))
    cloud = _read(os.path.join(repo, "trends_cloud.json"))
    arc = _read(os.path.join(repo, "trends", "2026-08-29", "11-01-data.json"))
    idx = _read(os.path.join(repo, "trends_history.json"))

    assert trends == arc  # 归档与根级文件同内容
    assert idx == {"2026-08-29": "2026-08-29/11-01-data.json"}
    assert cloud["generatedAt"] == trends["generatedAt"] == int(frozen_now.timestamp() * 1000)
    assert [k["term"] for k in trends["keywords"]] == ["claude", "openai"]  # 同分值按 canon 序
    for kw in trends["keywords"]:  # 无历史基准:不附任何变化字段
        assert "rankChange" not in kw and "isNewEntry" not in kw


def test_write_trends_跨日重跑涨跌与同日基准稳定(frozen_now, monkeypatch, tmp_path):
    _no_ai(monkeypatch)
    repo = str(tmp_path)
    day1 = frozen_now.replace(day=28, hour=22, minute=0)

    # 第一天(08-28 22:00):openai 动量高排第 1
    _seed(repo, "hackernews", "2026-08-16", "22-00", [_hn("Claude c", 1)])
    _seed(repo, "hackernews", "2026-08-27", "22-00", [_hn("OpenAI a", 2), _hn("OpenAI b", 3)])
    _seed(repo, "hackernews", "2026-08-28", "22-00", [_hn("OpenAI c", 4), _hn("Claude a", 5), _hn("Claude b", 6)])
    monkeypatch.setattr(tk, "now_cst", lambda: day1)
    assert tk.write_trends(repo) is True
    first = _read(os.path.join(repo, "trends.json"))
    assert [k["term"] for k in first["keywords"]] == ["openai", "claude"]

    # 第二天(08-29 11:01)重写快照分布,claude 动量反超
    import shutil
    shutil.rmtree(os.path.join(repo, "hackernews"))
    _seed(repo, "hackernews", "2026-08-16", "22-00", [_hn("OpenAI c", 1)])
    _seed(repo, "hackernews", "2026-08-27", "22-00", [_hn("Claude a", 2), _hn("Claude b", 3)])
    _seed(repo, "hackernews", "2026-08-28", "22-00", [_hn("Claude c", 4), _hn("OpenAI a", 5), _hn("OpenAI b", 6)])
    monkeypatch.setattr(tk, "now_cst", lambda: frozen_now)
    assert tk.write_trends(repo) is True
    second = _read(os.path.join(repo, "trends.json"))
    kws = second["keywords"]
    assert [k["term"] for k in kws] == ["claude", "openai"]
    assert kws[0]["rankChange"] == 1 and kws[1]["rankChange"] == -1  # vs 08-28 榜

    # 同日再跑一批:基准仍是 08-28 最后一期,而不是今日 11-01 早批
    assert tk.write_trends(repo) is True
    again = _read(os.path.join(repo, "trends.json"))
    assert again["keywords"][0]["rankChange"] == 1
    assert again["keywords"][1]["rankChange"] == -1


def test_write_trends_空仓库返回False不落盘(frozen_now, monkeypatch, tmp_path):
    _no_ai(monkeypatch)
    repo = str(tmp_path / "repo")
    os.makedirs(repo)
    assert tk.write_trends(repo) is False
    assert not os.path.exists(os.path.join(repo, "trends.json"))


def test_main_dry_run只读不写(frozen_now, monkeypatch, tmp_path, capsys):
    _no_ai(monkeypatch)
    repo = str(tmp_path)
    _seed(repo, "hackernews", "2026-08-27", "22-00",
          [_hn("OpenAI a", 1), _hn("OpenAI b", 2), _hn("Claude c", 3)])
    _seed(repo, "hackernews", "2026-08-28", "22-00",
          [_hn("OpenAI c", 4), _hn("Claude d", 5), _hn("Claude e", 6)])
    monkeypatch.setattr(sys, "argv", ["trend_keywords.py", "--repo-dir", repo, "--dry-run"])

    assert tk.main() == 0
    for name in ("trends.json", "trends_cloud.json", "trends_history.json"):
        assert not os.path.exists(os.path.join(repo, name))
    out = capsys.readouterr().out
    assert "统计回退" in out and "(dry-run,未写" in out
