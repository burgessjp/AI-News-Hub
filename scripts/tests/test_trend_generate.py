"""trend_keywords.generate_trends 统计核心回归(tmp_path 合成快照,零网络)。

钉住:窗口锚定「快照最大日期」而非运行时刻、入榜门槛(total/daysActive)、
同条同词只计一次、动量排序、自由 unigram 护栏(进池不进榜)、词云结构、
坏快照容忍与两路 None。窗口/命中模式用合成快照构造——统计语义需要
受控命中序列,真实批次 fixture 给不了确定性。
"""

import json
import os

import trend_keywords as tk


def _seed(repo, source, date, hm, items):
    """落一份最小快照(只有 generate_trends 会读的 items 字段)。"""
    d = os.path.join(repo, source, date)
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, f"{hm}-data.json")
    with open(p, "w", encoding="utf-8") as f:
        json.dump({"items": items}, f, ensure_ascii=False)
    return p


def _hn(title, n):
    return {"title": title, "url": f"https://x/{n}"}


def _raw_file(path):
    with open(path, "w", encoding="utf-8") as f:
        f.write("not-json")


def _terms(entries):
    return [k["term"] for k in entries]


# ===== _iter_daily_snapshots =====

def test_iter_snapshots_只认已知源与定宽格式且同日取末班(tmp_path):
    repo = str(tmp_path)
    _seed(repo, "hackernews", "2026-08-28", "08-00", [])
    last = _seed(repo, "hackernews", "2026-08-28", "22-00", [])
    _seed(repo, "hackernews", "2026-8-28", "23-00", [])   # 非定宽日期
    _seed(repo, "hackernews", "not-a-date", "23-00", [])
    _seed(repo, "hackernews", "2026-08-28", "8-00", [])    # 非定宽时刻
    _seed(repo, "unknown-source", "2026-08-28", "22-00", [])  # 不在 SOURCE_KEYS
    os.makedirs(os.path.join(repo, "hackernews", "stray"), exist_ok=True)

    got = tk._iter_daily_snapshots(repo)
    assert set(got) == {"hackernews"}
    assert got["hackernews"] == {"2026-08-28": last}


# ===== generate_trends:窗口与门槛 =====

def test_generate_窗口锚定快照最大日期而非now(frozen_now, tmp_path):
    repo = str(tmp_path)
    _seed(repo, "hackernews", "2026-08-16", "22-00", [_hn("OpenAI a", 1), _hn("OpenAI b", 2)])
    _seed(repo, "hackernews", "2026-08-28", "22-00", [_hn("OpenAI c", 3)])

    trends, cloud, pool = tk.generate_trends(repo)
    # 锚点 = 08-28,窗口 14 天 = 08-15 ~ 08-28;frozen_now 只影响 generatedAt
    assert trends["days"][0] == "2026-08-15"
    assert trends["days"][-1] == "2026-08-28"
    assert trends["windowDays"] == 14
    assert trends["generatedAt"] == int(frozen_now.timestamp() * 1000)


def test_generate_门槛滤掉单日闪现与总量不足(frozen_now, tmp_path):
    repo = str(tmp_path)
    _seed(repo, "hackernews", "2026-08-27", "22-00",
          [_hn("OpenAI a", 1), _hn("OpenAI b", 2), _hn("Claude a", 3), _hn("Claude b", 4),
           _hn("Gemini a", 5)])
    _seed(repo, "hackernews", "2026-08-28", "22-00",
          [_hn("OpenAI c", 6), _hn("Gemini b", 7)])

    trends, cloud, pool = tk.generate_trends(repo)
    # OpenAI 3 次/2 天过门槛;Claude 2 次全在 1 天(单日闪现);Gemini 2 次/2 天总量不足
    assert _terms(trends["keywords"]) == ["openai"]
    assert _terms(cloud["words"]) == ["openai"]
    assert _terms(pool) == ["openai"]


def test_generate_同条多次出现同词只计一次(frozen_now, tmp_path):
    repo = str(tmp_path)
    _seed(repo, "hackernews", "2026-08-27", "22-00",
          [_hn("openai openai OpenAI", 1)])
    _seed(repo, "hackernews", "2026-08-28", "22-00",
          [_hn("OpenAI b", 2), _hn("OpenAI c", 3)])

    trends, _, _ = tk.generate_trends(repo)
    kw = trends["keywords"][0]
    assert kw["total"] == 3  # 按条目计,不是按出现次数(否则 5)
    assert kw["daysActive"] == 2


# ===== generate_trends:排序 / 护栏 / 榜单长度 =====

def test_generate_动量排序压过总量(frozen_now, tmp_path):
    repo = str(tmp_path)
    # claude 4 次全在窗口前段(动量 0);openai 3 次贴着锚点(动量封顶 2.5)
    _seed(repo, "hackernews", "2026-08-16", "22-00",
          [_hn("Claude a", 1), _hn("Claude b", 2)])
    _seed(repo, "hackernews", "2026-08-17", "22-00",
          [_hn("Claude c", 3), _hn("Claude d", 4)])
    _seed(repo, "hackernews", "2026-08-27", "22-00", [_hn("OpenAI a", 5)])
    _seed(repo, "hackernews", "2026-08-28", "22-00",
          [_hn("OpenAI b", 6), _hn("OpenAI c", 7)])

    trends, _, _ = tk.generate_trends(repo)
    assert _terms(trends["keywords"]) == ["openai", "claude"]


