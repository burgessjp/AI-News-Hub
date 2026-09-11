"""trend_keywords.py 纯函数回归(零 IO)。

钉住:词条提取的别名/停用词/自由 bigram 间隔约束(多字段拼接假相邻防线)、
动量封顶与容差带、统计回退榜的护栏对象、代表条目两轮制选择、AI 合并的
统计重算、各源快照字段映射(与 overview_summary._extract_items 对齐的前提)。
这些是「趋势 Tab 全部内容」的语义地基——错一个就是全用户可见的错误数据。
"""

from collections import Counter

import trend_keywords as tk


# ===== _extract_terms:别名变体命中(不受停用词/间隔约束) =====

def test_extract_别名bigram两侧皆停用词仍命中():
    # "open source" 的两个 unigram 都是停用词,唯有别名 bigram 让它入榜
    assert tk._extract_terms("open source") == {"open-source": "open source"}


def test_extract_连写形式经bigram变体命中():
    # GPT-5 切成 gpt/5 两 token,变体 "gpt 5" 命中;连字符 gap 不拦别名对
    found = tk._extract_terms("GPT-5 released today")
    assert "gpt-5" in found


def test_extract_小数版本号不拆碎片且只活在别名里():
    # 3.7 是完整 token;自由路径拦纯数字,只有 "gemini 3.7" 变体能入榜
    found = tk._extract_terms("gemini 3.7 is fast")
    assert found["gemini-3"] == "gemini 3.7"
    assert "3.7" not in found


def test_extract_别名unigram不受停用词约束():
    # rag/mcp 这类短词若是停用词成员也照样入榜(别名优先级最高)
    found = tk._extract_terms("rag with mcp")
    assert "rag" in found and "mcp" in found


def test_extract_同条同词只录一次且display取首见():
    found = tk._extract_terms("OpenAI and openai")
    assert found == {"openai": "OpenAI"}  # "and" 停用词,第二次 openai 不覆盖首见写法


def test_extract_CJK变体走子串匹配():
    found = tk._extract_terms("通义千问发布了新模型")
    assert "qwen" in found
    assert tk._extract_terms("智谱开源了GLM")["zhipu"] == "智谱"


# ===== _extract_terms:自由 token / 停用词约束 =====

def test_extract_停用词与领域泛词不录():
    assert tk._extract_terms("the model launched a new app") == {}
    assert tk._extract_terms("ai ai ai") == {}


def test_extract_自由unigram须两字符以上且含字母():
    # 单字母残片与纯数字(含小数)都不配做词条
    assert tk._extract_terms("x y 5 3.7") == {}


def test_extract_纯数字token只活在别名变体里():
    # "5 5" 自由 bigram 两侧须过自由 token 约束,数字侧被拦
    assert tk._extract_terms("5 5") == {}


# ===== _extract_terms:自由 bigram 间隔约束(假相邻防线) =====

def test_extract_空格与Tab与单连字符允许配对():
    assert "neural network" in tk._extract_terms("neural network")
    assert "neural network" in tk._extract_terms("neural\tnetwork")
    assert "long horizon" in tk._extract_terms("long-horizon planning")  # 连字符复合词要保


def test_extract_逗号句号换行不配对():
    # 多字段拼接边界(\n)与标点都是假相邻,拼出的词组是噪声
    assert "neural network" not in tk._extract_terms("neural, network")
    assert "neural network" not in tk._extract_terms("neural\nnetwork")
    assert "neural network" not in tk._extract_terms("neural.network")


def test_extract_所有格先剥掉避免s残片():
    found = tk._extract_terms("Builder's guide")
    assert "s" not in found
    assert "builder" in found


# ===== _momentum_score / _trend_of =====

def test_momentum_平稳序列比值为1():
    assert tk._momentum_score([1] * 14) == 7.0  # 近7日和 × min(1, 2.5)


def test_momentum_尾部尖峰被封顶():
    # 动量比 (9+1)/(0+1)=10,封顶 2.5 → 9 × 2.5
    assert tk._momentum_score([0] * 11 + [3, 3, 3]) == 22.5


def test_momentum_衰减词动量比压低分值():
    # 近 3 日熄火:动量比 (0+1)/(9+1)=0.1,9 × 0.1 = 0.9,稳定泛词自然沉底
    assert tk._momentum_score([0] * 8 + [3, 3, 3, 0, 0, 0]) == 0.9


def test_trend_近3日爆发为up():
    assert tk._trend_of([0] * 11 + [5, 5, 5]) == "up"


def test_trend_近3日熄火为down():
    assert tk._trend_of([0] * 8 + [5, 5, 5, 0, 0, 0]) == "down"


def test_trend_容差带内算flat():
    # 4 vs 3:带宽 max(1, round(3×0.15))=1,4 ≤ 3+1 → flat
    assert tk._trend_of([0] * 8 + [1, 1, 1, 2, 1, 1]) == "flat"


def test_trend_接近噪声不标涨_101对100():
    # 无容差时 101 vs 100 会标 up;带宽 15 把它压回 flat
    assert tk._trend_of([0] * 8 + [30, 30, 40, 34, 33, 34]) == "flat"


def test_trend_前3日为零时带宽兜底为1():
    assert tk._trend_of([0] * 11 + [1, 0, 0]) == "flat"  # 1 vs 0 → flat
    assert tk._trend_of([0] * 11 + [1, 1, 0]) == "up"    # 2 vs 0 → up


