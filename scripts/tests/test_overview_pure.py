"""overview_summary.py 纯函数回归(零 IO,不触 AI/网络)。

钉住:标题判重三规则(原「token<3 豁免」移除)——跨语言同事件重复是历史实锤
(2026-09 回测 47 天 202 期归档 15 对漏网:Shopify/GLM-5.3/GPT-6 Astra/Kimi K3/
DeepSeek V4 Pro/Hy4 等),错一个就是用户可见的重复条目;泛词锚点防误杀
(mattpocock/skills vs Claude Skills API、同公司两条不同新闻)。以及
_parse_result 的「去重后不足 10 条按热度回填」契约——常规批次恒输出 10 条、
回填不破单源 ≤3 / 7 天时效 / URL 去重、回填条目恒非 breaking 且无 AI 点评;
增量批次契约——与上一期 Top10 同事件(URL/标题判重)的条目软降位到非头条
区末尾、不参与回填,breaking 条目豁免。breaking 硬校验——supportRefs 佐证
去重后 ≥2 源且 ≥1 有指标源,breakingReason 含内部黑话(权重/档位/日期…)整条
降级,佐证或行文不达标宁可不上「头条」。
"""

from collections import Counter
from datetime import datetime

from common import BEIJING_TZ

import overview_summary as ov


# ===== _tokenize_title:中英边界二次切分 + 英文虚词丢弃 =====

def test_tokenize_中英连写段按边界切开():
    # 「Pro与Grok」若黏成一个 token,containment 判重时 pro 永远匹配不上
    tokens = ov._tokenize_title("DeepSeek V4 Pro与Grok 4.6同日发布")
    assert "pro" in tokens and "grok" in tokens


def test_tokenize_两字符英文虚词丢弃():
    # on 丢弃后「Kimi-K3 on HuggingFace」只多 1 个 token 差,近似包含才能命中
    assert ov._tokenize_title("Kimi-K3 on HuggingFace") == {"kimi", "k3", "huggingface"}


def test_tokenize_中文段保留单字丢弃():
    tokens = ov._tokenize_title("GLM-5.3 和 编程能力开源第一")
    assert "glm" in tokens and "编程能力开源第一" in tokens
    assert "和" not in tokens and "5" not in tokens


# ===== _title_not_duplicate:三规则判重 =====

def _is_dup(first, second):
    """跨源判定:首个标题登记为源 a,判源 b 的第二个标题是否重复。"""
    history = []
    assert ov._title_not_duplicate(first, history, "a")
    return not ov._title_not_duplicate(second, history, "b")


def test_判重_同源近似条目不误杀():
    # GitHub 同 owner 不同仓:owner 共享、只差 1 token,近似包含在同源内会误杀
    # ——语义规则(②③)仅跨源生效,同源内靠 URL 精确去重兜底
    history = []
    assert ov._title_not_duplicate("langchain-ai/langchain", history, "github-trending")
    assert ov._title_not_duplicate("langchain-ai/langgraph", history, "github-trending")
    assert ov._title_not_duplicate("repo-one", history, "github-trending")
    assert ov._title_not_duplicate("repo-two", history, "github-trending")


def test_判重_裸产品名短标题被长标题包含():
    # 2026-08-15 实锤:PH「GLM-5.3」与 aihot 中文报道同事件双条目(token<3 豁免曾放行)
    assert _is_dup("GLM-5.3", "GLM-5.3 发布:编程能力开源第一,并涌现网络安全能力")
    assert _is_dup("GPT-6 Astra", "OpenAI 发布 GPT-6 Astra:1.05M 上下文的计算机操作模型")


def test_判重_完全同token短标题():
    # 2026-08-30 实锤:「Hy4 preview」PH/HN 双条目,Jaccard=1.0 仍被豁免放行
    assert _is_dup("Hy4 preview", "Hy4 preview")


