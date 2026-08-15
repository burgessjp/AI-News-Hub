#!/usr/bin/env python3
"""
跨源热词趋势统计(统计为主 + 每批至多一次 AI 精修,失败回退纯统计)。

「趋势」Tab 的数据生产者:扫描数据仓库 checkout 里近 WINDOW_DAYS 天的 8 源快照,
按「条目命中」做词频统计(一个词在一条条目中出现算 1 次,同日同条不重复计),
产出一个热词榜(每日命中序列 + 涨跌 + 代表条目),写进根级独立文件
`trends.json`(内容与原 index.json 内联 `latest_trends` 字段同构,整文件覆盖)。
同时每批落一份按日归档 `trends/<date>/<HH-MM>-data.json`(根级索引
`trends_history.json` 维护 {date: relpath},保留 90 天指针,旧归档目录只增不删),
并基于「昨日最后一期」归档为每个热词附上排名变化字段 rankChange / isNewEntry
(App 端显示 +N / -N / 持平 / 新上榜;无历史基准时不输出这两个字段)。

挂载点:push_data.py 在 overlay 之后、git add 之前调用 write_trends(repo_dir)。
选 push 阶段而非 fetch 阶段的原因:fetch 的 out/ 只有当天快照,而 push 阶段
clone 下来的数据仓库含全部历史日期目录(快照是文件,git --depth 1 不影响),
是唯一免费拿到全部历史的环节。

抽取与归一规则:
  - 每源取哪些字段做文本,见 _item_text(对齐 overview_summary._extract_items 的
    字段映射;aihot-featured 优先 titleEn,stormzhang-ai 优先 english);
  - 英文:小写化 → [a-z0-9]+ 分词 → 去停用词(STOPWORDS)→ unigram + 相邻 bigram
    (bigram 任一侧为停用词则丢弃;unigram 必须含字母,纯数字只活在 bigram 里,
    如 "gpt 5");
  - 别名归一:ALIASES 把 AI 领域常见实体的大小写/连字符/空格变体(及中文别名)
    收敛到一个 canonical key,display 用映射表指定形;非映射词 display 取语料中
    最常见的原始大小写写法;
  - 中文:不做分词,只对映射表内含 CJK 的变体做子串匹配(千问/豆包/智谱…);
  - 入榜门槛:total >= MIN_TOTAL 且 daysActive >= MIN_DAYS_ACTIVE(滤掉单日闪现);
    排序按动量加权分(近 7 日命中和 × 动量比封顶,见 _momentum_score),自由
    unigram 不直接入榜(护栏见 _is_free_unigram)但按分值序补位,榜单恒取
    TOP_KEYWORDS 个(不足时允许浮动,数据稀薄期不强凑);
  - AI 精修:每批把候选池(REFINE_POOL_SIZE 个,宽口径含被护栏拦下的词)交给
    LLM 合并同义/剔除泛词/规范 display(可为中文);配置缺失或调用/校验失败
    原样保留统计回退榜,不告警不阻断;
  - trend:近 3 日命中和 vs 前 3 日命中和,±max(1, 前 3 日 × 15%) 容差带内算
    flat(无容差时 101 vs 100 也标 up,箭头近似噪声);
  - 每词保留 <= MAX_ITEMS_PER_KEYWORD 条代表条目(日期新的优先,按 URL 去重)。

用法:
  # 对本地数据仓库 checkout 生成并写回其 trends.json
  python3 scripts/trend_keywords.py --repo-dir repo

  # 只打印不写文件(本地验证)
  python3 scripts/trend_keywords.py --repo-dir repo --dry-run
"""

import argparse
import json
import os
import re
import sys
import time
from collections import Counter
from datetime import datetime, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from common import SOURCE_KEYS, BEIJING_TZ, now_cst

# AI 精修复用 ai_summary 的配置单点(环境变量名 + config_ready),调用统一走
# ai_client.call_llm(围栏剥离 / 429 快速重试 / thinking 开关都已收口在内)
import ai_client
from ai_summary import ENV_API_KEY, ENV_BASE_URL, ENV_MODEL, config_ready

WINDOW_DAYS = 14            # 统计窗口(天)
TOP_KEYWORDS = 10           # 入榜热词数
MIN_TOTAL = 3               # 窗口期总命中下限
MIN_DAYS_ACTIVE = 2         # 窗口期活跃天数下限(有命中即算活跃)
MAX_ITEMS_PER_KEYWORD = 3   # 每词代表条目上限
REFINE_POOL_SIZE = 25       # AI 精修候选池大小(按分值序,宽口径不过护栏)
POOL_ITEM_SAMPLES = 6       # 候选池每词代表条目数(给合并重选留原料,比终榜宽裕)
MOMENTUM_CAP = 2.5          # 动量比封顶(防前 3 日基数小时单日尖峰爆表)
TREND_FLAT_RATIO = 0.15     # trend 容差带比例:|近3日-前3日| <= max(1, 前3日×此值) 算 flat

