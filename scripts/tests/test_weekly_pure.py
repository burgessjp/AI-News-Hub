"""weekly_digest 纯函数回归:周界数学 / 周输入组装 / prompt 组装 / 校验组装。

零网络零 AI(config_ready / call_llm 均不触达);夹具用 tmp_path 现造
overview 归档与索引,钉死「幂等闸之外的确定性骨架」:
 - _last_week_range 恒指向已完结周(周一 00:00 后任何时刻);
 - _load_week_input 索引寻址取每日最后一期、缺日容忍、coverage 闸、
   url 跨日去重(daysSeen 计数 / date 取最近日 / breaking 任一日命中即真);
 - _build_prompts 含全部候选 url 与输出 schema;
 - _validate_and_build 的全部丢弃分支与整体失败阈值,以及 item 回填与
   候选池逐字段一致(防幻觉回归锚)。
"""

import json
from datetime import datetime, timedelta

import pytest

import weekly_digest as wd
from common import BEIJING_TZ


def _mk_item(url, title=None, source="hackernews", breaking=False,
             metrics="得分 100 · 评论 10", comment="一句话点评"):
    return {
        "source": source,
        "title": title or f"条目 {url}",
        "url": url,
        "metrics": metrics,
        "comment": comment,
        "breaking": breaking,
        "breakingReason": "重大发布" if breaking else "",
    }


def _mk_repo_day(repo_dir, date, items, digest=None, hhmm="08-00"):
    """在 tmp repo 里落一天 overview 归档(顶层 digest + items)。"""
    day_dir = repo_dir / "overview" / date
    day_dir.mkdir(parents=True, exist_ok=True)
    data = {"digest": digest or f"{date} 的综述", "items": items}
    (day_dir / f"{hhmm}-data.json").write_text(
        json.dumps(data, ensure_ascii=False), encoding="utf-8")


def _mk_history(repo_dir, mapping):
    (repo_dir / "overview_history.json").write_text(
        json.dumps(mapping, ensure_ascii=False), encoding="utf-8")


def _week_dates(monday):
    base = datetime.strptime(monday, "%Y-%m-%d").date()
    return [(base + timedelta(days=i)).isoformat() for i in range(7)]


MONDAY = "2026-10-12"  # 上一个已完结周的周一(相对 2026-10-24 周六)
WEEK = _week_dates(MONDAY)


# ===== 周界数学:_last_week_range =====

@pytest.mark.parametrize("now,expect_monday,expect_sunday", [
    # 周一 00:01:上周刚结束,立刻指向上一整个周
    (datetime(2026, 10, 19, 0, 1, tzinfo=BEIJING_TZ), "2026-10-12", "2026-10-18"),
    # 周日 23:59:本周未结束,「上周」仍是再上一周
    (datetime(2026, 10, 18, 23, 59, tzinfo=BEIJING_TZ), "2026-10-05", "2026-10-11"),
    # 周中(周三)
    (datetime(2026, 10, 14, 12, 0, tzinfo=BEIJING_TZ), "2026-10-05", "2026-10-11"),
    # 跨年周:2026-12-28 周一 ~ 2027-01-03 周日
    (datetime(2027, 1, 4, 8, 0, tzinfo=BEIJING_TZ), "2026-12-28", "2027-01-03"),
])
def test_last_week_range_恒指向已完结周(now, expect_monday, expect_sunday):
    monday, sunday = wd._last_week_range(now)
    assert monday.isoformat() == expect_monday
    assert sunday.isoformat() == expect_sunday


# ===== 期号:_issue_no(自 WEEKLY_EPOCH 确定性推算) =====

def test_issue_no_自纪元周推算():
    assert wd._issue_no(wd.WEEKLY_EPOCH) == 1
    assert wd._issue_no("2026-10-12") == 4


def test_issue_no_非周一对齐报错():
    with pytest.raises(ValueError):
        wd._issue_no("2026-10-13")


# ===== 周输入组装:_load_week_input =====

