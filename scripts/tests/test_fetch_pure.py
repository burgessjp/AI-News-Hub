"""fetch_data.py 纯函数回归(零网络零落盘)。

覆盖:parse_count / rundown 三件套(RSC 提取、RSC→items、DOM 兜底)/
厂商新闻日期解析四件套 / 索引结构清洗 / _retain_recent 历史合并矩阵。
_retain_recent 是 history.json 与 overview_history.json 继承语义的唯一实现点,
矩阵钉死后拆分阶段不许漂移。
"""

import fetch_data as fd


# ===== parse_count =====

def test_parse_count_逗号千分位与空白():
    assert fd.parse_count("64,846") == 64846
    assert fd.parse_count(" 12 ") == 12
    assert fd.parse_count("") == 0
    assert fd.parse_count(None) == 0


def test_parse_count_非纯数字归零():
    assert fd.parse_count("1,234 stars") == 0
    assert fd.parse_count("-5") == 0
    assert fd.parse_count("12.5") == 0


# ===== rundown:RSC flight payload 提取 =====

# 模拟 /articles 页 <script> 里的 next_f push:articles 数组整体被 JS 转义
RSC_HTML = (
    'self.__next_f.push([1,"1:[\\"$\\",\\"$L2\\"]"]);'
    'self.__next_f.push([1,"c:\\"articles\\":[{'
    '\\"slug\\":\\"first-post\\",\\"title\\":\\"First\\",'
    '\\"subtitle\\":\\"PLUS: bonus line\\",'
    '\\"authors\\":[\\"Ann Lee\\",\\"Bob Wu\\",\\"Cal Ra\\"],'
    '\\"thumbnailUrl\\":\\"https://img.example/1.jpg\\",'
    '\\"publishDate\\":\\"2026-08-29T01:30:00.000Z\\"}]"]);'
    'self.__next_f.push([1,"d:\\"articles\\":[{'
    '\\"slug\\":\\"second-post\\",\\"title\\":\\"Second\\",'
    '\\"subtitle\\":\\"plain\\",\\"authors\\":[],'
    '\\"thumbnailUrl\\":\\"\\",\\"publishDate\\":\\"not-a-date\\"}]"]);'
    'self.__next_f.push([1,"e:\\"articles\\":[{\\"slug\\":\\"first-post\\",'
    '\\"title\\":\\"重复 slug 应被去重\\"}]"]);'
)


def test_extract_rundown_rsc_多段合并保留字典项():
    articles = fd._extract_rundown_rsc_articles(RSC_HTML)
    slugs = [a["slug"] for a in articles]
    # 三段各 1 篇,合并为 3(去重发生在 items_from_rsc,提取层不去重)
    assert slugs == ["first-post", "second-post", "first-post"]


def test_extract_rundown_rsc_无锚点或坏段返回空():
    assert fd._extract_rundown_rsc_articles("<html>无 payload</html>") == []
    # 括号不配平的段被跳过,不抛错
    bad = 'x \\"articles\\":[{\\"slug\\":\\"unterminated\\"'
    assert fd._extract_rundown_rsc_articles(bad) == []


def test_rundown_items_from_rsc_去重_plus_剥离_时区换算():
    items = fd._rundown_items_from_rsc(RSC_HTML)
    assert len(items) == 2  # 重复 slug 的第三段被去重

    first = items[0]
    assert first["slug"] == "first-post"
    assert first["rank"] == 1
    assert first["url"] == "https://www.therundown.ai/articles/first-post"
    assert first["subtitle"] == "bonus line"  # 「PLUS: 」前缀剥掉
    assert first["authors"] == "Ann Lee, +2"
    assert first["coverUrl"] == "https://img.example/1.jpg"
    # publishDate UTC → 北京时间:01:30Z = 09:30 CST
    assert first["publishedAt"] == "2026-08-29 09:30"

    second = items[1]
    assert second["authors"] == ""  # 空作者组 → 空串
    assert second["publishedAt"] == ""  # 坏日期解析失败留空,不抛错
    assert second["rank"] == 2


def test_rundown_authors_str_压成首作者加_n():
    assert fd._rundown_authors_str(["Zach Mink"]) == "Zach Mink"
    assert fd._rundown_authors_str(["Zach Mink", "A", "B", "C"]) == "Zach Mink, +3"
    assert fd._rundown_authors_str([]) == ""
    assert fd._rundown_authors_str(["  ", "Ann"]) == "Ann"  # 空白名被滤掉
    assert fd._rundown_authors_str("not-a-list") == "not-a-list"  # 非数组原样
    assert fd._rundown_authors_str(123) == ""


# ===== rundown:DOM 兜底 =====

