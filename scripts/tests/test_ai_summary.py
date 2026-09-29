"""ai_summary.py 纯函数回归(零 IO,不触 AI/网络)。

钉住两块契约:
 - titleEcho 条目核验 —— 与 overview_summary 同款失败模式:单次调用选条+写作,
   偶发把 A 事件的描述写到 B 条的 ref 上(总览侧 2026-09 回放实锤过 Cursor
   条目配芯片点评;分源摘要卡整卡都是 AI 文本,错配比总览更伤),错绑整条丢弃、
   缺失从宽(防模型整体漏字段时全量误杀)、无效 ref 无从核验从宽;
 - summary_fingerprint 摘要继承指纹 —— top-N (标题,URL) 序列完全相等才允许
   继承上一期 ai_summary_v2(同数据不换皮重写、省一次调用);顺序/标题/URL
   任一变化都不得继承,超出 top-N 的尾部变化不影响(摘要输入只看 top-N)。
"""

import pytest

import ai_summary as asm


def _hn(title, url, score=100):
    return {"title": title, "target_url": url, "score": score, "descendants": 10}


# ===== _item_title:echo 核验的锚定口径(与各 _fmt_* builder 输入行的标题部分一致) =====

def test_item_title_按源取输入行标题字段():
    assert asm._item_title("hackernews", _hn("T", "https://x.dev/a")) == "T"
    assert asm._item_title("github-trending",
                           {"owner": "o", "name": "r", "url": "https://x.dev/g"}) == "o/r"
    assert asm._item_title("stormzhang-ai",
                           {"summary": "中文摘要当标题", "url": "https://x.dev/s"}) == "中文摘要当标题"
    assert asm._item_title("producthunt", {"name": "App", "url": "https://x.dev/p"}) == "App"
    assert asm._item_title("aihot-featured", {"title": "精选", "url": "https://x.dev/f"}) == "精选"
    # 未知源兜底 title;非 dict 兜底空串
    assert asm._item_title("no-such-source", {"title": "T", "url": "u"}) == "T"
    assert asm._item_title("hackernews", "not-a-dict") == ""


# ===== _clean_entries:titleEcho 核验 + ref 回填 =====

def test_clean_echo不匹配整条丢弃():
    # AI 以为 ref 0 是芯片报道(echo 抄的芯片标题开头),实际是 Cursor 条目
    sliced = [_hn("OpenAI cuts out SpaceX-owned Cursor", "https://x.dev/cursor"),
              _hn("Other story", "https://x.dev/o")]
    parsed = [
        {"title": "芯片发布", "desc": "OpenAI 自研芯片落地", "ref": 0,
         "titleEcho": "OpenAI's first"},
        {"title": "对的故事", "desc": "d", "ref": 1, "titleEcho": "Other stor"},
    ]
    cleaned, present, dropped = asm._clean_entries(parsed, "hackernews", sliced)
    assert [c["title"] for c in cleaned] == ["对的故事"]
    assert present == 2 and dropped == 1
    assert cleaned[0]["url"] == "https://x.dev/o"


def test_clean_echo匹配前缀容忍():
    # 模型抄的长度有漂移(10 字符上下),只要是被锚定标题的前缀即算匹配
    sliced = [_hn("Chrome drops MV2 extensions", "https://x.dev/mv2")]
    parsed = [{"title": "t", "desc": "d", "ref": 0, "titleEcho": "Chrome drop"}]
    cleaned, _, dropped = asm._clean_entries(parsed, "hackernews", sliced)
    assert len(cleaned) == 1 and dropped == 0


def test_clean_echo缺失从宽保留():
    sliced = [_hn("Solo story", "https://x.dev/s")]
    parsed = [{"title": "t", "desc": "d", "ref": 0}]
    cleaned, present, dropped = asm._clean_entries(parsed, "hackernews", sliced)
    assert len(cleaned) == 1 and present == 0 and dropped == 0


def test_clean_无效ref无从核验从宽保留():
    # ref 越界:url 留空、条目保留;echo 虽在但锚定条目不存在,不参与核验
    sliced = [_hn("Solo story", "https://x.dev/s")]
    parsed = [{"title": "t", "desc": "d", "ref": 99, "titleEcho": "whatever"}]
    cleaned, present, dropped = asm._clean_entries(parsed, "hackernews", sliced)
    assert len(cleaned) == 1 and cleaned[0]["url"] == ""
    assert present == 0 and dropped == 0


def test_clean_空title或desc过滤_全空抛异常():
    sliced = [_hn("T", "https://x.dev/a")]
    parsed = [{"title": "", "desc": "d", "ref": 0}, {"title": "t", "desc": "", "ref": 0}]
    with pytest.raises(RuntimeError):
        asm._clean_entries(parsed, "hackernews", sliced)
    # 全部错绑同样清空 → 抛异常(交给上层业务重试)
    with pytest.raises(RuntimeError):
        asm._clean_entries(
            [{"title": "t", "desc": "d", "ref": 0, "titleEcho": "mismatch"}],
            "hackernews", sliced)