def test_generate_自由unigram降权补位而非除名(frozen_now, tmp_path):
    repo = str(tmp_path)
    _seed(repo, "hackernews", "2026-08-27", "22-00",
          [_hn("OpenAI a", 1), _hn("OpenAI b", 2), _hn("Work", 3)])
    _seed(repo, "hackernews", "2026-08-28", "22-00",
          [_hn("OpenAI c", 4), _hn("Work", 5), _hn("Work", 6)])

    trends, cloud, pool = tk.generate_trends(repo)
    # 护栏词只有 1 个时,work(自由 unigram)按分值序补位进榜,但恒排在护栏词后;
    # 候选池与词云同口径(AI 捞回/词云全景都看得到它)
    assert _terms(trends["keywords"]) == ["openai", "work"]
    assert _terms(pool) == ["openai", "work"]
    assert _terms(cloud["words"]) == ["openai", "work"]


def test_generate_护栏词充足时榜单恒满10(frozen_now, tmp_path):
    repo = str(tmp_path)
    aliases = ["openai", "claude", "gemini", "grok", "mistral", "qwen",
               "kimi", "doubao", "sora", "cursor", "rag"]  # 11 个过护栏的别名词
    day1, day2 = [], []
    for i, t in enumerate(aliases + ["work"]):  # 每词 2+1 次命中/2 天,过门槛
        day1.append(_hn(t, f"{i}a"))
        day2 += [_hn(t, f"{i}b"), _hn(t, f"{i}c")]
    _seed(repo, "hackernews", "2026-08-27", "22-00", day1)
    _seed(repo, "hackernews", "2026-08-28", "22-00", day2)

    trends, cloud, pool = tk.generate_trends(repo)
    assert len(trends["keywords"]) == tk.TOP_KEYWORDS
    assert "work" not in _terms(trends["keywords"])  # 11 个护栏词足够,轮不到补位
    assert len(pool) == 12 and "work" in _terms(pool)


# ===== generate_trends:词云结构与字段 =====

def test_generate_词云结构轻量且generatedAt待填(frozen_now, tmp_path):
    repo = str(tmp_path)
    _seed(repo, "hackernews", "2026-08-27", "22-00",
          [_hn("OpenAI a", 1), _hn("OpenAI b", 2), _hn("Work w1", 3)])
    _seed(repo, "hackernews", "2026-08-28", "22-00",
          [_hn("OpenAI c", 4), _hn("Work w2", 5), _hn("Work w3", 6)])

    trends, cloud, pool = tk.generate_trends(repo)
    assert cloud["generatedAt"] is None      # write_trends 统一填生成时刻
    assert cloud["days"] == trends["days"]
    w = cloud["words"][0]
    assert set(w) == {"term", "display", "total"}  # 不带 daily/items,词云要轻
    kw = trends["keywords"][0]
    assert set(kw) == {"term", "display", "total", "daysActive", "daily", "trend", "items"}
    assert kw["display"] == "OpenAI"          # 别名指定形
    assert kw["daily"][tk.WINDOW_DAYS - 1] == 1  # 08-28 一条命中
    assert kw["daily"][tk.WINDOW_DAYS - 2] == 2  # 08-27 两条命中
    assert kw["trend"] == "up"                # 近3日 3 次 vs 前3日 0


# ===== generate_trends:窗口外与坏快照容忍 =====

def test_generate_窗口外历史不统计(frozen_now, tmp_path):
    repo = str(tmp_path)
    _seed(repo, "hackernews", "2026-08-27", "22-00", [_hn("OpenAI a", 1), _hn("OpenAI b", 2)])
    _seed(repo, "hackernews", "2026-08-28", "22-00", [_hn("OpenAI c", 3)])
    # 窗口外(锚点 08-28 的 14 天窗口始于 08-15)的 claude 不该被看到
    _seed(repo, "hackernews", "2026-07-30", "22-00",
          [_hn("Claude a", 4), _hn("Claude b", 5), _hn("Claude c", 6)])

    trends, _, _ = tk.generate_trends(repo)
    assert "claude" not in _terms(trends["keywords"])


def test_generate_坏快照与畸形items容忍跳过(frozen_now, tmp_path):
    repo = str(tmp_path)
    _seed(repo, "hackernews", "2026-08-26", "22-00", [_hn("OpenAI a", 1), _hn("OpenAI b", 2)])
    _raw_file(_seed(repo, "hackernews", "2026-08-27", "08-00", []))
    _seed(repo, "hackernews", "2026-08-27", "22-00", [{"items": "not-a-list"}])
    p = _seed(repo, "hackernews", "2026-08-28", "22-00",
              ["plain-string", _hn("OpenAI c", 3), {"no": "fields"}])
    assert os.path.isfile(p)
    # 08-27 两份坏文件(取末班 22-00 的 items 非列表)与 08-28 的畸形 item 均被跳过,
    # 但 08-26/08-28 的正常命中照常统计:仍 2 天 3 次过门槛
    trends, _, _ = tk.generate_trends(repo)
    assert _terms(trends["keywords"]) == ["openai"]
    kw = trends["keywords"][0]
    assert kw["total"] == 3
    assert kw["daily"][tk.WINDOW_DAYS - 3] == 2  # 08-26
    assert kw["daily"][tk.WINDOW_DAYS - 2] == 0  # 08-27:末班快照坏,整日落空
    assert kw["daily"][tk.WINDOW_DAYS - 1] == 1  # 08-28:仅正常 item 计入


def test_generate_空仓库与无合格词均返回None(frozen_now, tmp_path):
    repo = str(tmp_path / "repo")
    os.makedirs(repo)
    assert tk.generate_trends(repo) is None

    _seed(repo, "hackernews", "2026-08-28", "22-00", [_hn("OpenAI a", 1)])  # 单日 1 次
    assert tk.generate_trends(repo) is None