# 趋势历史索引(trends_history.json)的指针保留天数。对齐总览归档
# (fetch_data.OVERVIEW_RETENTION_DAYS)的 90 天档位:趋势归档每份仅十几 KB,
# 多留价值高;旧归档目录只增不删,超出保留期的日期仅不再被索引指向。
TRENDS_RETENTION_DAYS = 90

# 英文停用词:标准停用词 + 标题高频填充词 + 领域泛词(ai/model 之类在 AI 资讯
# 语境里无区分度,入榜只会霸榜)。
STOPWORDS = {
    # 标准功能词
    "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can",
    "could", "did", "do", "does", "for", "from", "had", "has", "have", "he",
    "her", "here", "him", "his", "how", "i", "if", "in", "into", "is", "it",
    "its", "just", "me", "my", "no", "not", "now", "of", "on", "or", "our",
    "out", "over", "own", "per", "she", "so", "than", "that", "the", "their",
    "them", "then", "there", "these", "they", "this", "to", "too", "under",
    "up", "us", "via", "was", "we", "were", "what", "when", "where", "which",
    "while", "who", "why", "will", "with", "you", "your",
    # 标题填充词
    "new", "best", "top", "first", "way", "ways", "thing", "things", "say",
    "says", "said", "see", "look", "want", "need", "know", "think", "take",
    "get", "got", "make", "made", "use", "used", "using", "let", "like",
    "one", "two", "day", "days", "week", "weeks", "year", "years", "time",
    "people", "good", "big", "going", "go", "come", "comes", "show", "ask",
    "hn", "vs", "launch", "launches", "launched", "release", "releases",
    "released", "announce", "announces", "announced", "introducing", "unveils",
    "update", "updates", "review", "guide", "tutorials", "tutorial",
    # 领域泛词(AI 资讯里处处出现,无趋势意义)
    "ai", "model", "models", "app", "apps", "tool", "tools", "tech",
    "software", "data", "code", "coding", "open", "source",
    # 各源结构性噪声(Rundown 的 "Exclusive:"/"PLUS:" 栏目前缀;HF 论文标题
    # 八股 "Vision-Language"/"Efficient"/"Multi-" 等)
    "exclusive", "plus", "efficient", "multi", "vision", "language", "large",
    "scale", "based", "framework", "benchmark", "benchmarks", "training",
    "novel", "learning", "real", "native", "generation", "world", "self",
    "frontier", "building", "end", "platform",
}

# AI 领域实体别名表:canonical key → (display, 变体集合)。
# 变体一律小写;bigram 变体含空格(如 "gpt 5");含 CJK 的变体走子串匹配。
# 新增实体只改本表一处。
ALIASES = {
    "openai": ("OpenAI", {"openai", "open ai"}),
    "chatgpt": ("ChatGPT", {"chatgpt", "chat gpt"}),
    "gpt-5": ("GPT-5", {"gpt5", "gpt-5", "gpt 5"}),
    "gpt-4": ("GPT-4", {"gpt4", "gpt-4", "gpt 4"}),
    "anthropic": ("Anthropic", {"anthropic"}),
    "claude": ("Claude", {"claude"}),
    "claude-code": ("Claude Code", {"claude code"}),
    "google": ("Google", {"google"}),
    "gemini": ("Gemini", {"gemini"}),
    "deepmind": ("DeepMind", {"deepmind", "deep mind"}),
    "deepseek": ("DeepSeek", {"deepseek", "deep seek", "深度求索"}),
    "meta": ("Meta", {"meta"}),
    "llama": ("Llama", {"llama"}),
    "microsoft": ("Microsoft", {"microsoft"}),
    "copilot": ("GitHub Copilot", {"copilot"}),
    "apple": ("Apple", {"apple"}),
    "nvidia": ("NVIDIA", {"nvidia"}),
    "xai": ("xAI", {"xai", "x ai"}),
    "grok": ("Grok", {"grok"}),
    "mistral": ("Mistral", {"mistral"}),
    "qwen": ("Qwen 千问", {"qwen", "千问", "通义千问"}),
    "kimi": ("Kimi", {"kimi", "月之暗面"}),
    "doubao": ("豆包", {"doubao", "豆包"}),
    "zhipu": ("智谱", {"zhipu", "智谱"}),
    "alibaba": ("Alibaba", {"alibaba", "阿里"}),
    "bytedance": ("字节跳动", {"bytedance", "字节跳动", "字节"}),
    "sora": ("Sora", {"sora"}),
    "midjourney": ("Midjourney", {"midjourney"}),
    "stable-diffusion": ("Stable Diffusion", {"stable diffusion"}),
    "huggingface": ("Hugging Face", {"huggingface", "hugging face"}),
    "github": ("GitHub", {"github"}),
    "cursor": ("Cursor", {"cursor"}),
    "vscode": ("VS Code", {"vscode", "vs code"}),
    "rag": ("RAG", {"rag"}),
    "mcp": ("MCP", {"mcp"}),
    "agent": ("Agent", {"agent", "agents", "agentic", "智能体"}),
    "robot": ("机器人", {"robot", "robots", "robotics", "机器人"}),
    "open-source": ("开源 Open Source", {"open source", "开源"}),
    "video-generation": ("视频生成", {"video generation", "video generative", "视频生成"}),
    "world-model": ("世界模型", {"world model", "world models", "世界模型"}),
}