def test_rundown_items_from_dom_卡片字段与去重():
    from bs4 import BeautifulSoup

    html = (
        '<a href="/articles/dom-post?utm=x">'
        "<h3>Dom Title</h3>"
        "<p>PLUS: dom sub</p>"
        "<p>Ann \u2022 5 minutes</p>"
        '<img src="https://beehiiv-images-production.s3/pic.jpg">'
        "</a>"
        '<a href="/articles/dom-post"><h3>重复卡</h3></a>'
        '<a href="/articles/no-title"><span>无 h3 标题跳过</span></a>'
        '<a href="https://elsewhere.com/x"><h3>非本站链接跳过</h3></a>'
    )
    items = fd._rundown_items_from_dom(BeautifulSoup(html, "lxml"))
    assert len(items) == 1
    item = items[0]
    assert item["slug"] == "dom-post"
    assert item["title"] == "Dom Title"
    assert item["subtitle"] == "dom sub"
    assert item["authors"] == "Ann"
    assert item["coverUrl"] == "https://beehiiv-images-production.s3/pic.jpg"
    assert item["publishedAt"] == ""  # DOM 兜底路径天然无日期
    assert item["rank"] == 1


# ===== 厂商新闻日期解析 =====

def test_anthropic_date_to_iso_缩写月():
    assert fd._anthropic_date_to_iso("Jul 22, 2026") == "2026-07-22"
    assert fd._anthropic_date_to_iso("Dec 1, 2025") == "2025-12-01"
    assert fd._anthropic_date_to_iso("22/07/2026") == ""
    assert fd._anthropic_date_to_iso("") == ""


def test_claude_date_to_iso_缩写月与全月名():
    assert fd._claude_date_to_iso("Jul 24, 2026") == "2026-07-24"
    assert fd._claude_date_to_iso("June 18, 2026") == "2026-06-18"
    assert fd._claude_date_to_iso("Frob 1, 2026") == ""


def test_parse_engineering_date_两级回退():
    assert fd._parse_engineering_date("Jul 22, 2026") == "2026-07-22"
    assert fd._parse_engineering_date("2026-05-25") == "2026-05-25"  # ISO 直通
    assert fd._parse_engineering_date("") == ""
    assert fd._parse_engineering_date("3 days ago") == ""


# ===== 上游索引结构清洗(拉到的旧索引可能是任意 JSON) =====

def test_valid_history_map_双层过滤():
    assert fd._valid_history_map("nope") == {}
    assert fd._valid_history_map(None) == {}
    got = fd._valid_history_map({
        "hackernews": {"2026-08-28": "a.json", "bad-date": "", 2026: "x"},
        123: {"2026-08-28": "y"},
        "stormzhang-ai": "not-a-dict",
        "rundown-ai": {"2026-08-28": "r.json"},
    })
    assert got == {
        "hackernews": {"2026-08-28": "a.json"},  # 空路径与非字符串键被滤
        "rundown-ai": {"2026-08-28": "r.json"},
    }


def test_valid_date_map_单层过滤():
    assert fd._valid_date_map([1, 2]) == {}
    got = fd._valid_date_map({"2026-08-28": "o.json", "": "x", "2026-08-27": 9})
    assert got == {"2026-08-28": "o.json"}


# ===== _retain_recent:history / overview_history 继承合并的唯一实现点 =====

def test_retain_recent_本地覆盖旧值并按日期倒序():
    got = fd._retain_recent(
        {"2026-08-27": "old-27.json", "2026-08-28": "old-28.json"},
        {"2026-08-28": "new-28.json", "2026-08-26": "new-26.json"},
        retention_days=31,
    )
    # 同日本地覆盖旧索引;键按日期倒序(dict 插入序,写出的 JSON 可读)
    assert got == {
        "2026-08-28": "new-28.json",
        "2026-08-27": "old-27.json",
        "2026-08-26": "new-26.json",
    }
    assert list(got.keys()) == ["2026-08-28", "2026-08-27", "2026-08-26"]


def test_retain_recent_起始日期下限():
    got = fd._retain_recent(
        {"2026-07-17": "drop.json", "2026-07-18": "keep.json", "2026-07-19": "k2.json"},
        None,
        retention_days=31,
    )
    # HISTORY_START_DATE = 2026-07-18:更早的快照源覆盖不全,索引一律不收录
    assert got == {"2026-07-19": "k2.json", "2026-07-18": "keep.json"}


def test_retain_recent_保留期截断取最新_n_天():
    previous = {f"2026-08-{d:02d}": f"p{d}.json" for d in range(20, 31)}  # 11 天
    previous.update({f"2026-08-{d:02d}": f"p{d}.json" for d in range(1, 10)})  # 再加 9 天
    got = fd._retain_recent(previous, {"2026-08-30": "local.json"}, retention_days=10)
    assert len(got) == 10
    assert got["2026-08-30"] == "local.json"
    assert list(got.keys()) == sorted(got.keys(), reverse=True)
    assert "2026-08-20" not in got  # 最旧的一天被截掉


def test_retain_recent_两侧皆空():
    assert fd._retain_recent(None, None, retention_days=31) == {}
