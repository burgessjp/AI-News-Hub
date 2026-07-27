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

设计要点(逐行搬自 OverviewRepository.kt):
  - 输入 8 源快照(SOURCE_KEYS,与 App 端一致),每源取前 ITEMS_PER_SOURCE=8 条;
  - 跨源归一化热度档位(每源按自身 top-8 最大原始热度归一化到 10-100%),
    让 AI 跨源比较的是相对档位而非量级悬殊的原始数字;
  - 拍平各源顶层 ai_summary_v2 为上下文;
  - 数据日期 = 全源快照最大 fetched_at_ms 的北京日期(breaking 时效硬约束用);
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

import requests

# 复用 ai_summary 的配置入口(同一套 AI_NEWS_HUB_AI_* 环境变量 + config_ready)
from ai_summary import ENV_BASE_URL, ENV_MODEL, ENV_API_KEY, config_ready


# ===== 常量(对齐 OverviewRepository.kt companion) =====

# 8 个归档源(对齐 App SummaryRepository.SOURCE_KEYS,顺序即默认展示顺序)
SOURCE_KEYS = (
    "hackernews", "github-trending", "openai-anthropic-news", "huggingface-papers",
    "producthunt", "rundown-ai", "aihot-featured", "stormzhang-ai",
)

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

BEIJING_TZ = timezone(timedelta(hours=8))


# ===== system prompt(逐字搬自 OverviewRepository.kt SYSTEM_PROMPT) =====

SYSTEM_PROMPT = """你是「AI News Hub」今日总览栏目的主编。输入是多个资讯源的今日榜单:每源附 AI 要点摘要,以及排名前若干条目(序号、标题、简介、**跨源归一化热度档位**、原始指标、北京日期 MM-DD)。请基于全部数据做当天整体研判。

严格输出一个 JSON 对象,不要输出任何解释文字,不要使用 markdown 代码围栏:
{"items":[{"ref":"源key:序号","analysis":"一句话,不超过40字","breaking":true,"breakingReason":"为什么是突发,40字内"}]}

规则:
1. items 是今日最值得关注的条目,**严格按热度档位(热度 NN%)从高到低排序**,最多 10 条(数据不足时按实际给,至少 5 条)。热度档位已跨源归一化,**直接按档位数字排序即可,不要主观调整顺序**——档位 92% 的条目必须排在档位 85% 的前面,即使后者主题看起来更重要。
2. **归一化热度档位**是核心排序信号:每条带「热度 NN%」,反映该条在本源的相对强度(同源内档位越高越热)。跨源比较时:
   - 有指标的源(HN/GitHub/HF/PH/AIHot)档位可信,直接按数字比;
   - **无指标源(rundown-ai / stormzhang-ai / openai-anthropic-news)的档位仅按列表序号给**,可信度低于有指标源——同等档位下,有指标源的条目优先于无指标源;
   - 原始指标量级差异极大(HN 几百 / GitHub 几万),**禁止直接比较原始数字**。
3. 其中「突发重磅」标 "breaking":true:多源交叉报道、热度档位 ≥85% 且远超同源其它条目、或重大发布与行业事件。0 到 2 条,宁缺毋滥,绝不硬凑;其余条目一律 "breaking":false。breaking 条目排在 items 最前,同样计入 10 条总数。**时效硬约束**:输入顶部已给出「数据日期(北京)」,仅条目末尾的北京日期等于该值的条目才可标 breaking;过期条目即便热度爆发也一律 false。
4. **跨源同事件合并**:同一事件(如某新模型发布)在多个源出现时,只保留热度档位最高的一条;通过 ref 引用其中之一即可,analysis 里可点出「多家报道」。不要让同一事件占据多个名额。
5. ref 必须原样照抄输入中的「源key:序号」(如 hackernews:2),不得编造;标题与链接由数据侧按 ref 回填,你不要输出标题和 URL。
6. analysis 用简体中文,一句话说清「为什么重要/值得关注什么」,不要复述标题。
7. breaking=true 的条目必须给出 breakingReason,简体中文,一句话说明「为什么是突发/影响面有多大」,≤40 字,不复述 analysis;breaking=false 时 breakingReason 留空字符串。"""


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

    # 第二遍:计算归一化热度(每源最大原始热度 → 10-100%)
    max_raw = max(r[6] for r in raws)
    result = []
    for pos, r in enumerate(raws):
        index, title, url, metrics, blurb, date_key, raw_heat = r
        if max_raw > 0:
            pct = max(10, min(100, int((raw_heat / max_raw) * 100)))
        else:
            # 无指标源:按列表序号线性递减(top1=100, topN≈10)
            pct = 100 if len(raws) == 1 else max(10, min(100, int(100 - pos * 90.0 / (len(raws) - 1))))
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