# 变体(小写)→ canonical key 的查找表(构建一次)
_VARIANT_TO_CANONICAL = {}
for _canon, (_display, _variants) in ALIASES.items():
    for _v in _variants:
        _VARIANT_TO_CANONICAL[_v] = _canon

# 含 CJK 的变体(子串匹配用):变体原文 → canonical key
_CJK_VARIANTS = {v: c for v, c in _VARIANT_TO_CANONICAL.items()
                 if re.search(r"[一-鿿]", v)}

_TOKEN_RE = re.compile(r"[A-Za-z0-9]+")


# ===== 快照扫描 =====

def _iter_daily_snapshots(repo_dir):
    """
    遍历各源目录,产出 {source: {date: 当日最后一次快照路径}}。

    只接受定宽格式(<YYYY-MM-DD>/<HH-MM>-data.json),字典序最大即当日最新
    (对齐 fetch_data._scan_history 的语义)。
    """
    result = {}
    for source in SOURCE_KEYS:
        src_root = os.path.join(repo_dir, source)
        if not os.path.isdir(src_root):
            continue
        last_of_day = {}
        for date_name in os.listdir(src_root):
            date_dir = os.path.join(src_root, date_name)
            if not os.path.isdir(date_dir) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date_name):
                continue
            for fname in os.listdir(date_dir):
                m = re.fullmatch(r"(\d{2}-\d{2})-data\.json", fname)
                if not m:
                    continue
                if date_name not in last_of_day or m.group(1) > last_of_day[date_name][0]:
                    last_of_day[date_name] = (m.group(1), os.path.join(date_dir, fname))
        if last_of_day:
            result[source] = {d: p for d, (_, p) in last_of_day.items()}
    return result


# ===== 每源文本/标题/URL 字段映射(对齐 overview_summary._extract_items) =====

def _s(o, key, default=""):
    """安全取字符串,剥白边,None 转空串。"""
    v = o.get(key, default)
    return str(v).strip() if v is not None else default


def _item_fields(source, o):
    """
    从一条快照 item 提取 (text_for_matching, title_for_display, url)。
    text_for_matching: 参与词频统计的文本(英文优先,中文标题也带上供 CJK 变体匹配);
    标题/URL 为空串的条目返回 None(调用方丢弃)。
    """
    if source == "hackernews":
        text, title = _s(o, "title"), _s(o, "title")
        url = _s(o, "target_url") or _s(o, "url")
    elif source == "github-trending":
        name = f"{_s(o, 'owner')}/{_s(o, 'name')}".strip("/")
        text = f"{name} {_s(o, 'description')}"
        title, url = name, _s(o, "url")
    elif source == "huggingface-papers":
        text, title = _s(o, "title"), _s(o, "title")
        url = _s(o, "url")
    elif source == "producthunt":
        text = f"{_s(o, 'name')} {_s(o, 'tagline')}"
        title, url = _s(o, "name"), _s(o, "url")
    elif source == "rundown-ai":
        text, title = _s(o, "title"), _s(o, "title")
        url = _s(o, "url")
    elif source == "aihot-featured":
        # titleEn 英文原文优先;中文 title 一并带上(供 CJK 变体子串匹配)
        text = f"{_s(o, 'titleEn')} {_s(o, 'title')}"
        title = _s(o, "title")
        url = _s(o, "permalink") or _s(o, "url")
    elif source == "openai-anthropic-news":
        text, title = _s(o, "title"), _s(o, "title")
        url = _s(o, "url")
    elif source == "stormzhang-ai":
        # english 英文原文优先;中文 summary 一并带上(供 CJK 变体子串匹配)。
        # english 尾部常带 TLDR 赞助行("PLUS: <软广> <作者>, +N"),且赞助条目整条
        # english 就是 "PLUS: ...";partition 两种都覆盖,避免作者名/赞助商混进词频
        english = _s(o, "english").partition("PLUS:")[0]
        text = f"{english} {_s(o, 'summary')}"
        title, url = _s(o, "summary"), _s(o, "url")
    else:
        return None
    if not text.strip() or not title.strip() or not url.strip():
        return None
    return text, title, url


# ===== 词条提取与归一 =====