def test_判重_近似包含允许恰多一个token且锚点非泛词():
    # 2026-07-28 实锤:Kimi K3 中英报道,交集 {kimi,k3} 均为专名锚点
    assert _is_dup(
        "Kimi-K3 on HuggingFace",
        "Kimi K3 开源:2.8T MoE 模型与技术报告")


def test_判重_跨语言长标题走重合度规则():
    # 2026-09-11 实锤:Shopify 中英报道,交集 5 个专名、Jaccard≈0.42,够不着 0.5
    assert _is_dup(
        "Shopify is moving from React Native back to Swift and Kotlin",
        "Shopify 宣布从 React Native 全面迁回 Swift 和 Kotlin 原生开发")


def test_判重_跨站英文同事件走重合度规则():
    # 2026-08-18 实锤:Cursor Origin 两条报道,交集 {cursor,origin,github}
    assert _is_dup(
        "Cursor 推出 Origin 代码托管服务,作为 GitHub 的替代方案",
        "Cursor's Origin hits GitHub on its worst day")


def test_判重_纯英文高重合原路径保留():
    assert _is_dup("Foo bar baz qux quux", "Foo bar baz qux zzz")


def test_防误杀_泛词锚点拦住skills同名不同事件():
    # 2026-08-21 实锤反例:skills 是泛词,仓库 vs 平台 API 不是同事件
    assert not _is_dup(
        "mattpocock/skills",
        "Claude Platform 正式上线 Computer Use、Skills API 与 Files API,新增浏览器操作工具")


def test_防误杀_同公司两条不同新闻():
    # 只有公司名/泛词重合,交集无专名锚点、重合度不够,均不得误杀
    assert not _is_dup(
        "OpenAI 正为一切构建 AI 智能体,但用户会愿意交出控制权吗?",
        "OpenAI 首席全球事务官勒汉恩:公众、企业要为 AI 网络攻击做好防御准备")
    assert not _is_dup(
        "Cloudflare 推出 Billable Usage API:为自助账户提供程序化成本可见性",
        "Cloudflare 推出 @cloudflare/computer 预览版:为智能体提供虚拟文件系统")
    assert not _is_dup(
        "Anthropic 让 Claude 自主训练模型以缓解对齐失败",
        "联邦法官裁定特朗普政府将 Anthropic 列入黑名单违法")


def test_防误杀_池级压测实锤的弱锚点类():
    # 全部来自 2026-09 池级压测(8 源 × top8 整池送判重)的真实误杀样本:
    # 产品线名/协议名/型号后缀/参数规模/通用名词,单靠它们当锚点必误杀
    assert not _is_dup(  # chatgpt:同产品两条不同功能新闻
        "Testing ads in ChatGPT",
        "ChatGPT 桌面端支持导入其他智能体工作数据")
    assert not _is_dup(  # flash 型号后缀:不同厂商共用
        "GLM-5.3-Flash", "Qwen3.8-Flash-Next 开源:Qwen4 架构早期预览")
    assert not _is_dup(  # labs 公司后缀
        "Black Forest Labs teaches video AI to run robots", "Poth Labs")
    assert not _is_dup(  # 3b 参数规模:不同模型
        "Tines 3B", "Ling-3.0-tiny 正式开源:1.3B 激活参数如何进入真实任务")
    assert not _is_dup(  # code 产品线:Claude Code 两条不同新闻
        "Auto mode is now the default in Claude Code for Pro",
        "Claude Code 会话间可互发消息")
    assert not _is_dup(  # world 通用名词
        "Code as Worlds: Agentic Discovery of Executable", "GPU World")
    assert not _is_dup(  # 虚词凑交集:for/the/language 曾凑满 3 个交集
        "Flint: A Visualization Language for the AI Era",
        "Kronos: A Foundation Model for Financial Markets")


# ===== _parse_result:ref 回填 + 去重 + 回填补足 10 条 =====

