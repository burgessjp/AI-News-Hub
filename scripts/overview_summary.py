#!/usr/bin/env python3
"""
今日总览跨源综合分析(对齐 App 端原 OverviewRepository.kt 的 generate 全流程)。

抓取脚本(fetch_data.py)每跑完所有源后,调用本模块读取本次落盘的 8 源快照,
拼成跨源 prompt 喂给 OpenAI 兼容服务,生成「今日热点 Top10」(含 breaking 标记),
作为 `latest_overview` 字段写进 index.json 顶层。

`latest_overview` 结构(对齐 App OverviewDigest,去掉端侧专属的 model/totalTokens/fromCache):
  {
    "generatedAt": <ms>,           # 本次生成时刻
    "dataFetchedAt": <ms>,         # 输入快照里最大的 fetched_at_ms
    "missingSources": [<source_key>, ...],  # 本次未能加载的源
    "items": [                              # Top10,breaking 排前
      {
        "source": "hackernews",
        "title": "...", "url": "...", "metrics": "...",
        "comment": "...",           # AI 写的一句话重要性(原 analysis)
        "breaking": false,
        "breakingReason": ""        # 仅 breaking=true 有
      }
    ]
  }

设计要点(初版逐行搬自 OverviewRepository.kt,后经准确性优化):
  - 输入 8 源快照(SOURCE_KEYS,与 App 端一致),每源取前 ITEMS_PER_SOURCE=8 条;
  - 跨源归一化热度档位:有指标源按自身 top-8 最大原始热度归一化到 10-100%,
    让 AI 跨源比较的是相对档位而非量级悬殊的原始数字;
    无指标源(rundown-ai/stormzhang-ai/openai-anthropic-news)无真实指标,按列表序号
    线性给到 10-70%(上限压低,避免位置档位压过有指标源的真实高热度条目);
  - 日期显示:与数据日期同年显示 MM-dd,跨年显示完整 yyyy-MM-dd(防旧文看似新鲜);
    rundown-ai 列表页无文章日期,其日期为抓取兜底,输入中标注「抓取日期」;
  - 数据日期 = 全源快照最大 fetched_at_ms 的北京日期;
    breaking 时效窗口 = 数据日期及其前一天(15:30 北京跑批时,前一日晚间大事仍算突发);
  - 调 OpenAI 兼容 /v1/chat/completions,温度 0.3,read 超时放宽到 120s(输出长);
  - 解析后做 ref 回填 + 时效兜底 + URL/标题双层去重 + breaking 截断到 MAX_BREAKING;
  - 失败返回 None,不阻断推送(对齐单源 AI 摘要失败的优雅降级,
    fetch_data.py 会从 previous_index 继承上次的 latest_overview)。

用法(供 fetch_data.py 内部 import):
  from overview_summary import generate_overview
  overview = generate_overview(out_dir, now_cst())  # 返回 dict 或 None
"""

import json
import math
import os
import re
import sys
import time
from datetime import datetime, timezone, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ai_client
from common import SOURCE_KEYS, BEIJING_TZ, now_cst

# 复用 ai_summary 的配置入口(同一套 AI_NEWS_HUB_AI_* 环境变量 + config_ready)
from ai_summary import ENV_BASE_URL, ENV_MODEL, ENV_API_KEY, config_ready


# ===== 常量(对齐 OverviewRepository.kt companion) =====

# 源 key → 展示标题(对齐 App SummaryRepository.titleOf)
SOURCE_TITLES = {
    "hackernews": "HackerNews",
    "github-trending": "GitHub Trending",
    "openai-anthropic-news": "OpenAI × Anthropic",
    "huggingface-papers": "HuggingFace Papers",
    "producthunt": "Product Hunt",
    "rundown-ai": "The Rundown AI",
    "aihot-featured": "AIHot 精选",
    "stormzhang-ai": "stormzhang AI",
}

ITEMS_PER_SOURCE = 8       # 每源喂 AI 的条目数
MIN_SOURCES = 4            # 低于此源数不生成(数据太少,分析无意义)
MAX_BREAKING = 2           # 「突发重磅」上限(占 Top10 名额)
MAX_TOP = 10               # 热点列表总条数上限
TITLE_DUP_THRESHOLD = 0.5  # 标题 Jaccard 去重阈值

# 对齐 App:connectTimeout 15s, readTimeout 120s(总览输出长,比单源摘要的 30s 放宽)
TIMEOUT = (15, 120)
TEMPERATURE = 0.3
MAX_ATTEMPTS = 3