def _extract_terms(text):
    """
    从一条文本提取 canonical 词条集合(按条目命中计,同条内同词只算一次)。

    返回 {canonical_key: 原始写法}(同词多种写法时保留首见,display 由上层
    按频次票选)。英文走 unigram + 相邻 bigram;CJK 变体走子串匹配。
    """
    found = {}
    # (lower, original) 成对 token 流:在原文上做大小写不敏感匹配再小写化,
    # 避免 lower() 改变个别 Unicode 字符长度导致的位置错位
    tokens = [(m.group(0).lower(), m.group(0)) for m in _TOKEN_RE.finditer(text)]

    def _record(raw, display_hint):
        canon = _VARIANT_TO_CANONICAL.get(raw, raw)
        if canon not in found:
            found[canon] = display_hint

    for i, (low, orig) in enumerate(tokens):
        # unigram:别名变体直接命中(不受停用词约束,如 "rag");否则必须含字母、
        # 非停用词(纯数字只活在 bigram 里,如 "gpt 5" 的 "5")
        if low in _VARIANT_TO_CANONICAL:
            _record(low, orig)
        elif len(low) >= 2 and re.search(r"[a-z]", low) and low not in STOPWORDS:
            _record(low, orig)
        # bigram:别名变体直接命中(如 "open source" 两侧都是停用词仍收录);
        # 否则任一侧为停用词即丢弃,且至少一侧含字母
        if i + 1 < len(tokens):
            nxt_low, nxt_orig = tokens[i + 1]
            pair = f"{low} {nxt_low}"
            if pair in _VARIANT_TO_CANONICAL:
                _record(pair, f"{orig} {nxt_orig}")
            elif (low not in STOPWORDS and nxt_low not in STOPWORDS
                    and re.search(r"[a-z]", pair)):
                _record(pair, f"{orig} {nxt_orig}")
    # CJK 变体子串匹配(不分词)
    for variant, canon in _CJK_VARIANTS.items():
        if variant in text:
            if canon not in found:
                found[canon] = variant
    return found


def _display_of(canon, surface_counter):
    """display 取名:映射表指定形优先;否则语料里最常见的原始大小写写法。"""
    if canon in ALIASES:
        return ALIASES[canon][0]
    if surface_counter:
        return surface_counter.most_common(1)[0][0]
    return canon


# ===== 主流程 =====

def _momentum_score(hits):
    """动量加权分:近 7 日命中和 × 动量比(封顶)。

    动量比 = (近3日+1)/(前3日+1),+1 平滑防除零。纯 total 排序是热度榜,
    Agent/OpenAI 这类常青词常年霸榜、头部固化;加权后上升中的词能顶到前面,
    稳定泛词自然沉底。门槛(total/daysActive)仍按全窗口算,只改排序键。
    """
    recent7 = sum(hits[-7:])
    momentum = (sum(hits[-3:]) + 1) / (sum(hits[-6:-3]) + 1)
    return recent7 * min(momentum, MOMENTUM_CAP)


def _trend_of(hits):
    """涨跌标记:近 3 日 vs 前 3 日命中和,容差带内算 flat。

    无容差时 101 vs 100 也会标 up,箭头近似随机噪声;带宽取
    max(1, 前 3 日 × TREND_FLAT_RATIO)。
    """
    recent, previous = sum(hits[-3:]), sum(hits[-6:-3])
    band = max(1, round(previous * TREND_FLAT_RATIO))
    if recent > previous + band:
        return "up"
    if recent < previous - band:
        return "down"
    return "flat"


def _is_free_unigram(canon):
    """是否为「非别名归一的自由 unigram」——统计回退榜的护栏拦截对象。

    这类词多为拆词残留(work/long 来自 "future of work"/"long-horizon" 的
    碎片),无实体/主题信息量;别名表词与自由 bigram(自带上下文)不受限。
    被拦下的词仍在 AI 精修候选池里,可由 AI 捞回。
    """
    return " " not in canon and canon not in ALIASES


def _select_items(cands, limit):
    """从(已按日期降序的)代表条目候选里选 ≤limit 条:URL 去重 + 两轮制。

    第一轮每源至多 1 条(避免榜单出口被聚合源屠榜),凑不满再放开补齐。
    """
    seen_urls, out = set(), []
    for diverse_only in (True, False):
        for it in cands:
            if len(out) >= limit:
                break
            if it["url"] in seen_urls:
                continue
            if diverse_only and any(o["source"] == it["source"] for o in out):
                continue
            seen_urls.add(it["url"])
            out.append(it)
    return out


def _finalize_entry(entry):
    """把候选池词条收口成终榜词条:代表条目截到 MAX_ITEMS_PER_KEYWORD。"""
    out = dict(entry)
    out["items"] = _select_items(entry["items"], MAX_ITEMS_PER_KEYWORD)
    return out