DAY = "2026-08-29"
FRESH_S = int(datetime(2026, 8, 29, 12, 0, tzinfo=BEIJING_TZ).timestamp())
STALE_S = int(datetime(2026, 8, 10, 12, 0, tzinfo=BEIJING_TZ).timestamp())  # 早于 7 天闸
FETCHED_MS = int(datetime(2026, 8, 29, 8, 0, tzinfo=BEIJING_TZ).timestamp()) * 1000
BREAKING_DATES = {DAY, "2026-08-28"}


def _hn(title, url, score=100, ts=FRESH_S):
    return {"title": title, "target_url": url, "score": score, "descendants": 10, "time": ts}


def _gh(name, url, today=100):
    return {"owner": "o", "name": name, "url": url, "starsToday": today,
            "totalStars": 1000, "description": "d"}


def _ph(name, url, votes=100, rank=1, created="2026-08-29T02:00:00Z"):
    return {"name": name, "url": url, "votesCount": votes, "commentsCount": 5,
            "dailyRank": rank, "createdAt": created}


def _ai(title, url, score=80, published="2026-08-29T02:00:00Z"):
    return {"title": title, "permalink": url, "score": score, "source": "s",
            "summary": "sum", "publishedAt": published}


def _ref(ref, analysis="点评", breaking=False, support_refs=None):
    o = {"ref": ref, "analysis": analysis, "breaking": breaking,
         "breakingReason": "证据" if breaking else ""}
    if support_refs is not None:
        o["supportRefs"] = support_refs
    return o


def _e2e_snapshots():
    """4 源 × 4~5 条;hn:3/4 热度刻意最高(若单源 ≤3 失守,回填会先取 hn 备选)。"""
    return {
        "hackernews": {"fetched_at_ms": FETCHED_MS, "items": [
            _hn("Shopify is moving from React Native back to Swift and Kotlin",
                "https://e.dev/shopify", 1153),
            _hn("Alpha launch day notes", "https://e.dev/a", 60),
            _hn("Beta tool reaches v2", "https://e.dev/b", 50),
            _hn("Delta hot spare item", "https://e.dev/d", 900),
            _hn("Echo hot spare item", "https://e.dev/e", 800),
        ]},
        "github-trending": {"fetched_at_ms": FETCHED_MS, "items": [
            _gh("repo-one", "https://gh.dev/1", 500),
            _gh("repo-two", "https://gh.dev/2", 400),
            _gh("repo-three", "https://gh.dev/3", 300),
            _gh("repo-four", "https://gh.dev/4", 200),
        ]},
        "producthunt": {"fetched_at_ms": FETCHED_MS, "items": [
            _ph("Anysite.io", "https://ph.dev/1", 400, 1),
            _ph("Cool app", "https://ph.dev/2", 230, 5),
            _ph("Cline Desktop", "https://ph.dev/3", 200, 6),
            _ph("Quiet launch", "https://ph.dev/4", 100, 6),
        ]},
        # 权重 90/80/70/60:备用池中 aihot:1 归一化热度 88%,仅次于被单源闸拦住的 hn 备选
        "aihot-featured": {"fetched_at_ms": FETCHED_MS, "items": [
            _ai("Shopify 宣布从 React Native 全面迁回 Swift 和 Kotlin 原生开发",
                "https://aihot.news/x1", 90),
            _ai("Anthropic 裁决与治理观察", "https://aihot.news/x2", 80),
            _ai("卡尔的AI沃茨实测随记", "https://aihot.news/x3", 70),
            _ai("前沿周报精选导读", "https://aihot.news/x4", 60),
        ]},
    }


