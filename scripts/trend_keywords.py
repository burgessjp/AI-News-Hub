#!/usr/bin/env python3
"""
跨源热词趋势统计(纯统计,不调 AI,零 token 成本)。

「趋势」Tab 的数据生产者:扫描数据仓库 checkout 里近 WINDOW_DAYS 天的 8 源快照,
按「条目命中」做词频统计(一个词在一条条目中出现算 1 次,同日同条不重复计),
产出一个热词榜(每日命中序列 + 涨跌 + 代表条目),写进根级独立文件
`trends.json`(内容与原 index.json 内联 `latest_trends` 字段同构,整文件覆盖)。

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
    按 (total, daysActive) 降序取 TOP_KEYWORDS;
  - trend:近 3 日命中和 vs 前 3 日命中和 → up / down / flat;
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
from collections import Counter
from datetime import datetime, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from common import SOURCE_KEYS, BEIJING_TZ, now_cst

WINDOW_DAYS = 14            # 统计窗口(天)
TOP_KEYWORDS = 10           # 入榜热词数
MIN_TOTAL = 3               # 窗口期总命中下限
MIN_DAYS_ACTIVE = 2         # 窗口期活跃天数下限(有命中即算活跃)
MAX_ITEMS_PER_KEYWORD = 3   # 每词代表条目上限

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
    "agent": ("Agent", {"agent", "agents", "智能体"}),
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

def generate_trends(repo_dir, now=None):
    """
    扫描 repo_dir 近 WINDOW_DAYS 天快照,生成趋势榜 dict;可用快照不足时返回 None。

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

    # 入榜过滤 + 排序:total 降序、daysActive 降序(同票时活跃天数多者靠前)
    ranked = []
    for canon, hits in daily_hits.items():
        total = sum(hits)
        days_active = sum(1 for h in hits if h > 0)
        if total >= MIN_TOTAL and days_active >= MIN_DAYS_ACTIVE:
            ranked.append((canon, total, days_active))
    ranked.sort(key=lambda x: (-x[1], -x[2], x[0]))
    ranked = ranked[:TOP_KEYWORDS]
    if not ranked:
        print("[TRENDS] 无热词达到入榜门槛,跳过", file=sys.stderr)
        return None

    keywords = []
    for canon, total, days_active in ranked:
        hits = daily_hits[canon]
        # trend:近 3 日命中和 vs 前 3 日命中和
        recent, previous = sum(hits[-3:]), sum(hits[-6:-3])
        trend = "up" if recent > previous else ("down" if recent < previous else "flat")
        # 代表条目:日期新的优先 + 按 URL 去重;第一轮优先源多样性(每源至多 1 条,
        # 避免榜单出口被聚合源屠榜),凑不满 MAX_ITEMS_PER_KEYWORD 再放开补齐
        seen_urls, top_items = set(), []
        cands = sorted(hit_items[canon], reverse=True)
        for diverse_only in (True, False):
            for date, source, title, url in cands:
                if len(top_items) >= MAX_ITEMS_PER_KEYWORD:
                    break
                if url in seen_urls:
                    continue
                if diverse_only and any(it["source"] == source for it in top_items):
                    continue
                seen_urls.add(url)
                top_items.append({"title": title, "url": url, "source": source, "date": date})
        keywords.append({
            "term": canon,
            "display": _display_of(canon, surface_forms[canon]),
            "total": total,
            "daysActive": days_active,
            "daily": hits,
            "trend": trend,
            "items": top_items,
        })

    now = now or now_cst()
    return {
        "generatedAt": int(now.timestamp() * 1000),
        "windowDays": WINDOW_DAYS,
        "days": days,
        "keywords": keywords,
    }


def write_trends(repo_dir):
    """
    生成趋势并写根级独立文件 <repo_dir>/trends.json(整文件覆盖,内容与原
    index.json 内联 `latest_trends` 字段同构)。

    任何失败只告警、返回 False,不抛异常——趋势是纯统计的锦上添花,
    不得阻断 push 主流程(对齐单源失败的降级哲学;文件暂缺时 App 走空态,
    下次运行自愈。趋势可从快照全量重算,无继承语义)。
    """
    try:
        trends = generate_trends(repo_dir)
        if trends is None:
            return False
        trends_path = os.path.join(repo_dir, "trends.json")
        with open(trends_path, "w", encoding="utf-8") as f:
            json.dump(trends, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print(f"[TRENDS] 已写入 trends.json:{len(trends['keywords'])} 个热词,"
              f"窗口 {trends['days'][0]} ~ {trends['days'][-1]}")
        return True
    except Exception as e:
        print(f"[TRENDS] 生成失败(不阻断推送):{type(e).__name__}: {e}", file=sys.stderr)
        return False


def main():
    parser = argparse.ArgumentParser(description="跨源热词趋势统计(纯统计,不调 AI)")
    parser.add_argument("--repo-dir", default="repo",
                        help="数据仓库 checkout 根目录(默认 ./repo)")
    parser.add_argument("--dry-run", action="store_true",
                        help="只打印结果摘要,不写 trends.json(本地验证用)")
    args = parser.parse_args()

    trends = generate_trends(args.repo_dir)
    if trends is None:
        return 1
    print(f"窗口:{trends['days'][0]} ~ {trends['days'][-1]}({trends['windowDays']} 天)")
    for i, kw in enumerate(trends["keywords"], 1):
        arrow = {"up": "↑", "down": "↓", "flat": "→"}[kw["trend"]]
        print(f"  #{i} {kw['display']:<20} 总 {kw['total']:>3} 次 / "
              f"{kw['daysActive']:>2} 天 {arrow}  daily={kw['daily']}")
        for it in kw["items"]:
            print(f"      - [{it['source']}] {it['date']} {it['title'][:50]}")
    if args.dry_run:
        print("(dry-run,未写 trends.json)")
        return 0
    return 0 if write_trends(args.repo_dir) else 1


if __name__ == "__main__":
    sys.exit(main())