def test_load_week_input_全周读取与每日综述(tmp_path):
    for i, d in enumerate(WEEK):
        _mk_repo_day(tmp_path, d, [_mk_item(f"https://x/{i}")], digest=f"{d}综述")
    _mk_history(tmp_path, {d: f"{d}/08-00-data.json" for d in WEEK})
    got = wd._load_week_input(tmp_path, WEEK[0], WEEK[6])
    assert got is not None
    assert got["coverage"] == 7
    assert got["coverageStart"] == WEEK[0] and got["coverageEnd"] == WEEK[6]
    assert [x["date"] for x in got["daily_digests"]] == WEEK
    assert got["daily_digests"][0]["digest"] == f"{WEEK[0]}综述"
    assert len(got["pool"]) == 7


def test_load_week_input_url跨日去重与daysSeen(tmp_path):
    # 同一 url 三天在榜:合并为一条,daysSeen=3,date 取最近出现日,breaking 任一日真即真
    _mk_repo_day(tmp_path, WEEK[0], [_mk_item("https://x/a"), _mk_item("https://x/b")])
    _mk_repo_day(tmp_path, WEEK[1], [_mk_item("https://x/a", breaking=True)])
    _mk_repo_day(tmp_path, WEEK[2], [_mk_item("https://x/a")])
    _mk_repo_day(tmp_path, WEEK[3], [_mk_item("https://x/c")])
    _mk_repo_day(tmp_path, WEEK[4], [_mk_item("https://x/d")])
    _mk_history(tmp_path, {d: f"{d}/08-00-data.json" for d in WEEK[:5]})
    got = wd._load_week_input(tmp_path, WEEK[0], WEEK[6])
    assert got["coverage"] == 5
    by_url = {it["url"]: it for it in got["pool"]}
    assert by_url["https://x/a"]["daysSeen"] == 3
    assert by_url["https://x/a"]["date"] == WEEK[2]
    assert by_url["https://x/a"]["breaking"] is True
    assert by_url["https://x/b"]["daysSeen"] == 1


def test_load_week_input_索引缺失回退目录扫描(tmp_path):
    for i, d in enumerate(WEEK[:6]):
        _mk_repo_day(tmp_path, d, [_mk_item(f"https://x/{i}")])
    # 不写 overview_history.json:回退扫 overview/<date>/ 取字典序最大文件
    got = wd._load_week_input(tmp_path, WEEK[0], WEEK[6])
    assert got is not None and got["coverage"] == 6


def test_load_week_input_索引损坏回退目录扫描(tmp_path):
    for i, d in enumerate(WEEK):
        _mk_repo_day(tmp_path, d, [_mk_item(f"https://x/{i}")])
    (tmp_path / "overview_history.json").write_text("not-json{", encoding="utf-8")
    got = wd._load_week_input(tmp_path, WEEK[0], WEEK[6])
    assert got is not None and got["coverage"] == 7


def test_load_week_input_每日取最后一期(tmp_path):
    # 同日多批:索引指 18-00,候选以最后一期为准(08-00 的条目不进池)
    _mk_repo_day(tmp_path, WEEK[0], [_mk_item("https://x/early")], hhmm="08-00")
    _mk_repo_day(tmp_path, WEEK[0], [_mk_item("https://x/late")], hhmm="18-00")
    for d in WEEK[1:6]:
        _mk_repo_day(tmp_path, d, [_mk_item(f"https://x/{d}")])
    _mk_history(tmp_path, {**{d: f"{d}/08-00-data.json" for d in WEEK[1:6]},
                           WEEK[0]: f"{WEEK[0]}/18-00-data.json"})
    got = wd._load_week_input(tmp_path, WEEK[0], WEEK[6])
    urls = {it["url"] for it in got["pool"]}
    assert "https://x/late" in urls and "https://x/early" not in urls


def test_load_week_input_索引指向的文件缺失跳过该日(tmp_path):
    for d in WEEK[:5]:
        _mk_repo_day(tmp_path, d, [_mk_item(f"https://x/{d}")])
    # 索引含 WEEK[5] 但文件不存在:该日不计 coverage
    _mk_history(tmp_path, {**{d: f"{d}/08-00-data.json" for d in WEEK[:5]},
                           WEEK[5]: f"{WEEK[5]}/08-00-data.json"})
    got = wd._load_week_input(tmp_path, WEEK[0], WEEK[6])
    assert got["coverage"] == 5