def test_解析端到端_同事件去重后按热度回填保满10条():
    snapshots = _e2e_snapshots()
    # 10 个 ref:hn:0(breaking,佐证 aihot:0 同事件中文报道)与 aihot:0 本体
    ai_data = {"digest": "综述", "items": [
        _ref("hackernews:0", breaking=True, support_refs=["aihot-featured:0"]),
        _ref("hackernews:1"), _ref("hackernews:2"),
        _ref("github-trending:0"), _ref("github-trending:1"), _ref("github-trending:2"),
        _ref("producthunt:0"), _ref("producthunt:1"), _ref("producthunt:2"),
        _ref("aihot-featured:0"),
    ]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY)

    # 契约一:恒 10 条(aI 候选 10 条去重剩 9,回填 1 条)
    assert len(result) == 10
    # 契约二:同事件只留一档位更高的英文原报道,中文转载被去掉
    shopify = [e for e in result if "shopify" in e["title"].lower()]
    assert len(shopify) == 1 and shopify[0]["source"] == "hackernews"
    # 契约三:回填条目无 AI 点评、恒非 breaking,来自单源未满 3 条的源
    backfilled = [e for e in result if e["comment"] == ""]
    assert len(backfilled) == 1
    assert backfilled[0]["title"] == "Anthropic 裁决与治理观察"
    assert backfilled[0]["breaking"] is False
    # 契约四:单源 ≤3(hn 备选热度全网最高,若闸失守回填会先取 hn 备选)
    counts = Counter(e["source"] for e in result)
    assert all(c <= 3 for c in counts.values()) and counts["aihot-featured"] == 1
    # 契约五:URL 全唯一 + breaking 排最前
    assert len({e["url"] for e in result}) == 10
    assert result[0]["breaking"] is True and result[0]["breakingReason"]


def test_解析端到端_备用池全过期时不硬凑保持原数():
    # 回填不得为凑数破坏 7 天时效硬闸:备用全 stale → 维持 9 条,不塞旧闻
    snapshots = {
        "hackernews": {"fetched_at_ms": FETCHED_MS, "items": [
            _hn("Fresh lead story", "https://e.dev/1"),
            _hn("Fresh second story", "https://e.dev/2"),
            _hn("Fresh third story", "https://e.dev/3"),
            _hn("Stale spare one", "https://e.dev/4", ts=STALE_S),
            _hn("Stale spare two", "https://e.dev/5", ts=STALE_S),
        ]},
        "producthunt": {"fetched_at_ms": FETCHED_MS, "items": [
            _ph("One", "https://ph.dev/1"), _ph("Two", "https://ph.dev/2"),
            _ph("Three", "https://ph.dev/3"),
            _ph("Stale", "https://ph.dev/4", created="2026-08-01T02:00:00Z"),
        ]},
        "aihot-featured": {"fetched_at_ms": FETCHED_MS, "items": [
            _ai("一", "https://aihot.news/1"), _ai("二", "https://aihot.news/2"),
            _ai("三", "https://aihot.news/3"),
            _ai("旧闻", "https://aihot.news/4", published="2026-08-01T02:00:00Z"),
        ]},
    }
    ai_data = {"digest": "", "items": [
        _ref("hackernews:0"), _ref("hackernews:1"), _ref("hackernews:2"),
        _ref("producthunt:0"), _ref("producthunt:1"), _ref("producthunt:2"),
        _ref("aihot-featured:0"), _ref("aihot-featured:1"), _ref("aihot-featured:2"),
    ]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY)
    assert len(result) == 9
    assert all(e["comment"] for e in result)  # 无回填条目


def test_解析端到端_breaking时效窗外强制降级():
    # AI 标了 breaking 但条目日期不在窗口(数据日期/前一天)→ 强制降级
    snapshots = {
        "hackernews": {"fetched_at_ms": FETCHED_MS, "items": [
            _hn("Big old launch", "https://e.dev/old", ts=STALE_S),
        ]},
    }
    ai_data = {"digest": "", "items": [_ref("hackernews:0", breaking=True)]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY)
    assert len(result) == 1 and result[0]["breaking"] is False
    assert result[0]["breakingReason"] == ""


def test_解析端到端_同URL双条目仅留一条():
    snapshots = {
        "producthunt": {"fetched_at_ms": FETCHED_MS, "items": [
            _ph("Same product", "https://ph.dev/1"),
            _ph("Same product again", "https://ph.dev/1"),
        ]},
    }
    ai_data = {"digest": "", "items": [_ref("producthunt:0"), _ref("producthunt:1")]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY)
    assert len(result) == 1