def generate_trends(repo_dir, now=None):
    """
    扫描 repo_dir 近 WINDOW_DAYS 天快照,生成 (趋势榜 dict, AI 精修候选池);
    可用快照不足时返回 None。

    趋势榜 keywords 先装「统计回退榜」(动量加权排序 + 自由 unigram 护栏后的
    top TOP_KEYWORDS);调用方可把候选池交给 refine_keywords_with_ai 精修替换。
    候选池按分值序取 REFINE_POOL_SIZE 个、宽口径不过护栏。

    now: datetime(北京时间),默认取当前;generatedAt 用它,日期窗口以快照实际
    最大日期为锚(而不是 now——本地验证 / 补跑时窗口对准数据而非运行时刻)。
    """
    snapshots = _iter_daily_snapshots(repo_dir)
    if not snapshots:
        print("[TRENDS] 未发现任何源快照,跳过", file=sys.stderr)
        return None

    all_dates = sorted({d for per_src in snapshots.values() for d in per_src})
    if not all_dates:
        return None
    anchor = all_dates[-1]  # 数据锚点 = 全源最大日期
    anchor_dt = datetime.strptime(anchor, "%Y-%m-%d").replace(tzinfo=BEIJING_TZ)
    # 窗口 = 日历连续 WINDOW_DAYS 天(缺数据的日期命中为 0,sparkline 如实留空)
    days = [(anchor_dt - timedelta(days=WINDOW_DAYS - 1 - i)).strftime("%Y-%m-%d")
            for i in range(WINDOW_DAYS)]
    day_index = {d: i for i, d in enumerate(days)}

    # 统计容器(canonical key 索引)
    daily_hits = {}       # canon -> [WINDOW_DAYS] 每日命中条目数
    surface_forms = {}    # canon -> Counter(原始写法票选 display 用)
    hit_items = {}        # canon -> [(date, source, title, url)](代表条目候选)

    for source, per_day in snapshots.items():
        for date, path in sorted(per_day.items()):
            if date not in day_index:
                continue  # 窗口外的历史不统计
            try:
                with open(path, "r", encoding="utf-8") as f:
                    snap = json.load(f)
            except Exception as e:
                print(f"[TRENDS] 读 {source}/{date} 快照失败:{type(e).__name__}: {e}",
                      file=sys.stderr)
                continue
            items = snap.get("items") or []
            if not isinstance(items, list):
                continue
            for o in items:
                if not isinstance(o, dict):
                    continue
                fields = _item_fields(source, o)
                if fields is None:
                    continue
                text, title, url = fields
                for canon, surface in _extract_terms(text).items():
                    if canon not in daily_hits:
                        daily_hits[canon] = [0] * WINDOW_DAYS
                        surface_forms[canon] = Counter()
                        hit_items[canon] = []
                    daily_hits[canon][day_index[date]] += 1
                    surface_forms[canon][surface] += 1
                    hit_items[canon].append((date, source, title, url))

    # 入榜门槛:total / daysActive(滤掉单日闪现);排序按动量加权分
    # (见 _momentum_score)——纯 total 排序是热度榜,常青词常年霸榜头部固化
    qualified = []
    for canon, hits in daily_hits.items():
        total = sum(hits)
        days_active = sum(1 for h in hits if h > 0)
        if total >= MIN_TOTAL and days_active >= MIN_DAYS_ACTIVE:
            qualified.append((_momentum_score(hits), total, canon))
    qualified.sort(key=lambda x: (-x[0], -x[1], x[2]))
    if not qualified:
        print("[TRENDS] 无热词达到入榜门槛,跳过", file=sys.stderr)
        return None

    def _entry(canon, item_cap):
        """把一个 canonical key 的统计结果组装成榜单词条 dict。"""
        hits = daily_hits[canon]
        # 代表条目:日期新的优先 + 按 URL 去重;第一轮优先源多样性(每源至多 1 条,
        # 避免榜单出口被聚合源屠榜),凑不满上限再放开补齐
        cands = [{"title": t, "url": u, "source": s, "date": d}
                 for d, s, t, u in sorted(hit_items[canon], reverse=True)]
        return {
            "term": canon,
            "display": _display_of(canon, surface_forms[canon]),
            "total": sum(hits),
            "daysActive": sum(1 for h in hits if h > 0),
            "daily": hits,
            "trend": _trend_of(hits),
            "items": _select_items(cands, item_cap),
        }

    # 候选池:按分值序取宽口径 top,不过自由 unigram 护栏(让 AI 看得到
    # memory/codex 这类可能值得捞回的词)
    pool = [_entry(canon, POOL_ITEM_SAMPLES)
            for _, _, canon in qualified[:REFINE_POOL_SIZE]]

    # 统计回退榜:自由 unigram 不直接入榜,但按分值序补位到满 TOP_KEYWORDS
    # (榜单长度恒定,避免 AI/统计产出 6-10 个浮动让用户以为缺数据)
    guarded = [k for k in pool if not _is_free_unigram(k["term"])]
    guarded += [k for k in pool if _is_free_unigram(k["term"])]
    keywords = [_finalize_entry(k) for k in guarded[:TOP_KEYWORDS]]

    now = now or now_cst()
    return {
        "generatedAt": int(now.timestamp() * 1000),
        "windowDays": WINDOW_DAYS,
        "days": days,
        "keywords": keywords,
    }, pool