# ===== system prompt(初版逐字搬自 OverviewRepository.kt;现为提升生成准确性重写) =====

SYSTEM_PROMPT = """你是「AI News Hub」今日总览栏目的主编。输入是多个资讯源的今日榜单:每源附 AI 要点摘要,以及排名前若干条目(序号、标题、简介、热度档位、原始指标、日期)。请基于全部数据做当天整体研判。

严格输出一个 JSON 对象,不要输出任何解释文字,不要使用 markdown 代码围栏:
{"items":[{"ref":"源key:序号","analysis":"一句话,不超过40字","breaking":true,"breakingReason":"为什么是突发,40字内"}]}

一、先读懂「热度档位」:
- 有指标源(hackernews、github-trending、huggingface-papers、producthunt、aihot-featured):档位由真实指标(得分/star/票数/upvotes/权重)归一化而来,可信,直接按数字比较。
- 无指标源(rundown-ai、stormzhang-ai、openai-anthropic-news):无真实热度指标,档位只按列表序号线性给出(上限 70%),仅反映站内排序。跨源比较时,无指标源条目默认排在同档位有指标源条目之后。
- 原始指标量级差异极大(HN 几百、GitHub 几万),禁止直接比较原始数字。

二、选条与排序:
1. items 为今日最值得关注的条目,最多 10 条(数据不足按实际给,至少 5 条)。按热度档位从高到低排序;档位差 ≤2% 视为同档,同档时有指标源在前、日期新鲜的在前。
2. 时效:输入顶部给出「数据日期(北京)」。日期早于数据日期 7 天以上的条目不得入选(档位再高也不行);标注「抓取日期」的条目其日期不代表发布日,不得作为时效依据。
3. 跨源同事件合并:同一事件(如某新模型发布,含其衍生通稿如「上线某平台」「开源某组件」)在多个源出现时只保留一条,取各报道源中的最高档位参与排序;ref 优先选有指标源的条目,同为有指标源取档位最高者。analysis 里可点出「多家报道」。同一事件不得占多个名额。
4. 同一来源(ref 源key)最多 3 条;超出时把名额让给其它源的高档位条目。
5. ref 必须原样照抄输入中的「源key:序号」(如 hackernews:2),不得编造;标题与链接由数据侧按 ref 回填,你不要输出标题和 URL。
6. analysis 用简体中文,≤40 字,回答「所以怎样」——对开发者/行业意味着什么;禁止复述标题事实,可引用输入中的真实数字(如「HN 899 分」)提升信息密度。

三、「突发重磅」("breaking":true),须同时满足:
① 属于重大发布/行业事件(新模型、重大开源、巨头战略动作等);
② 至少 2 个源报道同一事件,且其中 ≥1 个是有指标源;
③ 佐证条目中至少 1 条的真实日期等于数据日期或前一天(「抓取日期」不算)。
0 到 2 条,宁缺毋滥,绝不硬凑;任一条件不满足即 "breaking":false。breaking 条目排在 items 最前,计入 10 条总数。breaking=true 时必须给出 breakingReason:简体中文 ≤40 字,写清具体证据(哪些源报道、什么量级,如「HN 899 分热议 + PH 日榜#4」),禁止「影响面广」「引发热议」等无信息量表述,不复述 analysis;breaking=false 时留空字符串。"""


# ===== 快照读取 =====

def _load_snapshots(out_dir):
    """
    扫描 out_dir 下各源最新快照(本次落盘的),返回 {source: snapshot_dict}。

    每源目录 <out_dir>/<source>/<YYYY-MM-DD>/ 下取字典序最大的 <HH-MM>-data.json
    (对齐 fetch_data.py 的落盘命名:北京时间,文件名按时间排序即最新)。
    缺失/解析失败的源跳过(单源失败不阻断)。
    """
    snapshots = {}
    for source in SOURCE_KEYS:
        src_dir = os.path.join(out_dir, source)
        if not os.path.isdir(src_dir):
            continue
        # 收集所有 <date>/<time>-data.json,按 (date, time) 取最大
        candidates = []
        for date_name in os.listdir(src_dir):
            date_dir = os.path.join(src_dir, date_name)
            if not os.path.isdir(date_dir):
                continue
            for fname in os.listdir(date_dir):
                if fname.endswith("-data.json"):
                    candidates.append((date_name, fname, os.path.join(date_dir, fname)))
        if not candidates:
            continue
        candidates.sort(key=lambda x: (x[0], x[1]))
        try:
            with open(candidates[-1][2], "r", encoding="utf-8") as f:
                snap = json.load(f)
            if isinstance(snap, dict):
                snapshots[source] = snap
        except Exception as e:
            print(f"[OVERVIEW] 读 {source} 快照失败:{type(e).__name__}: {e}", file=sys.stderr)
    return snapshots