# ===== breaking 硬校验:supportRefs 佐证 + breakingReason 黑话闸 =====
#
# prompt 规则②(≥2 源且 ≥1 有指标源)原先只靠 AI 自觉,现由数据侧硬校验:
# 佐证源 = 主 ref 源 ∪ supportRefs(逐个按 ref 有效性核验)。

def test_breaking_无佐证单源强制降级():
    # AI 标了 breaking 但没给 supportRefs → 佐证仅主源 1 个,不满足规则②
    snapshots = {
        "hackernews": {"fetched_at_ms": FETCHED_MS, "items": [
            _hn("Solo big launch", "https://e.dev/solo"),
        ]},
    }
    ai_data = {"digest": "", "items": [_ref("hackernews:0", breaking=True)]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY)
    assert result[0]["breaking"] is False and result[0]["breakingReason"] == ""


def test_breaking_佐证全同源不算双源():
    # supportRefs 指向同一源的其它条目:源集合去重后仍只有 1 个源
    snapshots = {
        "hackernews": {"fetched_at_ms": FETCHED_MS, "items": [
            _hn("Same source event", "https://e.dev/a"),
            _hn("Same source follow-up", "https://e.dev/b"),
        ]},
    }
    ai_data = {"digest": "", "items": [
        _ref("hackernews:0", breaking=True, support_refs=["hackernews:1"])]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY)
    assert result[0]["breaking"] is False


def test_breaking_佐证全无指标源降级():
    # rundown-ai + stormzhang-ai 都是无指标源,规则②要求 ≥1 个有指标源
    snapshots = {
        "rundown-ai": {"fetched_at_ms": FETCHED_MS, "items": [
            {"title": "无指标事件", "url": "https://r.dev/a", "subtitle": "s",
             "publishedAt": "2026-08-29 09:00"}]},
        "stormzhang-ai": {"fetched_at_ms": FETCHED_MS, "items": [
            {"summary": "无指标转载", "url": "https://s.dev/a", "source": "X",
             "english": "e", "time": "2026-08-29 10:00"}]},
    }
    ai_data = {"digest": "", "items": [
        _ref("rundown-ai:0", breaking=True, support_refs=["stormzhang-ai:0"])]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY)
    assert result[0]["breaking"] is False


def test_breaking_有指标源佐证放行():
    # aihot-featured(score)是有指标源:主源 HN + 佐证 aihot = 2 源含指标源
    snapshots = {
        "hackernews": {"fetched_at_ms": FETCHED_MS, "items": [
            _hn("Cross source event", "https://e.dev/x", 900)]},
        "aihot-featured": {"fetched_at_ms": FETCHED_MS, "items": [
            _ai("跨源事件中文报道", "https://aihot.news/x", 85)]},
    }
    ai_data = {"digest": "", "items": [
        _ref("hackernews:0", breaking=True, support_refs=["aihot-featured:0"])]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY)
    assert result[0]["breaking"] is True and result[0]["breakingReason"]


def test_breaking_编造的佐证ref被忽略():
    # supportRefs 里的无效 ref(未知源/越界/坏格式)不参与佐证计数;
    # 剩一个有效佐证仍凑满 2 源 + 1 指标源 → 保持 breaking
    snapshots = {
        "hackernews": {"fetched_at_ms": FETCHED_MS, "items": [
            _hn("Real event", "https://e.dev/r", 900)]},
        "aihot-featured": {"fetched_at_ms": FETCHED_MS, "items": [
            _ai("真实佐证", "https://aihot.news/r", 80)]},
    }
    ai_data = {"digest": "", "items": [
        _ref("hackernews:0", breaking=True,
             support_refs=["no-such-source:0", "aihot-featured:99", "坏格式", "aihot-featured:0"])]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY)
    assert result[0]["breaking"] is True