# ===== AI 精修(可选增强,失败零降级回退纯统计) =====

_REFINE_SYSTEM_PROMPT = (
    "你是 AI 资讯热词榜的编辑。给你一份按近期热度排序的候选热词列表"
    "(含命中统计与示例标题),请产出最终榜单:合并语义相同或同属一个话题的词、"
    "剔除没有实体或主题信息量的泛词(形容词/常用动词/拆词残留)、给每个入选词"
    "一个规范的展示名。只做筛选与命名,不发明候选之外的新词。"
)


def _refine_user_prompt(pool):
    """把候选池序列化成 AI 输入(带示例标题,帮模型判断词条的话题归属)。"""
    cands = [{
        "term": k["term"],
        "display": k["display"],
        "total": k["total"],
        "daysActive": k["daysActive"],
        "trend": k["trend"],
        "samples": [it["title"] for it in k["items"][:2]],
    } for k in pool]
    return (
        "候选热词列表(JSON):\n"
        + json.dumps(cands, ensure_ascii=False)
        + "\n\n要求:\n"
          "1. 从候选中选出 10 个最终热词(候选确实不足时可少选),按近期热度与上升势头综合排序;\n"
          "2. 语义相同或同属一个话题的候选合并:主词放 term,被合并词列进 absorb;\n"
          "3. 剔除泛词:形容词/动词/无区分度的常用词不要入选;\n"
          "4. 每个入选词给 display:行业惯用名,不超过 16 个字符,可用中文;\n"
          '5. 只输出 JSON 对象:{"selected": [{"term": "...", "display": "...", '
          '"absorb": ["..."]}]},absorb 可为空数组;不要输出任何解释。\n'
    )


def _merge_pool_entries(main, absorbed, display):
    """把 absorbed 词条的统计合并进 main,产出终榜词条。

    daily 按下标累加,total/daysActive/trend(含容差带)随之重算;代表条目
    取并集后重跑两轮制选择(原料是各词条已截到 POOL_ITEM_SAMPLES 的列表,
    对合并场景足够)。
    """
    daily = list(main["daily"])
    items = list(main["items"])
    for other in absorbed:
        daily = [a + b for a, b in zip(daily, other["daily"])]
        items = items + other["items"]
    items.sort(key=lambda it: it["date"], reverse=True)
    return {
        "term": main["term"],
        "display": display,
        "total": sum(daily),
        "daysActive": sum(1 for h in daily if h > 0),
        "daily": daily,
        "trend": _trend_of(daily),
        "items": _select_items(items, MAX_ITEMS_PER_KEYWORD),
    }