# ===== 每源原始热度公式(搬自 OverviewRepository.kt rawHeatXxx) =====

def _raw_heat_hackernews(o):
    """HN 综合热度:得分 + 评论数 * 0.3。"""
    return _as_int(o, "score") + _as_int(o, "descendants") * 0.3


def _raw_heat_github(o):
    """GitHub 综合热度:今日新增 star * 3 + 累计 star 对数权重。"""
    today = _as_int(o, "starsToday")
    total = _as_int(o, "totalStars")
    return today * 3.0 + (math.log10(total) * 10 if total > 0 else 0.0)


def _raw_heat_producthunt(o):
    """Product Hunt 综合热度:票数 + 评论 * 0.5 + 日榜前 5 加成。"""
    votes = _as_int(o, "votesCount")
    comments = _as_int(o, "commentsCount")
    rank = _as_int(o, "dailyRank")
    rank_boost = (6 - rank) * 30.0 if 1 <= rank <= 5 else 0.0
    return votes + comments * 0.5 + rank_boost


def _as_int(o, key, default=0):
    """JSONObject 兼容取 int(字符串数字也接受)。"""
    v = o.get(key, default)
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


# ===== extract_items(搬自 OverviewRepository.kt extractItems) =====

def _extract_items(source, snapshot, limit=ITEMS_PER_SOURCE):
    """
    从快照 items 提取前 [limit] 条,返回 [(index, title, url, metrics, blurb, date_key, heat_pct), ...]。
    热度归一化:每源原始热度按该源 top-[limit] 内的最大值算百分比;无指标源按列表序号线性递减。
    """
    items = snapshot.get("items") or []
    if not isinstance(items, list):
        return []
    n = min(limit, len(items))
    fallback_date_key = _beijing_date_key_of_ms(snapshot.get("fetched_at_ms", 0))

    # 第一遍:抽取原始字段 + 原始热度
    raws = []  # 每项: (index, title, url, metrics, blurb, date_key, raw_heat)
    for i in range(n):
        o = items[i]
        if not isinstance(o, dict):
            continue
        if source == "hackernews":
            view = (i, _s(o, "title"), _s(o, "target_url"),
                    f"得分 {_as_int(o, 'score')} · 评论 {_as_int(o, 'descendants')}", "",
                    _beijing_date_key_of_ms(_as_int(o, "time") * 1000))
            raw_heat = _raw_heat_hackernews(o)
        elif source == "github-trending":
            view = (i, f"{_s(o, 'owner')}/{_s(o, 'name')}", _s(o, "url"),
                    f"今日 star +{_as_int(o, 'starsToday')} · 累计 {_fmt_count(_as_int(o, 'totalStars'))}",
                    _s(o, "description"), fallback_date_key)
            raw_heat = _raw_heat_github(o)
        elif source == "huggingface-papers":
            view = (i, _s(o, "title"), _s(o, "url"),
                    f"upvotes {_as_int(o, 'upvotes')}", _s(o, "summary"),
                    _beijing_date_key_of_en_date(_s(o, "published")))
            raw_heat = float(_as_int(o, "upvotes"))
        elif source == "producthunt":
            metrics = f"票 {_as_int(o, 'votesCount')} · 评论 {_as_int(o, 'commentsCount')}"
            rank = _as_int(o, "dailyRank")
            if rank > 0:
                metrics += f" · 日榜#{rank}"
            view = (i, _s(o, "name"), _s(o, "url"), metrics, _s(o, "tagline"),
                    _beijing_date_key_of_iso(_s(o, "createdAt")))
            raw_heat = _raw_heat_producthunt(o)
        elif source == "rundown-ai":
            view = (i, _s(o, "title"), _s(o, "url"), "", _s(o, "subtitle"), fallback_date_key)
            raw_heat = 0.0  # 无指标源,按序号归一化
        elif source == "stormzhang-ai":
            # "2026-07-15 20:00" 北京时间无时区,直接取前 10 字符(yyyy-MM-dd)
            t = _s(o, "time").strip()
            date_key = t[:10] if len(t) >= 10 else ""
            view = (i, _s(o, "summary"), _s(o, "url"), f"信源 {_s(o, 'source')}",
                    _s(o, "english"), date_key)
            raw_heat = 0.0
        elif source == "aihot-featured":
            view = (i, _s(o, "title"), _s(o, "permalink") or _s(o, "url"),
                    f"权重 {_as_int(o, 'score')} · {_s(o, 'source')}", _s(o, "summary"),
                    _beijing_date_key_of_iso(_s(o, "publishedAt")))
            raw_heat = float(_as_int(o, "score"))
        elif source == "openai-anthropic-news":
            view = (i, _s(o, "title"), _s(o, "url"),
                    f"厂商 {_s(o, 'vendor')} · {_s(o, 'category')}", _s(o, "summary"),
                    _beijing_date_key_of_iso(_s(o, "publishedAt")))
            raw_heat = 0.0
        else:
            continue
        # 标题空的丢弃
        if not view[1].strip():
            continue
        raws.append(view + (raw_heat,))

    if not raws:
        return []

    # 第二遍:计算归一化热度(有指标源:每源最大原始热度 → 10-100%;无指标源:序号 → 10-70%)
    max_raw = max(r[6] for r in raws)
    result = []
    for pos, r in enumerate(raws):
        index, title, url, metrics, blurb, date_key, raw_heat = r
        if max_raw > 0:
            pct = max(10, min(100, int((raw_heat / max_raw) * 100)))
        else:
            # 无指标源:按列表序号线性递减,上限压到 70(top1=70, topN≈10)
            # ——位置档位仅反映站内排序,不允许与有指标源的真实高热度档位同量级
            pct = 70 if len(raws) == 1 else max(10, min(70, int(70 - pos * 60.0 / (len(raws) - 1))))
        result.append((index, title, url, metrics, blurb, date_key, pct))
    return result