def test_breaking_reason含内部黑话整条降级():
    # 历史实锤:AI 写出「aihot-featured权重80,日期09-01」直接上屏(App 里
    # breaking 条目的描述位就是 breakingReason)。黑话闸:命中即降级,宁缺毋滥
    snapshots = {
        "hackernews": {"fetched_at_ms": FETCHED_MS, "items": [
            _hn("Jargon event", "https://e.dev/j", 900)]},
        "aihot-featured": {"fetched_at_ms": FETCHED_MS, "items": [
            _ai("佐证报道", "https://aihot.news/j", 80)]},
    }
    for reason in ("aihot-featured权重80,日期09-01", "热度档位90%多源报道",
                   "HN 899 分,score 溢出", "佐证 ref 见输入序号 3"):
        ai_data = {"digest": "", "items": [
            {"ref": "hackernews:0", "analysis": "点评", "breaking": True,
             "supportRefs": ["aihot-featured:0"], "breakingReason": reason}]}
        result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY)
        assert result[0]["breaking"] is False, reason


# ===== 增量批次:与上一期 Top10 同事件的软降位 =====

def _prev_items(pairs):
    """上一期 Top10 形态:[(source, title, url), ...] → previous_overview['items']。"""
    return [{"source": s, "title": t, "url": u} for s, t, u in pairs]


def test_增量_上期同URL条目软降位到末尾():
    # 两条与上期 URL 精确同命中的条目排在全部新鲜条目之后(稳定排序,
    # 两组内部保持 AI 次序);不再占据榜单头部
    snapshots = {"hackernews": {"fetched_at_ms": FETCHED_MS, "items": [
        _hn("Carryover story", "https://e.dev/carry", 900),
        _hn("Fresh alpha", "https://e.dev/f1", 100),
        _hn("Fresh beta", "https://e.dev/f2", 90),
    ]}}
    prev = _prev_items([("hackernews", "Carryover story yesterday", "https://e.dev/carry")])
    ai_data = {"digest": "", "items": [
        _ref("hackernews:0"), _ref("hackernews:1"), _ref("hackernews:2")]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY,
                              previous_items=prev)
    assert [e["title"] for e in result] == ["Fresh alpha", "Fresh beta", "Carryover story"]


def test_增量_上期同事件跨语言标题判重也降位():
    # 上一期是英文原报道、本期中文转述(URL 不同),三规则判重命中同样降位
    snapshots = {"hackernews": {"fetched_at_ms": FETCHED_MS, "items": [
        _hn("Fresh story", "https://e.dev/f", 100),
    ]}, "aihot-featured": {"fetched_at_ms": FETCHED_MS, "items": [
        _ai("Shopify 宣布从 React Native 全面迁回 Swift 和 Kotlin 原生开发",
            "https://aihot.news/carry", 90),
    ]}}
    prev = _prev_items([("hackernews",
                         "Shopify is moving from React Native back to Swift and Kotlin",
                         "https://e.dev/old")])
    ai_data = {"digest": "", "items": [
        _ref("aihot-featured:0"), _ref("hackernews:0")]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY,
                              previous_items=prev)
    assert result[0]["title"] == "Fresh story"
    assert result[1]["title"].startswith("Shopify 宣布")


def test_增量_breaking条目豁免软降位():
    # 重大事件的后续进展标 breaking(佐证齐全)→ 仍排最前,不被增量降位挤后
    snapshots = {"hackernews": {"fetched_at_ms": FETCHED_MS, "items": [
        _hn("Big saga progress", "https://e.dev/saga", 900),
        _hn("Fresh minor", "https://e.dev/f", 100),
    ]}, "aihot-featured": {"fetched_at_ms": FETCHED_MS, "items": [
        _ai("大事件进展中文报道", "https://aihot.news/saga", 85)]}}
    prev = _prev_items([("hackernews", "Big saga started yesterday", "https://e.dev/saga")])
    ai_data = {"digest": "", "items": [
        _ref("hackernews:0", breaking=True, support_refs=["aihot-featured:0"]),
        _ref("hackernews:1")]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY,
                              previous_items=prev)
    assert result[0]["breaking"] is True and result[0]["title"] == "Big saga progress"