def refine_keywords_with_ai(trends, pool):
    """
    用 AI 对候选池做一次精修,成功时就地替换 trends["keywords"] 并返回 True。

    每批至多一次调用,对齐 ai_summary 的配置入口;任何失败(配置缺失/调用
    异常/结果校验不过)只告警并保留统计回退榜,返回 False——精修是锦上添花,
    绝不阻断主流程。
    """
    if not config_ready():
        print("[TRENDS] AI 精修跳过(未配置 AI 环境变量,使用统计回退榜)",
              file=sys.stderr)
        return False
    base_url = os.getenv(ENV_BASE_URL)
    model = os.getenv(ENV_MODEL)
    api_key = os.getenv(ENV_API_KEY)
    parsed = None
    for attempt in range(2):  # 业务层重试 1 次(传输层 429/503 重试在 ai_client 内)
        try:
            parsed = ai_client.call_llm(
                _REFINE_SYSTEM_PROMPT, _refine_user_prompt(pool),
                base_url, model, api_key,
                timeout=60, temperature=0.2, expect="object",
            )
            break
        except Exception as e:
            if attempt == 0:
                print(f"[TRENDS] AI 精修调用失败,10s 后重试一次:"
                      f"{type(e).__name__}: {e}", file=sys.stderr)
                time.sleep(10)
                continue
            print(f"[TRENDS] AI 精修失败(回退统计回退榜):{type(e).__name__}: {e}",
                  file=sys.stderr)
            return False
    try:
        selected = parsed.get("selected")
        if not isinstance(selected, list):
            raise RuntimeError(f"selected 非数组:{type(selected)}")
        if not 5 <= len(selected) <= TOP_KEYWORDS:
            raise RuntimeError(f"selected 数量异常:{len(selected)}")
        pool_by_term = {k["term"]: k for k in pool}
        used = set()
        entries = []
        for item in selected:
            if not isinstance(item, dict):
                raise RuntimeError("selected 元素非对象")
            term = item.get("term")
            display = str(item.get("display") or "").strip()
            absorb = item.get("absorb") or []
            if term not in pool_by_term or term in used:
                raise RuntimeError(f"term 不在候选池或重复:{term!r}")
            if not display or len(display) > 24:
                raise RuntimeError(f"display 非法:{display!r}")
            if not isinstance(absorb, list):
                raise RuntimeError("absorb 非数组")
            absorbed = []
            for a in absorb:
                if a not in pool_by_term or a in used:
                    raise RuntimeError(f"absorb 词条不在候选池或重复:{a!r}")
                absorbed.append(pool_by_term[a])
                used.add(a)
            used.add(term)
            entries.append(_merge_pool_entries(pool_by_term[term], absorbed, display))
        absorbed_count = len(used) - len(entries)
        # AI 未选满 TOP_KEYWORDS 时按分值序用统计候选补齐(榜单长度恒定,
        # 避免用户以为缺数据);候选池耗尽时才允许少于 10 个
        shortage = TOP_KEYWORDS - len(entries)
        if shortage > 0:
            fill = [_finalize_entry(k) for k in pool if k["term"] not in used][:shortage]
            entries += fill
            if fill:
                print(f"[TRENDS] AI 精修未选满,按分值序补齐 {len(fill)} 个统计候选")
        trends["keywords"] = entries
        print(f"[TRENDS] AI 精修完成:{len(entries)} 个热词"
              f"(合并吸收 {absorbed_count} 个候选)")
        return True
    except Exception as e:
        print(f"[TRENDS] AI 精修结果校验失败(回退统计回退榜):"
              f"{type(e).__name__}: {e}", file=sys.stderr)
        return False