def _s(o, key, default=""):
    """安全取字符串,剥白边,None 转空串。"""
    v = o.get(key, default)
    return str(v).strip() if v is not None else default


def _fmt_count(n):
    return f"{n:,}"


# ===== 日期辅助(搬自 OverviewRepository.kt) =====

def _beijing_date_key_of_ms(epoch_ms):
    """Unix 毫秒 → 北京日期(yyyy-MM-dd);0 或负数返回空串。"""
    if not epoch_ms or epoch_ms <= 0:
        return ""
    try:
        return datetime.fromtimestamp(epoch_ms / 1000, tz=BEIJING_TZ).strftime("%Y-%m-%d")
    except Exception:
        return ""


def _beijing_date_key_of_iso(iso):
    """ISO UTC 字符串(如 2026-07-18T07:01:00Z)→ 北京日期;解析失败返回空串。"""
    s = (iso or "").strip()
    if not s:
        return ""
    try:
        # fromisoformat 不认 Z,替换成 +00:00
        dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.astimezone(BEIJING_TZ).strftime("%Y-%m-%d")
    except Exception:
        return ""


def _beijing_date_key_of_en_date(text):
    """英文月份格式日期(如 "Jul 8, 2026")→ 北京日期;解析失败返回空串。"""
    s = (text or "").strip()
    if not s:
        return ""
    for fmt in ("%b %d, %Y", "%B %d, %Y"):
        try:
            dt = datetime.strptime(s, fmt).replace(tzinfo=BEIJING_TZ)
            return dt.strftime("%Y-%m-%d")
        except ValueError:
            continue
    return ""


# ===== prompt 组装(搬自 OverviewRepository.kt buildSection + readAiSummary) =====

def _read_ai_summary(snapshot):
    """读快照顶层 AI 要点,拍平为纯文本(优先 ai_summary_v2,回退 ai_summary)。"""
    v2 = snapshot.get("ai_summary_v2")
    if isinstance(v2, list) and v2:
        parts = []
        for obj in v2:
            if not isinstance(obj, dict):
                continue
            title = (obj.get("title") or "").strip()
            desc = (obj.get("desc") or "").strip()
            if title and desc:
                parts.append(f"{title}：{desc}")
        if parts:
            return "；".join(parts)
    # 回退旧纯文本字段
    legacy = snapshot.get("ai_summary")
    return (str(legacy).strip() if legacy else "")