def test_load_week_input_coverage不足返回None(tmp_path, monkeypatch):
    for d in WEEK[:4]:  # 4 < MIN_COVERAGE_DAYS=5
        _mk_repo_day(tmp_path, d, [_mk_item(f"https://x/{d}")])
    _mk_history(tmp_path, {d: f"{d}/08-00-data.json" for d in WEEK[:4]})
    assert wd._load_week_input(tmp_path, WEEK[0], WEEK[6]) is None


# ===== prompt 组装:_build_prompts =====

def _full_week_input(tmp_path):
    # 每天 2 条独立 url:候选池 14 条,足够拼 3 板块 × 3 条目的合法 AI 输出
    for i, d in enumerate(WEEK):
        _mk_repo_day(tmp_path, d, [_mk_item(f"https://x/{i}", breaking=(i == 0)),
                                   _mk_item(f"https://x/b{i}")],
                     digest=f"{d}综述")
    _mk_history(tmp_path, {d: f"{d}/08-00-data.json" for d in WEEK})
    return wd._load_week_input(tmp_path, WEEK[0], WEEK[6])


def test_build_prompts_含全部候选url与综述(tmp_path):
    got = _full_week_input(tmp_path)
    system, user = wd._build_prompts(got)
    for it in got["pool"]:
        assert it["url"] in user
    for d in got["daily_digests"]:
        assert d["digest"] in user
    # 输出 schema 与防幻觉规则必须在场
    assert '"digest"' in user and '"sections"' in user and '"refs"' in user
    assert "不得编造" in user
    assert "周刊" in system


# ===== 校验与组装:_validate_and_build =====

def _ai_ok(pool):
    """由候选池前 9 条拼一份 3 板块 × 3 条目的合法 AI 输出。"""
    refs = [it["url"] for it in pool[:9]]
    return {
        "digest": "本周主线明确。" * 8,
        "sections": [
            {"title": "模型发布", "summary": "这条线本周快速演进。" * 4,
             "refs": refs[0:3]},
            {"title": "开源生态", "summary": "开源社区动作频频。" * 4,
             "refs": refs[3:6]},
            {"title": "行业动向", "summary": "行业格局出现变化。" * 4,
             "refs": refs[6:9]},
        ],
    }


def test_validate_and_build_合法输出回填完整条目(tmp_path):
    got = _full_week_input(tmp_path)
    pool_by_url = {it["url"]: it for it in got["pool"]}
    ai = _ai_ok(got["pool"])
    now = datetime(2026, 10, 19, 8, 0, tzinfo=BEIJING_TZ)
    weekly = wd._validate_and_build(ai, got, now)
    assert weekly["weekStart"] == MONDAY
    assert weekly["weekEnd"] == WEEK[6]
    assert weekly["coverageDays"] == 7
    assert weekly["coverageStart"] == WEEK[0] and weekly["coverageEnd"] == WEEK[6]
    assert weekly["issueNo"] == 4
    assert weekly["generatedAt"] == int(now.timestamp() * 1000)
    assert weekly["promptVersion"] == wd.WEEKLY_PROMPT_VERSION
    assert weekly["schemaVersion"] == 1
    assert weekly["digest"] == ai["digest"]
    assert len(weekly["sections"]) == 3
    # 防幻觉回归锚:回填条目与候选池原对象逐字段一致
    sec0_items = weekly["sections"][0]["items"]
    for item in sec0_items:
        origin = pool_by_url[item["url"]]
        for field in ("title", "source", "metrics", "comment", "breaking", "daysSeen"):
            assert item[field] == origin[field]


def test_validate_and_build_未知ref丢弃板块塌到2条则丢板块(tmp_path):
    got = _full_week_input(tmp_path)
    ai = _ai_ok(got["pool"])
    # 第 3 板块 3 个 ref 换成 2 个编造 + 1 个真:存活 1 < 3 → 整板块丢弃 → 只剩 2 板块
    ai["sections"][2]["refs"] = ["https://fake/1", "https://fake/2", got["pool"][8]["url"]]
    with pytest.raises(RuntimeError):
        wd._validate_and_build(ai, got, datetime(2026, 10, 19, 8, 0, tzinfo=BEIJING_TZ))