def _load_trends_history(repo_dir):
    """读根级独立索引 trends_history.json:{date: relpath}(relpath 相对 trends/)。

    缺失(首期运行)/损坏一律返回 {}(按「无历史」处理,不告警不阻断——
    索引本来就是锦上添花,坏掉了下一批写回即自愈)。
    """
    try:
        with open(os.path.join(repo_dir, "trends_history.json"), "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception:
        return {}
    if not isinstance(data, dict):
        return {}
    return {d: p for d, p in data.items() if isinstance(d, str) and isinstance(p, str)}


def attach_rank_changes(trends, repo_dir, today):
    """
    给榜单逐项附上排名变化字段(就地修改 trends["keywords"]):

      基准 = 索引中日期早于 today 的最近一期归档(昨日最后一期;昨日无运行时
      自动回退更早的最近一日。一天内多批共用同一基准,标记稳定不漂移)
      - 上期在榜   → rankChange = 上期排名 - 新排名(正 = 上升)
      - 上期不在榜 → isNewEntry = True(新上榜)
      - 无历史基准 → 不附加任何字段(App 侧不显示标记)

    必须在写入今日归档 / 更新索引**之前**调用(排除今日早批的干扰)。
    返回基准日期(无可用基准时返回 None,供调用方日志)。
    """
    history = _load_trends_history(repo_dir)
    baseline_date = max((d for d in history if d < today), default=None)
    if baseline_date is None:
        return None
    try:
        with open(os.path.join(repo_dir, "trends", history[baseline_date]),
                  "r", encoding="utf-8") as f:
            baseline = json.load(f)
    except Exception as e:
        print(f"[TRENDS] 读基准归档失败({baseline_date},不附加变化字段):"
              f"{type(e).__name__}: {e}", file=sys.stderr)
        return None
    prev_ranks = {}
    for i, kw in enumerate(baseline.get("keywords") or []):
        term = kw.get("term") if isinstance(kw, dict) else None
        if term:
            prev_ranks[term] = i + 1
    if not prev_ranks:
        return None
    for i, kw in enumerate(trends["keywords"]):
        prev = prev_ranks.get(kw["term"])
        if prev is None:
            kw["isNewEntry"] = True
        else:
            kw["rankChange"] = prev - (i + 1)
    return baseline_date


def _write_trends_archive(repo_dir, trends, now):
    """
    写按日归档 trends/<date>/<HH-MM>-data.json(内容与根级 trends.json 相同),
    并更新根级索引 trends_history.json:{date: relpath}(relpath 相对 trends/,
    同日多批后写的覆盖指针,即索引始终指向当日最后一期)。

    索引合并规则与 overview_history 同构:旧索引整体继承 + 今日覆盖 →
    按日期倒序截前 TRENDS_RETENTION_DAYS 天(dict 保持插入序,写出可读)。
    """
    date = now.strftime("%Y-%m-%d")
    rel = f"{date}/{now.strftime('%H-%M')}-data.json"
    arc_path = os.path.join(repo_dir, "trends", *rel.split("/"))
    os.makedirs(os.path.dirname(arc_path), exist_ok=True)
    with open(arc_path, "w", encoding="utf-8") as f:
        json.dump(trends, f, ensure_ascii=False, indent=2)
        f.write("\n")
    merged = _load_trends_history(repo_dir)
    merged[date] = rel
    keep = sorted(merged, reverse=True)[:TRENDS_RETENTION_DAYS]
    with open(os.path.join(repo_dir, "trends_history.json"), "w", encoding="utf-8") as f:
        json.dump({d: merged[d] for d in keep}, f, ensure_ascii=False, indent=2)
        f.write("\n")


def write_trends(repo_dir):
    """
    生成趋势并写根级独立文件 <repo_dir>/trends.json(整文件覆盖,内容与原
    index.json 内联 `latest_trends` 字段同构),同时落一份按日归档并维护
    trends_history.json 索引。

    任何失败只告警、返回 False,不抛异常——趋势是锦上添花,不得阻断 push 主
    流程(对齐单源失败的降级哲学;文件暂缺时 App 走空态,下次运行自愈。统计
    部分可从快照全量重算,AI 精修失败的批次退回统计回退榜,均无继承语义)。
    归档/索引失败时根级 trends.json 已写入,仍视为成功;基准读取失败只是少
    rankChange 字段。
    """
    try:
        generated = generate_trends(repo_dir)
        if generated is None:
            return False
        trends, pool = generated
        # AI 精修(可选增强):成功则替换为精修榜,失败保留统计回退榜
        refine_keywords_with_ai(trends, pool)
        now = now_cst()
        today = now.strftime("%Y-%m-%d")
        # 排名变化:先算基准,再写今日归档(否则会把今日早批当成基准)
        baseline = attach_rank_changes(trends, repo_dir, today)
        if baseline:
            print(f"[TRENDS] 排名变化基准:{baseline} 最后一期")
        trends_path = os.path.join(repo_dir, "trends.json")
        with open(trends_path, "w", encoding="utf-8") as f:
            json.dump(trends, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print(f"[TRENDS] 已写入 trends.json:{len(trends['keywords'])} 个热词,"
              f"窗口 {trends['days'][0]} ~ {trends['days'][-1]}")
        try:
            _write_trends_archive(repo_dir, trends, now)
        except Exception as e:
            print(f"[TRENDS] 历史归档失败(不阻断,trends.json 已写入):"
                  f"{type(e).__name__}: {e}", file=sys.stderr)
        return True
    except Exception as e:
        print(f"[TRENDS] 生成失败(不阻断推送):{type(e).__name__}: {e}", file=sys.stderr)
        return False


def main():
    parser = argparse.ArgumentParser(
        description="跨源热词趋势统计(统计为主 + 每批至多一次 AI 精修,失败回退纯统计)")
    parser.add_argument("--repo-dir", default="repo",
                        help="数据仓库 checkout 根目录(默认 ./repo)")
    parser.add_argument("--dry-run", action="store_true",
                        help="只打印结果摘要,不写 trends.json(本地验证用)")
    args = parser.parse_args()

    # 正式路径全权交给 write_trends(其自身日志足够),避免预览 + 写入各算一遍
    if not args.dry_run:
        return 0 if write_trends(args.repo_dir) else 1

    generated = generate_trends(args.repo_dir)
    if generated is None:
        return 1
    trends, pool = generated
    refined = refine_keywords_with_ai(trends, pool)
    # dry-run 也附上排名变化字段(纯读旧索引/归档,不写任何文件)
    baseline = attach_rank_changes(trends, args.repo_dir, now_cst().strftime("%Y-%m-%d"))
    print(f"窗口:{trends['days'][0]} ~ {trends['days'][-1]}({trends['windowDays']} 天)")
    print(f"榜单来源:{'AI 精修' if refined else '统计回退'}")
    print(f"排名变化基准:{baseline + ' 最后一期' if baseline else '无(不附加变化字段)'}")
    for i, kw in enumerate(trends["keywords"], 1):
        arrow = {"up": "↑", "down": "↓", "flat": "→"}[kw["trend"]]
        if kw.get("isNewEntry"):
            delta = "新上榜"
        elif "rankChange" in kw:
            delta = "持平" if kw["rankChange"] == 0 else f"{kw['rankChange']:+d}"
        else:
            delta = "--"
        print(f"  #{i} {kw['display']:<20} 总 {kw['total']:>3} 次 / "
              f"{kw['daysActive']:>2} 天 {arrow} {delta:<4} daily={kw['daily']}")
        for it in kw["items"]:
            print(f"      - [{it['source']}] {it['date']} {it['title'][:50]}")
    print("(dry-run,未写 trends.json)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