def test_增量_回填池排除上期同事件条目():
    # 备用池里归一化热度最高的条目若是上期同事件,回填跳过它取次高新鲜条目
    snapshots = {"hackernews": {"fetched_at_ms": FETCHED_MS, "items": [
        _hn("Kept one", "https://e.dev/k1", 100),
        _hn("Pool carryover", "https://e.dev/carry", 900),
        _hn("Pool fresh", "https://e.dev/pf", 500),
    ]}}
    prev = _prev_items([("hackernews", "Pool carryover last edition", "https://e.dev/carry")])
    ai_data = {"digest": "", "items": [_ref("hackernews:0")]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY,
                              previous_items=prev)
    titles = [e["title"] for e in result]
    assert "Pool carryover" not in titles and "Pool fresh" in titles


def test_增量_无上一期时行为与旧版一致():
    # previous_items=None:不注入判重状态,排序纯按 breaking + AI 次序
    snapshots = {"hackernews": {"fetched_at_ms": FETCHED_MS, "items": [
        _hn("Story one", "https://e.dev/1", 100),
        _hn("Story two", "https://e.dev/2", 90),
    ]}}
    ai_data = {"digest": "", "items": [_ref("hackernews:0"), _ref("hackernews:1")]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY)
    assert [e["title"] for e in result] == ["Story one", "Story two"]


# ===== _build_previous_section:上一期注入段与早晚报身份 =====

def test_上一期段_同日为晚报身份():
    prev = {"dataFetchedAt": int(datetime(2026, 8, 29, 8, 0, tzinfo=BEIJING_TZ).timestamp()) * 1000,
            "digest": "上一期综述原文", "items": [
                {"source": "hackernews", "title": "Prev top one", "url": "https://e.dev/p1"},
                {"source": "github-trending", "title": "prev/old-repo", "url": "https://gh.dev/p1"}]}
    section = ov._build_previous_section(prev, DAY)
    assert "晚报" in section and "2026-08-29" in section
    assert "上一期综述:上一期综述原文" in section
    assert "Prev top one" in section and "prev/old-repo" in section
    assert "禁止照抄" in section  # 防把上一期当素材复述


def test_上一期段_跨日为早报身份():
    prev = {"dataFetchedAt": int(datetime(2026, 8, 28, 18, 0, tzinfo=BEIJING_TZ).timestamp()) * 1000,
            "digest": "", "items": [
                {"source": "hackernews", "title": "Prev edition item", "url": "https://e.dev/p"}]}
    section = ov._build_previous_section(prev, DAY)
    assert "早报" in section


def test_上一期段_digest与items双缺当首期():
    # 上一期残缺(无综述且无条目):返回空串,generate_overview 不注入、当首期处理
    prev = {"dataFetchedAt": int(datetime(2026, 8, 28, 18, 0, tzinfo=BEIJING_TZ).timestamp()) * 1000,
            "digest": "", "items": []}
    assert ov._build_previous_section(prev, DAY) == ""
    assert ov._build_previous_section(None, DAY) == ""


# ===== digest 风格告警(replay_overview 人工评读用,非硬闸) =====

def test_digest风格告警_模板腔命中():
    hits = ov.digest_style_warnings("今日主线集中在模型开源,开发者应关注工具链重构。")
    assert "今日主线集中在" in hits and "开发者应关注" in hits


def test_digest风格告警_正常导语零命中():
    hits = ov.digest_style_warnings("OpenAI 终止供模型的余波继续扩大,转售生意的脆弱性暴露无遗。")
    assert hits == []


# ===== 点评-条目对应核验:titleEcho =====
#
# 单次调用让模型同时选条+合并+写作,偶发把 A 事件的点评写到 B 事件的 ref 上
# (2026-09-01 回放实锤:Cursor 条目配了芯片点评)。AI 须照抄所选条目标题开头,
# 数据侧精确比对:对不上 → 保留条目(标题/链接来自真实 ref)但丢弃点评、强制
# 非 breaking;echo 缺失从宽(等同旧版,防模型整体漏字段时全量点评被误杀)。