def test_validate_and_build_跨板块重复url被拦(tmp_path):
    got = _full_week_input(tmp_path)
    ai = _ai_ok(got["pool"])
    # 第 3 板块引用第 1 板块已用的 url:第二处出现被丢弃,板块塌到 2 → 整体失败
    ai["sections"][2]["refs"] = [got["pool"][6]["url"], got["pool"][0]["url"],
                                 got["pool"][7]["url"]]
    with pytest.raises(RuntimeError):
        wd._validate_and_build(ai, got, datetime(2026, 10, 19, 8, 0, tzinfo=BEIJING_TZ))


def test_validate_and_build_digest缺失失败(tmp_path):
    got = _full_week_input(tmp_path)
    ai = _ai_ok(got["pool"])
    ai["digest"] = "  "
    with pytest.raises(RuntimeError):
        wd._validate_and_build(ai, got, datetime(2026, 10, 19, 8, 0, tzinfo=BEIJING_TZ))


def test_validate_and_build_digest超长从宽到1_5倍(tmp_path):
    got = _full_week_input(tmp_path)
    ai = _ai_ok(got["pool"])
    ai["digest"] = "长" * int(wd.DIGEST_MAX_CHARS * 1.5)  # 恰在上限内:放行
    weekly = wd._validate_and_build(ai, got, datetime(2026, 10, 19, 8, 0, tzinfo=BEIJING_TZ))
    assert weekly["digest"] == ai["digest"]
    ai["digest"] = "长" * int(wd.DIGEST_MAX_CHARS * 1.5 + 1)  # 超限:失败
    with pytest.raises(RuntimeError):
        wd._validate_and_build(ai, got, datetime(2026, 10, 19, 8, 0, tzinfo=BEIJING_TZ))


def test_validate_and_build_板块超6截断_ref超6截断(tmp_path):
    got = _full_week_input(tmp_path)
    # 造 7 板块 × 7 ref 的极胖输出(候选池要够:补足 49 条独立 url)
    for i in range(49):
        got["pool"].append({"url": f"https://x/extra/{i}", "title": f"额外条目 {i}",
                            "source": "hackernews", "date": WEEK[6],
                            "metrics": "得分 100", "comment": "点评",
                            "breaking": False, "daysSeen": 1})
    ai = {"digest": "综述。" * 10, "sections": [
        {"title": f"板块{i}", "summary": "短评。" * 10,
         "refs": [it["url"] for it in got["pool"][i * 7:i * 7 + 7]]}
        for i in range(7)
    ]}
    weekly = wd._validate_and_build(ai, got, datetime(2026, 10, 19, 8, 0, tzinfo=BEIJING_TZ))
    assert len(weekly["sections"]) == 6
    assert all(len(s["items"]) <= 6 for s in weekly["sections"])


def test_validate_and_build_板块title超长或summary空被丢弃(tmp_path):
    got = _full_week_input(tmp_path)
    ai = _ai_ok(got["pool"])
    ai["sections"][0]["title"] = "超过十二个字符的超长板块标题啦"  # >12 字
    ai["sections"][1]["summary"] = ""  # 空短评
    with pytest.raises(RuntimeError):  # 2 板块被丢 → 剩 1 < 3
        wd._validate_and_build(ai, got, datetime(2026, 10, 19, 8, 0, tzinfo=BEIJING_TZ))


def test_validate_and_build_总条目不足9失败(tmp_path):
    got = _full_week_input(tmp_path)
    ai = _ai_ok(got["pool"])
    ai["sections"][2]["refs"] = [got["pool"][6]["url"]]  # 1 条 < 3 → 板块被丢
    ai["sections"].append({"title": "补位板块", "summary": "补位。" * 10,
                           "refs": [got["pool"][0]["url"]]})  # 又一个塌板块
    # 3 板块 × 3 = 9 恰好过线的前提被破坏:2 板块 × 3 = 6 < 9
    ai["sections"] = [s for s in ai["sections"][:2]] + [
        {"title": "第三板块", "summary": "短评。" * 10,
         "refs": [got["pool"][6]["url"], got["pool"][7]["url"]]}]  # 2 条 → 塌
    with pytest.raises(RuntimeError):
        wd._validate_and_build(ai, got, datetime(2026, 10, 19, 8, 0, tzinfo=BEIJING_TZ))