def _build_section(source, snapshot):
    """单源输入段:标题行 + AI 要点(上下文)+ 编号条目(标题/简介/热度/指标/日期)。"""
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
            line += f" | {date_key[5:]}"  # MM-dd
        sb.append(line)
    return "\n".join(sb)


# ===== AI 调用 + 解析(复刻 ai_summary._request_summary,放宽超时) =====

def _parse_overview_json(content):
    """
    把 AI 返回的文本解析成 {items: [...]};items 每项含 ref/analysis/breaking/breakingReason。
    容错:剥 markdown 围栏,截取首个 { 到末个 }。解析失败抛 RuntimeError(由调用方重试)。
    """
    if not content:
        raise RuntimeError("AI 响应 content 为空")
    # 剥 markdown 围栏
    stripped = re.sub(r"^```(?:json)?\s*", "", content.strip(), flags=re.IGNORECASE).rstrip("`").strip()
    start, end = stripped.find("{"), stripped.rfind("}")
    if start == -1 or end == -1 or end <= start:
        raise RuntimeError(f"AI 响应未找到 JSON 对象:{content[:120]}")
    payload = stripped[start:end + 1]
    try:
        data = json.loads(payload)
    except json.JSONDecodeError as e:
        raise RuntimeError(f"AI 响应 JSON 解析失败:{e}:{content[:120]}")
    if not isinstance(data, dict) or not isinstance(data.get("items"), list) or not data["items"]:
        raise RuntimeError(f"AI 响应非含非空 items 数组的对象:{content[:120]}")
    return data


def _request_overview(system_prompt, user_prompt, base_url, model, api_key):
    """发起一次 OpenAI 兼容 /v1/chat/completions 请求,返回解析后的 {items: [...]}。失败抛异常。"""
    url = f"{base_url.rstrip('/')}/v1/chat/completions"
    body = {
        "model": model,
        "temperature": TEMPERATURE,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
    }
    resp = requests.post(
        url,
        json=body,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        timeout=TIMEOUT,
    )
    resp.raise_for_status()
    data = resp.json()
    choices = data.get("choices") or []
    if not choices:
        raise RuntimeError(f"AI 响应无 choices:{str(data)[:120]}")
    content = (((choices[0].get("message") or {}).get("content")) or "").strip()
    return _parse_overview_json(content)


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


def _parse_result(ai_data, snapshots, today):
    """
    解析 AI 输出为统一的热点列表(完成 ref 回填 + 时效兜底 + 双层去重 + breaking 截断)。
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
        # 时效硬约束:AI 标 breaking 但日期不是今天的,强制降级
        ai_breaking = bool(o.get("breaking"))
        effective_date = date_key if date_key else _beijing_date_key_of_ms(snapshot.get("fetched_at_ms", 0))
        is_breaking = ai_breaking and bool(effective_date) and effective_date == today
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

    # 组 user prompt
    user_prompt = f"数据日期(北京):{data_today}\n\n" + "\n\n".join(
        _build_section(src, snapshots[src]) for src in SOURCE_KEYS if src in snapshots
    )

    base_url = os.getenv(ENV_BASE_URL)
    model = os.getenv(ENV_MODEL)
    api_key = os.getenv(ENV_API_KEY)

    last_err = None
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            ai_data = _request_overview(SYSTEM_PROMPT, user_prompt, base_url, model, api_key)
            items = _parse_result(ai_data, snapshots, data_today)
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