def _build_section(source, snapshot, data_today=""):
    """
    单源输入段:标题行 + AI 要点(上下文)+ 编号条目(标题/简介/热度/指标/日期)。
    日期显示:与数据日期同年显示 MM-dd;跨年显示完整 yyyy-MM-dd(防上一年旧文看似新鲜);
    rundown-ai 列表页无文章日期,其日期为抓取兜底,标注「抓取日期」。
    """
    sb = [f"## {source}({SOURCE_TITLES.get(source, source)})"]
    ai_summary = _read_ai_summary(snapshot)
    if ai_summary:
        sb.append(f"AI 要点:{ai_summary}")
    sb.append("条目:")
    for (index, title, url, metrics, blurb, date_key, heat_pct) in _extract_items(source, snapshot):
        line = f"[{index}] {title}"
        if blurb:
            line += f" — {blurb[:60]}"
        # 跨源可比的归一化热度档位
        line += f" | 热度 {heat_pct}%"
        if metrics:
            line += f" | {metrics}"
        if len(date_key) >= 10:
            date_text = date_key[5:] if date_key[:4] == data_today[:4] else date_key
            if source == "rundown-ai":
                date_text += "(抓取日期)"
            line += f" | {date_text}"
        sb.append(line)
    return "\n".join(sb)


# ===== AI 调用统一经 ai_client(共享 Session / 429 重试 / 围栏剥离) =====
# 总览的请求与解析原与 ai_summary 各持一份逐字重复的实现,现已收口到
# ai_client.call_llm(expect="object");此处仅保留对返回对象的结构校验。


def _validate_overview_obj(data):
    """ai_client.call_llm 已保证返回非空 dict;这里校验它含非空 items 数组。
    失败抛 RuntimeError(由 generate_overview 的 3 次业务层重试捕获)。"""
    if not isinstance(data.get("items"), list) or not data["items"]:
        raise RuntimeError(f"AI 响应非含非空 items 数组的对象:{str(data)[:120]}")
    return data


# ===== 解析兜底(搬自 OverviewRepository.kt parseResult/parseEntries) =====

def _tokenize_title(title):
    """标题切 token:按非字母数字(含中文字符保留)分段,统一小写,过滤 1 字符噪声。"""
    tokens = re.split(r"[^a-z0-9\u4e00-\u9fa5]+", title.lower())
    return {t for t in tokens if len(t) >= 2}


def _jaccard(a, b):
    """Jaccard 相似度:交集 / 并集。空集返回 0。"""
    if not a or not b:
        return 0.0
    inter = len(a & b)
    return inter / (len(a) + len(b) - inter)


def _title_not_duplicate(title, history):
    """
    标题去重:与历史标题集合逐一算 Jaccard,≥ TITLE_DUP_THRESHOLD 视为重复丢弃;否则记入历史。
    返回 (is_unique, ) —— 调用方据此前进/跳过。
    """
    tokens = _tokenize_title(title)
    if len(tokens) < 3:
        history.append(tokens)
        return True
    for prev in history:
        if _jaccard(tokens, prev) >= TITLE_DUP_THRESHOLD:
            return False
    history.append(tokens)
    return True


def _parse_result(ai_data, snapshots, breaking_dates):
    """
    解析 AI 输出为统一的热点列表(完成 ref 回填 + 时效兜底 + 双层去重 + breaking 截断)。
    breaking_dates: 允许标 breaking 的北京日期集合(数据日期及其前一天——15:30 北京
    跑批时,前一日晚间的大事仍算突发)。
    返回 [{source, title, url, metrics, comment, breaking, breakingReason}, ...]。
    """
    raw_items = ai_data.get("items") or []
    seen_refs = set()
    entries = []
    for o in raw_items:
        if not isinstance(o, dict):
            continue
        ref = (o.get("ref") or "").strip()
        if not ref or ref in seen_refs:
            continue
        seen_refs.add(ref)
        # ref = "源key:序号"
        cut = ref.rfind(":")
        if cut <= 0:
            continue
        source = ref[:cut].strip().lower()
        try:
            index = int(ref[cut + 1:].strip())
        except ValueError:
            continue
        snapshot = snapshots.get(source)
        if not snapshot:
            continue
        items = _extract_items(source, snapshot)
        if index < 0 or index >= len(items):
            continue
        item = items[index]
        _, title, url, metrics, _, date_key, _ = item
        if not url.strip():
            continue
        # 时效硬约束:AI 标 breaking 但日期不在允许窗口(数据日期/前一天)的,强制降级
        ai_breaking = bool(o.get("breaking"))
        effective_date = date_key if date_key else _beijing_date_key_of_ms(snapshot.get("fetched_at_ms", 0))
        is_breaking = ai_breaking and bool(effective_date) and effective_date in breaking_dates
        entries.append({
            "source": source,
            "title": title,
            "url": url,
            "metrics": metrics,
            "comment": (o.get("analysis") or "").strip(),
            "breaking": is_breaking,
            "breakingReason": (o.get("breakingReason") or "").strip() if is_breaking else "",
        })

    if not entries:
        return []

    # breaking 截断到 MAX_BREAKING
    breaking_left = MAX_BREAKING
    urls_seen = set()
    title_history = []
    result = []
    for e in entries:
        if e["breaking"] and breaking_left > 0:
            breaking_left -= 1
        else:
            e["breaking"] = False
            e["breakingReason"] = ""
        # URL 去重
        if e["url"] in urls_seen:
            continue
        urls_seen.add(e["url"])
        # 标题相似度去重
        if not _title_not_duplicate(e["title"], title_history):
            continue
        result.append(e)

    # breaking 排前,整体截断到 MAX_TOP(稳定排序,不打乱同级次序)
    result.sort(key=lambda x: 0 if x["breaking"] else 1)
    return result[:MAX_TOP]