# ===== 护栏对象 =====

def test_is_free_unigram_无空格且非别名():
    assert tk._is_free_unigram("work")          # 拆词残留,拦
    assert not tk._is_free_unigram("openai")    # 别名表词,放
    assert not tk._is_free_unigram("claude code")  # 自由 bigram 自带上下文,放


# ===== _select_items:两轮制 + URL 去重 =====

def _cand(source, url, date):
    return {"title": f"{source}-{url}", "url": url, "source": source, "date": date}


def test_select_首轮每源至多一条再放开补齐():
    cands = [_cand("a", "u1", "d3"), _cand("a", "u2", "d3"), _cand("a", "u3", "d2"),
             _cand("b", "u4", "d2")]
    got = tk._select_items(cands, 3)
    assert [c["url"] for c in got] == ["u1", "u4", "u2"]  # 首轮 a1+b1,次轮补 a2


def test_select_URL去重跨两轮生效():
    cands = [_cand("a", "u1", "d2"), _cand("b", "u1", "d1")]  # 同 URL 不同源
    assert [c["url"] for c in tk._select_items(cands, 3)] == ["u1"]


def test_select_不超过limit():
    cands = [_cand("a", f"u{i}", "d") for i in range(5)]
    assert len(tk._select_items(cands, 2)) == 2


# ===== _display_of =====

def test_display_别名指定形优先():
    assert tk._display_of("claude", Counter({"claude": 9})) == "Claude"


def test_display_自由词取语料最高频写法_空则回退canon():
    surfaces = Counter({"Neural Network": 2, "neural network": 1})
    assert tk._display_of("neural network", surfaces) == "Neural Network"
    assert tk._display_of("neural network", Counter()) == "neural network"


# ===== _merge_pool_entries:AI 合并的统计重算 =====

def _entry(term, daily, items):
    return {
        "term": term, "display": term, "total": sum(daily),
        "daysActive": sum(1 for h in daily if h > 0), "daily": daily,
        "trend": tk._trend_of(daily), "items": items,
    }


def test_merge_按下标累加并重算派生字段():
    main = _entry("glm", [0] * 11 + [1, 1, 1],
                  [_cand("a", "m1", "2026-08-28"), _cand("b", "m2", "2026-08-27")])
    absorbed = _entry("glm-5.2", [0] * 11 + [1, 1, 0],
                      [_cand("c", "a1", "2026-08-28"), _cand("a", "a2", "2026-08-26")])
    merged = tk._merge_pool_entries(main, [absorbed], "GLM 全家")
    assert merged["term"] == "glm"                    # 主词身份不变
    assert merged["display"] == "GLM 全家"             # AI 规范名替换
    assert merged["daily"] == [0] * 11 + [2, 2, 1]
    assert merged["total"] == 5 and merged["daysActive"] == 3
    assert merged["trend"] == "up"                    # 近3日 5 vs 前3日 0
    # 条目并集按日期降序重选,截 MAX_ITEMS_PER_KEYWORD=3
    assert [it["url"] for it in merged["items"]] == ["m1", "a1", "m2"]


def test_merge_空absorb等于复制():
    main = _entry("qwen", [0] * 12 + [1, 1], [_cand("a", "u", "d")])
    merged = tk._merge_pool_entries(main, [], "Qwen")
    assert merged["total"] == main["total"] and merged["items"] == main["items"]


# ===== _item_fields:各源字段映射(对齐 overview_summary._extract_items) =====

def test_fields_hackernews外链优先():
    got = tk._item_fields("hackernews", {"title": "T", "target_url": "https://ext", "url": "https://hn"})
    assert got == ("T", "T", "https://ext")
    got = tk._item_fields("hackernews", {"title": "T", "target_url": "", "url": "https://hn"})
    assert got[2] == "https://hn"


def test_fields_github拼接用换行防跨界bigram():
    got = tk._item_fields("github-trending",
                          {"owner": "o", "name": "n", "description": "d", "url": "u"})
    assert got == ("o/n\nd", "o/n", "u")


def test_fields_producthunt与aihot英文优先():
    assert tk._item_fields("producthunt", {"name": "N", "tagline": "Tg", "url": "u"}) == ("N\nTg", "N", "u")
    got = tk._item_fields("aihot-featured",
                          {"titleEn": "E", "title": "中", "permalink": "p", "url": "u"})
    assert got == ("E\n中", "中", "p")
    got = tk._item_fields("aihot-featured",
                          {"titleEn": "E", "title": "中", "permalink": "", "url": "u"})
    assert got[2] == "u"


def test_fields_stormzhang赞助行被截断():
    got = tk._item_fields("stormzhang-ai", {
        "english": "Full text\nPLUS: sponsor words author, +1",
        "summary": "摘要", "url": "u"})
    assert got[0] == "Full text\n\n摘要"  # PLUS: 之后(含作者/赞助商)不进词频
    assert got[1] == "摘要" and got[2] == "u"


def test_fields_未知源与空字段返回None():
    assert tk._item_fields("nope", {"title": "t", "url": "u"}) is None
    assert tk._item_fields("hackernews", {"title": "", "url": "u"}) is None
    assert tk._item_fields("hackernews", {"title": "t", "url": ""}) is None