def test_clean_锚定口径_github取owner_name_stormzhang取summary():
    gh_items = [{"owner": "o", "name": "repo-x", "url": "https://x.dev/g"}]
    ok = asm._clean_entries(
        [{"title": "t", "desc": "d", "ref": 0, "titleEcho": "o/repo-x"}],
        "github-trending", gh_items)
    assert len(ok[0]) == 1
    sz_items = [{"summary": "中文条目当标题", "url": "https://x.dev/s", "source": "X",
                 "english": "e", "time": "2026-08-29 10:00"}]
    ok2 = asm._clean_entries(
        [{"title": "t", "desc": "d", "ref": 0, "titleEcho": "中文条目当"}],
        "stormzhang-ai", sz_items)
    assert len(ok2[0]) == 1


def test_clean_echo带源前缀放行():
    # oai 输入行是「[0] [OpenAI] Title(category):summary」形态,模型常把 [OpenAI]
    # 连带当标题开头照抄(2026-09-29 生产实锤:3/3 全败皆因 echo 带 vendor 前缀
    # 被整批判错绑)。核验须认可「行内前缀 + 标题」形态——前缀由数据侧按
    # builder 行形态预拼,不靠猜
    oai_items = [{"title": "The Lenfest Institute grows", "url": "https://x.dev/a",
                  "vendor": "OpenAI", "category": "Company", "summary": "s",
                  "publishedAt": "2026-09-29"}]
    parsed = [{"title": "t", "desc": "d", "ref": 0, "titleEcho": "[OpenAI] T"}]
    cleaned, present, dropped = asm._clean_entries(parsed, "openai-anthropic-news", oai_items)
    assert len(cleaned) == 1 and present == 1 and dropped == 0

    sz_items = [{"summary": "中文条目当标题", "url": "https://x.dev/s", "source": "X",
                 "english": "e", "time": "2026-09-29 10:00"}]
    parsed2 = [{"title": "t", "desc": "d", "ref": 0, "titleEcho": "[X] 中文条"}]
    cleaned2, p2, d2 = asm._clean_entries(parsed2, "stormzhang-ai", sz_items)
    assert len(cleaned2) == 1 and d2 == 0


def test_clean_echo带源前缀的错绑仍拦截():
    # echo 抄的是 OpenAI 条目开头(带前缀),ref 却指向 Anthropic 条目 → 依旧丢弃
    # (配一条合法卡:单卡全丢按契约抛 RuntimeError,两卡才能观测到选择性丢弃)
    oai_items = [
        {"title": "The Lenfest grows", "url": "https://x.dev/a", "vendor": "OpenAI"},
        {"title": "Giving companies control", "url": "https://x.dev/b", "vendor": "Anthropic"},
    ]
    parsed = [
        {"title": "错绑卡", "desc": "d", "ref": 1, "titleEcho": "[OpenAI] T"},
        {"title": "合法卡", "desc": "d", "ref": 0, "titleEcho": "[OpenAI] T"},
    ]
    cleaned, present, dropped = asm._clean_entries(parsed, "openai-anthropic-news", oai_items)
    assert [c["title"] for c in cleaned] == ["合法卡"] and dropped == 1




# ===== summary_fingerprint:摘要继承指纹 =====

def test_fingerprint_同topN相等_超出部分不影响():
    a = [_hn(f"t{i}", f"https://x.dev/{i}") for i in range(20)]
    b = list(a[:asm.SOURCE_TOP_N["hackernews"]]) + [_hn("diff", "https://x.dev/z")]
    assert asm.summary_fingerprint("hackernews", a) == asm.summary_fingerprint("hackernews", b)


def test_fingerprint_顺序标题URL变化均不等():
    a = [_hn("t1", "https://x.dev/1"), _hn("t2", "https://x.dev/2")]
    reorder = [a[1], a[0]]
    assert asm.summary_fingerprint("hackernews", a) != asm.summary_fingerprint("hackernews", reorder)
    retitled = [_hn("t1-changed", "https://x.dev/1"), a[1]]
    assert asm.summary_fingerprint("hackernews", a) != asm.summary_fingerprint("hackernews", retitled)
    reurl = [_hn("t1", "https://x.dev/other"), a[1]]
    assert asm.summary_fingerprint("hackernews", a) != asm.summary_fingerprint("hackernews", reurl)


def test_fingerprint_空与非列表():
    assert asm.summary_fingerprint("hackernews", []) == ()
    assert asm.summary_fingerprint("hackernews", None) == ()