def test_点评核验_echo不匹配丢点评保条目():
    # AI 以为该 ref 是芯片报道(echo 抄的是芯片标题开头),实际 ref 是 Cursor 条目
    snapshots = {
        "rundown-ai": {"fetched_at_ms": FETCHED_MS, "items": [
            {"title": "OpenAI cuts out SpaceX-owned Cursor", "url": "https://r.dev/cursor",
             "subtitle": "s", "publishedAt": "2026-08-29 09:00"},
        ]},
    }
    ai_data = {"digest": "", "items": [
        {"ref": "rundown-ai:0", "titleEcho": "OpenAI's first AI chip brings",
         "analysis": "OpenAI 自研芯片落地,训练推理效率提升", "breaking": True,
         "breakingReason": "多家报道,事件重大"}]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY)
    assert len(result) == 1
    assert result[0]["title"] == "OpenAI cuts out SpaceX-owned Cursor"  # 条目保留
    assert result[0]["comment"] == ""                                    # 点评丢弃
    assert result[0]["breaking"] is False                                # 连带不信 breaking


def test_点评核验_echo匹配点评保留():
    snapshots = {"hackernews": {"fetched_at_ms": FETCHED_MS, "items": [
        _hn("Chrome drops MV2 extensions including uBlock Origin", "https://e.dev/mv2", 408)]}}
    ai_data = {"digest": "", "items": [
        {"ref": "hackernews:0", "titleEcho": "Chrome drops MV2 e",
         "analysis": "Chrome 下架 MV2 扩展,广告拦截生态受冲击", "breaking": False}]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY)
    assert result[0]["comment"] == "Chrome 下架 MV2 扩展,广告拦截生态受冲击"


def test_点评核验_echo短于标题按前缀容忍():
    # 模型抄的长度有漂移(10 字符上下):只要是被实际标题的前缀即算匹配
    snapshots = {"hackernews": {"fetched_at_ms": FETCHED_MS, "items": [
        _hn("Shopify is moving from React Native back to Swift", "https://e.dev/s", 900)]}}
    ai_data = {"digest": "", "items": [
        {"ref": "hackernews:0", "titleEcho": "Shopify is moving",
         "analysis": "Shopify 迁回原生,跨端技术选型代价显现", "breaking": False}]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY)
    assert result[0]["comment"] != ""


def test_点评核验_echo缺失从宽保留点评():
    snapshots = {"hackernews": {"fetched_at_ms": FETCHED_MS, "items": [
        _hn("Solo story", "https://e.dev/s", 100)]}}
    ai_data = {"digest": "", "items": [
        {"ref": "hackernews:0", "analysis": "正常点评", "breaking": False}]}
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY)
    assert result[0]["comment"] == "正常点评"


def test_增量_carryover的AI条目排在新鲜回填条目之后():
    # 排序契约:新鲜优先于承接——即使回填条目无点评,也排在 carryover 的 AI 条目前
    snapshots = {"hackernews": {"fetched_at_ms": FETCHED_MS, "items": [
        _hn("Carryover AI story", "https://e.dev/carry", 900),
        _hn("Fresh AI story", "https://e.dev/fresh", 500),
        _hn("Pool fresh spare", "https://e.dev/pool", 300),
    ]}}
    prev = _prev_items([("hackernews", "Carryover AI story last edition", "https://e.dev/carry")])
    ai_data = {"digest": "", "items": [
        _ref("hackernews:0"), _ref("hackernews:1")]}  # 去重后 2 条,回填补 1 条
    result = ov._parse_result(ai_data, snapshots, BREAKING_DATES, DAY,
                              previous_items=prev)
    assert [e["title"] for e in result] == [
        "Fresh AI story",        # 新鲜 AI 条目
        "Pool fresh spare",      # 新鲜回填条目(无点评)仍在前
        "Carryover AI story",    # carryover AI 条目殿后
    ]