# ===== 入口 =====

def generate_overview(out_dir, now):
    """
    读本次 out_dir 下 8 源快照,生成今日总览。成功返回 dict(写入 index.json latest_overview),
    失败返回 None(调用方从 previous_index 继承上次的 latest_overview)。

    now: datetime(北京时间,带 tzinfo),用于 generatedAt。
    """
    if not config_ready():
        missing = [k for k in (ENV_BASE_URL, ENV_MODEL, ENV_API_KEY) if not os.getenv(k)]
        print(f"[OVERVIEW] 跳过总览生成:缺少环境变量 {missing}", file=sys.stderr)
        return None

    snapshots = _load_snapshots(out_dir)
    if len(snapshots) < MIN_SOURCES:
        print(f"[OVERVIEW] 跳过总览生成:可用源 {len(snapshots)} < {MIN_SOURCES}",
              file=sys.stderr)
        return None

    # 数据日期 = 全源快照最大 fetched_at_ms 的北京日期
    data_date_ms = max((s.get("fetched_at_ms", 0) or 0) for s in snapshots.values())
    data_today = _beijing_date_key_of_ms(data_date_ms)
    # breaking 时效窗口:数据日期及其前一天(15:30 北京跑批时,前一日晚间大事仍算突发)
    data_yesterday = _beijing_date_key_of_ms(data_date_ms - 86400000)
    breaking_dates = {d for d in (data_today, data_yesterday) if d}

    # 组 user prompt
    user_prompt = (
        f"数据日期(北京):{data_today};breaking 时效窗口:{data_yesterday} 或 {data_today}\n\n"
        + "\n\n".join(
            _build_section(src, snapshots[src], data_today) for src in SOURCE_KEYS if src in snapshots
        )
    )

    base_url = os.getenv(ENV_BASE_URL)
    model = os.getenv(ENV_MODEL)
    api_key = os.getenv(ENV_API_KEY)

    last_err = None
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            ai_data = ai_client.call_llm(
                SYSTEM_PROMPT, user_prompt, base_url, model, api_key,
                timeout=TIMEOUT, temperature=TEMPERATURE, expect="object",
            )
            _validate_overview_obj(ai_data)
            items = _parse_result(ai_data, snapshots, breaking_dates)
            if not items:
                raise RuntimeError("解析后无有效条目")
            overview = {
                "generatedAt": int(now.timestamp() * 1000),
                "dataFetchedAt": data_date_ms,
                "missingSources": [k for k in SOURCE_KEYS if k not in snapshots],
                "items": items,
            }
            print(f"[OVERVIEW] 生成成功:{len(items)} 条(第 {attempt} 次成功),"
                  f"breaking {sum(1 for i in items if i['breaking'])} 条")
            return overview
        except Exception as e:
            last_err = e
            print(f"[OVERVIEW] 第 {attempt}/{MAX_ATTEMPTS} 次失败:"
                  f"{type(e).__name__}: {e}", file=sys.stderr)
            if attempt < MAX_ATTEMPTS:
                time.sleep(2 ** attempt)  # 2s, 4s

    print(f"[OVERVIEW] {MAX_ATTEMPTS} 次全败,跳过:{last_err}", file=sys.stderr)
    return None
