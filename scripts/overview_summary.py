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
    "digest": "...",               # 3-4 句跨源今日综述(可能缺省,App 端空串不渲染)
    "items": [                              # Top10,breaking 排前、增量批次新鲜在前
      {
        "source": "hackernews",
        "title": "...", "url": "...", "metrics": "...",
        "comment": "...",           # AI 写的一句话重要性(原 analysis)
        "breaking": false,
        "breakingReason": ""        # 仅 breaking=true 有
      }
    ]
  }

设计要点(初版逐行搬自 OverviewRepository.kt,后经准确性 + 编辑质量两轮优化):
  - 输入 8 源快照(SOURCE_KEYS,与 App 端一致),每源取前 ITEMS_PER_SOURCE=8 条;
    AI 候选上限 14 条(>10,给数据侧同事件去重留余量);
  - 跨源归一化热度档位:有指标源按自身 top-8 最大原始热度归一化到 10-100%,
    让 AI 跨源比较的是相对档位而非量级悬殊的原始数字;
    无指标源(rundown-ai/stormzhang-ai/openai-anthropic-news)无真实指标,按列表序号
    线性给到 10-70%(上限压低,避免位置档位压过有指标源的真实高热度条目);
  - 日期显示:与数据日期同年显示 MM-dd,跨年显示完整 yyyy-MM-dd(防旧文看似新鲜);
    rundown-ai 列表页无文章日期,其日期为抓取兜底,输入中标注「抓取日期」;
  - 数据日期 = 全源快照最大 fetched_at_ms 的北京日期;
    breaking 时效窗口 = 数据日期及其前一天(每日批次制,晚间跑批时前一日大事对未及阅读的用户仍是突发);
  - 调 OpenAI 兼容 /v1/chat/completions,温度 0.3,read 超时放宽到 120s(输出长);
  - 解析后做 ref 回填 + 时效兜底 + URL/标题双层去重 + breaking 截断到 MAX_BREAKING;
  - 标题去重三规则(2026-09 收紧,拦跨语言同事件重复——aihot 精选常是 HN/GitHub
    热点的中文转述,中文标题与英文原标题的 Jaccard 天然够不着 0.5,曾致 Shopify
    迁移同事件双条目上榜):① Jaccard ≥0.5 原路径;② 近似包含(短 token 集被长集
    包含/只多 1 个 token,且交集含非泛词锚点——专拦「GLM-5.3」「GPT-6 Astra」式
    裸产品名标题,泛词锚点表防止「两条都提到 OpenAI 的不同新闻」误杀);③ 重合度
    (交集 ≥3 且 Jaccard ≥0.25)。原「token<3 豁免」已移除——豁免曾让裸产品名标题
    完全绕过判重;
  - 去重后不足 MAX_TOP 时从输入池按归一化热度降序回填(单源 ≤3、7 天时效硬闸、
    同套 URL/标题去重),保证常规批次恒输出 10 条;回填条目无 AI 点评(comment 留
    空,App 端空串不渲染)、恒非 breaking;
  - 增量批次(2026-09 编辑质量轮):fetch_data.py 把上一期 latest_overview 传入,
    上一期 digest + Top10 注入 prompt(上一期数据日期与本批同日 → 晚报身份,否则
    早报),AI 被要求「已报道事件无新进展不再选、有进展换角度写」;数据侧对与上一期
    同事件(URL 精确命中或标题三规则判重,复用跨源闸)的条目软降位——稳定排序排到
    全部新鲜条目之后、不参与回填,breaking 条目豁免(重大事件后续进展仍可居首);
  - breaking 硬校验(同轮):AI 输出 item 增加可选 supportRefs(同事件佐证 ref 列表,
    仅数据侧核验用、不落盘),佐证源(含主 ref)去重后 ≥2 源且 ≥1 个有指标源才放行
    breaking,不满足强制降级——prompt 规则从「靠 AI 自觉」变数据侧硬闸;
    breakingReason 加黑话闸:命中输入字段内部名(权重/档位/日期/score…)整条降级
    (历史实锤「aihot-featured权重80,日期09-01」曾直接上屏——App 里 breaking 条目的
    描述位就是它),宁缺毋滥;
  - 点评-条目对应核验(同轮):AI 须照抄所选条目标题开头(titleEcho),数据侧精确
    比对——单次调用让模型同时选条+写作,偶发把 A 事件的点评写到 B 的 ref
    (2026-09-01 回放实锤:Cursor 条目配了芯片点评);错绑保留条目(标题/链接
    来自真实 ref)但丢弃点评、强制非 breaking,echo 缺失从宽(防整体漏字段误杀);
    批次日志输出覆盖率 + 错绑数(覆盖塌了说明模型没配合,回放排查);
  - digest 风格收紧(同轮):3-4 句 ≤180 字,首句直入当天最重要的事,禁盘点式开头
    与口号式收尾(prompt 内置正反 few-shot);`digest_style_warnings` 供
    replay_overview.py 回放评读用(告警非硬闸);
  - 失败返回 None,不阻断推送(对齐单源 AI 摘要失败的优雅降级,
    fetch_data.py 会从 previous_index 继承上次的 latest_overview)。

用法(供 fetch_data.py 内部 import):
  from overview_summary import generate_overview
  overview = generate_overview(out_dir, now_cst())                    # 首期/无上期
  overview = generate_overview(out_dir, now_cst(), previous_overview) # 增量批次
"""

import json
import math
import os
import re
import sys
import time
from collections import Counter
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
TITLE_DUP_THRESHOLD = 0.5  # 标题 Jaccard 去重阈值(纯英文同事件高重合兜底)
OVERLAP_MIN_SHARED = 3     # 重合度规则:标题 token 交集下限
OVERLAP_MIN_JACCARD = 0.25 # 重合度规则:Jaccard 下限(跨语言同事件实测 0.32~0.5)
FRESH_WINDOW_DAYS = 7      # 回填时效硬闸,与 prompt 选条规则同口径

# 有指标源(热度档位来自真实指标;breaking 硬校验要求佐证含至少一个)。
# 与 SYSTEM_PROMPT「热度档位」一节的有指标源清单保持同口径。
METRIC_SOURCES = {
    "hackernews", "github-trending", "huggingface-papers", "producthunt", "aihot-featured",
}

# breakingReason 内部黑话闸(不区分大小写):命中任一 → 该条降级非 breaking。
# 历史实锤:AI 曾写出「aihot-featured权重80,日期09-01」直接上屏(App 里 breaking
# 条目的描述位就是 breakingReason)。佐证只用读者可懂的量级,不引用输入字段名。
BREAKING_JARGON = ("权重", "档位", "日期", "序号", "score", "ref")

# digest 模板腔告警词(replay_overview.py 评读用,非硬闸——prompt 已禁,此处兜测)
DIGEST_STYLE_TABOO = ("今日主线集中在", "今日AI领域", "热点集中在", "开发者应关注")

# 英文虚词(2~4 字符):判重前丢弃——「Kimi-K3 on HuggingFace」的 on 会多算一个
# token 差;the/for 这类 3 字符虚词还会虚增重合度规则的交集数(Flint vs Kronos
# 论文曾靠 for/the/language 凑满 3 个交集被误杀)。
EN_STOP = {
    "on", "of", "to", "in", "is", "it", "at", "by", "as", "us",
    "we", "or", "an", "be", "do", "if", "no", "so", "up", "my", "vs",
    "the", "and", "for", "with", "from", "that", "this", "are", "was",
    "has", "have", "had", "will", "can", "its", "all", "how", "why",
    "what", "when", "who", "now", "not", "but", "out", "get", "you", "your",
}

# 泛词锚点表:公司/产品线/生态名 + 高频通用词。近似包含规则要求交集至少含一个
# 非泛词,防止「两条都提到同一公司的不同新闻」被误判同事件。产品线/协议名
# (code/cursor/mcp/iphone/voice…)是池级压测实锤的弱锚点——同一产品线的不同
# 新闻(Claude Code「Auto 模式默认化」vs「会话间互发消息」)会共享它们;
# 公司名(cloudflare)同理。新厂商/新产品线上线时滚动补,补词前先核对不会
# 误杀在榜好词——同 trend_keywords.py 补停用词的模式。
GENERIC_ANCHORS = {
    "ai", "openai", "claude", "anthropic", "google", "deepmind", "gemini",
    "github", "microsoft", "meta", "nvidia", "apple", "amazon", "aws",
    "cloudflare", "hugging", "face", "agent", "agents", "model", "models",
    "llm", "api", "app", "apps", "new", "open", "source", "release",
    "released", "launch", "launches", "announces", "pro", "skills", "use",
    "files", "computer", "preview",
    "code", "cursor", "mcp", "iphone", "mac", "editor", "voice", "chrome",
    "chatgpt", "gpt", "codex", "labs", "gpu", "video", "companies",
    "world", "expert",
    # 型号后缀与参数规模:flash/max/ultra/mini/exp/next/vision、3b/70b 等——
    # 不同厂商共用(GLM-Flash vs Qwen-Flash、Tines 3B vs Ling-tiny 均曾误杀);
    # 真同产品的裸名对(「Gemini 3.7 Flash」⊂ 中文报道)走纯包含,不依赖锚点
    "flash", "max", "ultra", "mini", "exp", "next", "vision", "3d",
    "1b", "3b", "7b", "13b", "30b", "35b", "70b", "80b",
    "宣布", "推出", "发布", "开源", "报告", "实测", "体验", "上手",
}

# 对齐 App:connectTimeout 15s, readTimeout 120s(总览输出长,比单源摘要的 30s 放宽)
TIMEOUT = (15, 120)
TEMPERATURE = 0.3
MAX_ATTEMPTS = 3


# ===== system prompt =====

SYSTEM_PROMPT = """你是「AI News Hub」日刊的主编。输入是多个资讯源的今日榜单:每源附 AI 要点摘要,以及排名前若干条目(序号、标题、简介、热度档位、原始指标、日期);若为增量批次,输入顶部还会附「上一期总览」。请基于全部数据做当天整体研判。

严格输出一个 JSON 对象,不要输出任何解释文字,不要使用 markdown 代码围栏:
{"digest":"今日综述,见第〇节","items":[{"ref":"源key:序号","titleEcho":"该条目标题的前10个字符原文","supportRefs":["源key:序号"],"analysis":"一句话,不超过40字","breaking":true,"breakingReason":"为什么重要,40字内"}]}

〇、先写「今日综述」(digest):
- 3 到 4 句简体中文,总计不超过 180 字;
- 第一句直接进入当天最重要的一件事,把它说透;其余句子交代其余主线与整体格局;
- 写判断不写清单:跨源归纳「所以怎样」,不复述单条标题、不罗列全部事件;
- 禁止套固定框架:不得以「今日主线集中在」「今日AI领域……」之类盘点式开头,不得以「开发者应关注……」之类口号式收尾;
- 反例(禁止):「今日主线集中在模型开源与生态变动,多家厂商相继发布新模型。开发者应关注开源模型能力跃升带来的工具链重构。」
- 正例(风格参照):「OpenAI 终止向 Cursor 供模型的余波今天继续扩大,模型转售生意的脆弱性暴露无遗,多家工具厂商被迫转向自研接入层。另一条主线是国产开源模型集中放量:腾讯 Hy4 与智谱 GLM-5.3 同日开源,中档算力可跑的多模态第一次有了真选择。」
- 没有明显主线时,如实概述当天热点的分布,禁止硬凑主题。

一、增量纪律(输入含「上一期总览」时生效,首期忽略本节):
- 上一期已报道的事件,没有新进展(无后续事实、指标无跳升)不再选入 items;
- 仍值得报道的,必须换进展角度:analysis 写「这次新发生了什么」,不重复上期已说过的旧事实;
- digest 不得复述上期综述的原句与框架;输入标注「晚报」时优先呈现上一期之后的增量,标注「早报」时可承接昨夜至今晨的动态。

二、先读懂「热度档位」:
- 有指标源(hackernews、github-trending、huggingface-papers、producthunt、aihot-featured):档位由真实指标(得分/star/票数/upvotes/权重)归一化而来,可信,直接按数字比较。
- 无指标源(rundown-ai、stormzhang-ai、openai-anthropic-news):无真实热度指标,档位只按列表序号线性给出(上限 70%),仅反映站内排序。跨源比较时,无指标源条目默认排在同档位有指标源条目之后。
- 原始指标量级差异极大(HN 几百、GitHub 几万),禁止直接比较原始数字。

三、选条与排序:
1. items 为今日最值得关注的条目,最多 14 条(数据不足按实际给,至少 5 条;数据侧会对跨源同事件去重后截取前 10)。按热度档位从高到低排序;档位差 ≤2% 视为同档,同档时有指标源在前、日期新鲜的在前。
2. 时效:输入顶部给出「数据日期(北京)」。日期早于数据日期 7 天以上的条目不得入选(档位再高也不行);标注「抓取日期」的条目其日期不代表发布日,不得作为时效依据。
3. 跨源同事件合并:同一事件(如某新模型发布,含其衍生通稿如「上线某平台」「开源某组件」)在多个源出现时只保留一条,取各报道源中的最高档位参与排序;ref 优先选有指标源的条目,同为有指标源取档位最高者。analysis 里可点出「多家报道」。同一事件不得占多个名额。
4. 同一来源(ref 源key)最多 3 条;超出时把名额让给其它源的高档位条目。
5. ref 与 supportRefs 必须原样照抄输入中真实存在的「源key:序号」(如 hackernews:2),不得编造;标题与链接由数据侧按 ref 回填,你不要输出标题和 URL。titleEcho 原样照抄该 ref 条目标题的前 10 个字符(不足 10 个抄完整标题,保留原语言、大小写、标点与空格)——数据侧据此核验点评与条目的对应关系,对不上该条点评会被丢弃。
6. analysis 用简体中文,≤40 字,回答「所以怎样」——对开发者/行业意味着什么;禁止复述标题事实;必须含至少一个具体事实锚点(真实数字、版本号或厂商动作),禁止「推动X发展」「引发热议」「值得持续关注」等空泛收尾。

四、「突发重磅」("breaking":true),须同时满足:
① 属于重大发布/行业事件(新模型、重大开源、巨头战略动作等);
② 至少 2 个源报道同一事件,且其中 ≥1 个是有指标源——除主 ref 外,把同事件的其它佐证条目 ref 填进 supportRefs(数据侧会逐一核验,佐证不足或编造会被降级为普通条目);
③ 佐证条目中至少 1 条的真实日期等于数据日期或前一天(「抓取日期」不算)。
0 到 2 条,宁缺毋滥,绝不硬凑;任一条件不满足即 "breaking":false。breaking 条目排在 items 最前,计入条目总数。breaking=true 时必须给出 breakingReason:简体中文 ≤40 字,写给读者看的「为什么重要」;引用佐证只用读者可懂的量级(如「HN 899 分热议」「GitHub 单日 +3.9k star」「PH 日榜#1」),禁止出现输入字段的内部名称(权重、档位、热度、日期编号、score、ref、序号);禁止「影响面广」「引发热议」等无信息量表述,不复述 analysis。breaking=false 时 supportRefs 可省略、breakingReason 留空字符串。"""


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
            # 2026-08 站点改版后快照带真实 publishedAt(北京时间 yyyy-MM-dd HH:mm);
            # 旧快照无此字段,回退抓取日期(_build_section 里标注「抓取日期」)
            pub = _s(o, "publishedAt")
            date_key = pub[:10] if len(pub) >= 10 else fallback_date_key
            view = (i, _s(o, "title"), _s(o, "url"), "", _s(o, "subtitle"), date_key)
            raw_heat = 0.0  # 无指标源,按序号归一化
        elif source == "stormzhang-ai":
            # "2026-07-15 20:00" 北京时间无时区,直接取前 10 字符(yyyy-MM-dd)
            t = _s(o, "time")
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
    rundown-ai 条目优先用真实 publishedAt;旧快照无此字段的用抓取兜底,标注「抓取日期」。
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
            # rundown-ai 旧快照无 publishedAt,日期为抓取兜底,标注「抓取日期」;
            # 带真实 publishedAt 的条目正常参与时效判断(同日发布撞抓取日的极少数
            # 条目会降级为保守处理,可接受)
            if source == "rundown-ai" and date_key == _beijing_date_key_of_ms(
                    snapshot.get("fetched_at_ms", 0)):
                date_text += "(抓取日期)"
            line += f" | {date_text}"
        sb.append(line)
    return "\n".join(sb)


def _build_previous_section(previous_overview, data_today):
    """
    上一期注入段(增量批次的研判基线):早/晚报身份 + 上一期综述 + 上一期 Top10。

    身份判定:上一期数据日期与本批数据日期同日 → 本批为当日第二期(晚报,重点写
    增量);否则为当日第一期(早报,可承接昨夜今晨)。digest 与 items 均缺时返回
    空串(上一期残缺,不值得注入,当首期处理)。
    """
    if not isinstance(previous_overview, dict):
        return ""
    digest = (previous_overview.get("digest") or "").strip()
    prev_items = [it for it in (previous_overview.get("items") or [])
                  if isinstance(it, dict) and (it.get("title") or "").strip()]
    if not digest and not prev_items:
        return ""
    prev_date = _beijing_date_key_of_ms(previous_overview.get("dataFetchedAt", 0) or 0)
    if prev_date and prev_date == data_today:
        edition = "本批是当日第二期(晚报):重点呈现上一期之后的新进展与增量"
    else:
        edition = "本批是当日第一期(早报):可承接昨夜至今晨的动态,不必重复昨日已报"
    lines = [
        "# 上一期总览(增量研判基线,不是本期素材,禁止照抄)",
        f"- {edition};上一期数据日期:{prev_date or '未知'}",
    ]
    if digest:
        lines.append(f"- 上一期综述:{digest}")
    if prev_items:
        lines.append("- 上一期 Top10(已报道;无新进展不再选入 items):")
        for i, it in enumerate(prev_items, 1):
            lines.append(f"  {i}. [{(it.get('source') or '').strip()}] {(it.get('title') or '').strip()}")
    return "\n".join(lines)


def digest_style_warnings(digest):
    """digest 模板腔命中清单(replay_overview.py 回放评读用;告警非硬闸,
    正常情况下 prompt 的禁令 + few-shot 已拦住,这里兜测回归)。"""
    text = digest or ""
    return [taboo for taboo in DIGEST_STYLE_TABOO if taboo in text]


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
    """
    标题切 token:按非字母数字(含中文)分段、统一小写、滤 1 字符噪声与 EN_STOP 虚词;
    含中文的段再按中英边界二次切分——否则「Pro与Grok」黏成一个 token,containment
    判重时 pro 永远匹配不上(DeepSeek V4 Pro 跨语言重复曾因此漏网)。
    """
    tokens = set()
    for seg in re.split(r"[^a-z0-9一-龥]+", title.lower()):
        if not seg:
            continue
        if re.fullmatch(r"[a-z0-9]+", seg):
            if len(seg) >= 2 and seg not in EN_STOP:
                tokens.add(seg)
        else:
            for sub in re.split(
                    r"(?<=[一-龥])(?=[a-z0-9])|(?<=[a-z0-9])(?=[一-龥])", seg):
                if len(sub) >= 2 and sub not in EN_STOP:
                    tokens.add(sub)
    return tokens


def _jaccard(a, b):
    """Jaccard 相似度:交集 / 并集。空集返回 0。"""
    if not a or not b:
        return 0.0
    inter = len(a & b)
    return inter / (len(a) + len(b) - inter)


def _near_containment(ta, tb):
    """
    近似包含判重(仅跨源调用):较小 token 集被较大集完全包含;或只多出 ≤1 个
    token,且交集含至少一个非泛词锚点(锚点必须是专名,不能只是公司名/泛词)。
    专拦跨语言同事件的「裸产品名」形态:如「GLM-5.3」vs「GLM-5.3 发布:…」、
    「GPT-6 Astra」vs「OpenAI 发布 GPT-6 Astra:…」。
    """
    small, big = (ta, tb) if len(ta) <= len(tb) else (tb, ta)
    if not small:
        return False
    inter = small & big
    if not inter:
        return False
    if small <= big:
        return True
    return len(small - big) <= 1 and any(t not in GENERIC_ANCHORS for t in inter)


def _dup_across_sources(tokens, source, prev_source, prev_tokens):
    """
    跨源三规则判重(① Jaccard ② 近似包含 ③ 重合度);同源对恒不判重。
    批内去重(_title_not_duplicate)与跨期 carryover 判定(_is_carryover)共用,
    语义闸只此一份。
    """
    if source == prev_source or not tokens or not prev_tokens:
        return False
    jac = _jaccard(tokens, prev_tokens)
    shared = len(tokens & prev_tokens)
    return (jac >= TITLE_DUP_THRESHOLD
            or (shared >= OVERLAP_MIN_SHARED and jac >= OVERLAP_MIN_JACCARD)
            or _near_containment(tokens, prev_tokens))


def _title_not_duplicate(title, history, source=""):
    """
    标题去重(三规则,对 history 逐一比对,命中任一即丢弃;均仅跨源生效):
    ① Jaccard ≥ TITLE_DUP_THRESHOLD(纯英文同事件高重合);
    ② 近似包含(裸产品名短标题形态,见 _near_containment);
    ③ 重合度:交集 ≥ OVERLAP_MIN_SHARED 且 Jaccard ≥ OVERLAP_MIN_JACCARD
      (跨语言长标题形态,如 Shopify 迁移中英报道)。
    三规则限跨源:同源内已有 URL 精确去重兜底,而语义规则在同源内误杀率高且
    结构性存在——GitHub 同 owner 不同仓(owner 共享,Jaccard 可达 0.67、近似
    包含只差 1 token)必被误杀;回填把整池条目送进判重后此闸必须存在。15 对
    历史实锤重复全部是跨源对,闸无漏拦。
    不重复则记入 history。历史上的「token<3 豁免」已移除——裸产品名标题恰恰是
    重复高发形态,豁免等于放行(「Hy4 preview」Jaccard=1.0 曾原样双条目上榜)。
    history 条目为 (source, tokens),source 用于跨源闸判定。
    """
    tokens = _tokenize_title(title)
    if not tokens:
        history.append((source, tokens))
        return True
    for prev_source, prev in history:
        if _dup_across_sources(tokens, source, prev_source, prev):
            return False
    history.append((source, tokens))
    return True


def _carryover_state(previous_items):
    """
    上一期 Top10 → carryover 判定状态:(URL 集合, (source, tokens) 列表)。
    previous_items 为 None/空时返回空状态(等同首期,不触发任何降位)。
    """
    urls = set()
    titles = []
    for it in previous_items or []:
        if not isinstance(it, dict):
            continue
        u = (it.get("url") or "").strip()
        if u:
            urls.add(u)
        t = _tokenize_title(it.get("title") or "")
        if t:
            titles.append(((it.get("source") or "").strip(), t))
    return urls, titles


def _is_carryover(url, title, source, prev_state):
    """是否与上一期 Top10 同事件:URL 精确命中,或标题跨源三规则判重命中。"""
    prev_urls, prev_titles = prev_state
    if url and url in prev_urls:
        return True
    tokens = _tokenize_title(title)
    return any(_dup_across_sources(tokens, source, ps, pt) for ps, pt in prev_titles)


def _valid_support_sources(o, snapshots, extracted):
    """
    解析 AI 输出的 supportRefs(同事件佐证 ref 列表),逐个按主 ref 同口径核验
    (格式 / 源存在 / 序号未越界),返回通过核验的源 key 集合(编造的 ref 静默丢弃,
    由佐证不足引发的降级兜住)。仅 breaking 硬校验用,不落盘进 latest_overview。
    """
    refs = o.get("supportRefs")
    if not isinstance(refs, list):
        return set()
    out = set()
    for r in refs:
        ref = str(r or "").strip()
        cut = ref.rfind(":")
        if cut <= 0:
            continue
        src = ref[:cut].strip().lower()
        try:
            index = int(ref[cut + 1:].strip())
        except ValueError:
            continue
        items = extracted.get(src) or []
        if src in snapshots and 0 <= index < len(items):
            out.add(src)
    return out


def _breaking_reason_ok(reason):
    """breakingReason 黑话闸:非空且不命中任何输入字段内部名(不区分大小写)。"""
    if not reason:
        return False
    lowered = reason.lower()
    return not any(j in lowered for j in BREAKING_JARGON)


def _backfill_items(result, extracted, used_positions, urls_seen, title_history, data_today,
                    prev_state=(set(), [])):
    """
    从输入池按归一化热度降序回填,把热点列表补足到 MAX_TOP。
    触发条件:AI 候选经去重后不足 10 条(AI 候选上限 14 见 SYSTEM_PROMPT,双保险
    保证常规批次恒 10 条)。约束对齐 prompt 选条规则:单源 ≤3 条、7 天时效硬闸、
    URL/标题去重与 AI 条目共用同一套登记、与上一期同事件(carryover)不回填
    (增量批次的回填名额留给新鲜条目)。回填条目 comment 留空(App 端空串不
    渲染)、恒非 breaking。
    """
    if not data_today:
        return []
    try:
        floor_date = (datetime.strptime(data_today, "%Y-%m-%d")
                      - timedelta(days=FRESH_WINDOW_DAYS)).strftime("%Y-%m-%d")
    except ValueError:
        return []
    source_counts = Counter(e["source"] for e in result)
    pool = []
    for src, items in extracted.items():
        for item in items:
            if (src, item[0]) in used_positions or not item[2].strip():
                continue
            pool.append(item + (SOURCE_KEYS.index(src), src))
    pool.sort(key=lambda x: (-x[6], x[7], x[0]))

    added = []
    for index, title, url, metrics, _blurb, date_key, _pct, _order, src in pool:
        if len(result) + len(added) >= MAX_TOP:
            break
        if source_counts[src] >= 3:
            continue
        # 时效硬闸:日期早于数据日期 7 天以上的不入池(与 prompt 选条规则同口径)
        if date_key and date_key < floor_date:
            continue
        if url in urls_seen or not _title_not_duplicate(title, title_history, src):
            continue
        # 增量闸:上一期已报道的同事件条目不占回填名额
        if _is_carryover(url, title, src, prev_state):
            continue
        urls_seen.add(url)
        source_counts[src] += 1
        added.append({
            "source": src,
            "title": title,
            "url": url,
            "metrics": metrics,
            "comment": "",
            "breaking": False,
            "breakingReason": "",
        })
    return added


def _parse_result(ai_data, snapshots, breaking_dates, data_today="", previous_items=None):
    """
    解析 AI 输出为统一的热点列表:ref 回填 + 时效兜底 + 双层去重 + breaking 硬校验
    (佐证 ≥2 源且 ≥1 有指标源、reason 黑话闸)+ 截断到 MAX_BREAKING + 不足 MAX_TOP
    时从输入池按热度回填 + 增量批次 carryover 软降位。
    breaking_dates: 允许标 breaking 的北京日期集合(数据日期及其前一天——每日
    批次制下,前一日的大事对多数用户仍是新闻)。
    data_today: 数据日期(北京 yyyy-MM-dd),回填时效过滤的基准。
    previous_items: 上一期 latest_overview 的 items(增量批次注入;None 为首期)。
    返回 [{source, title, url, metrics, comment, breaking, breakingReason}, ...]。
    """
    # 每源条目只抽取一次:ref 回填与回填候选共用同一份(含归一化热度档位)
    extracted = {src: _extract_items(src, snapshots[src]) for src in snapshots}
    prev_state = _carryover_state(previous_items)

    raw_items = ai_data.get("items") or []
    echo_drop_count = 0  # titleEcho 错绑(echo 与实际标题对不上)丢弃点评的条数
    echo_present = 0     # 输出里带 titleEcho 的条数(覆盖率高说明模型在配合核验)
    seen_refs = set()
    used_positions = set()  # AI 已点名的 (source, index):含被判重丢弃者,回填不再取
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
        items = extracted.get(source) or []
        if index < 0 or index >= len(items):
            continue
        item = items[index]
        _, title, url, metrics, _, date_key, _ = item
        if not url.strip():
            continue
        used_positions.add((source, index))
        # 点评-条目对应核验:AI 须照抄所选条目标题开头(titleEcho);对不上说明
        # 它把别的条目当成了这个 ref(长列表上偶发错绑,2026-09 回放实锤:Cursor
        # 条目配了芯片点评)。错绑时保留条目(标题/链接来自真实 ref)但丢弃点评、
        # 强制非 breaking;echo 缺失从宽(等同旧版,防模型整体漏字段时全量误杀)。
        echo = str(o.get("titleEcho") or "").strip()
        if echo:
            echo_present += 1
        echo_mismatch = bool(echo) and not title.startswith(echo)
        if echo_mismatch:
            echo_drop_count += 1
        # breaking 三重硬闸:① 时效(数据日期/前一天);② 佐证(supportRefs 含主
        # ref 去重后 ≥2 源且 ≥1 有指标源——prompt 规则原只靠 AI 自觉,现数据侧
        # 核验);③ reason 黑话(内部字段名不得上屏)。任一不满足强制降级。
        ai_breaking = bool(o.get("breaking"))
        effective_date = date_key if date_key else _beijing_date_key_of_ms(snapshot.get("fetched_at_ms", 0))
        support_sources = {source} | _valid_support_sources(o, snapshots, extracted)
        reason_raw = (o.get("breakingReason") or "").strip()
        is_breaking = (ai_breaking
                       and bool(effective_date) and effective_date in breaking_dates
                       and len(support_sources) >= 2 and bool(support_sources & METRIC_SOURCES)
                       and _breaking_reason_ok(reason_raw)
                       and not echo_mismatch)
        entries.append({
            "source": source,
            "title": title,
            "url": url,
            "metrics": metrics,
            "comment": "" if echo_mismatch else (o.get("analysis") or "").strip(),
            "breaking": is_breaking,
            "breakingReason": reason_raw if is_breaking else "",
        })

    if not entries:
        return []

    # 点评核验观测:覆盖率(模型配合度)+ 错绑丢弃数;echo 大面积缺失说明模型
    # 没配合,核验形同虚设,须回放排查 prompt 遵循度
    print(f"[OVERVIEW] 点评核验:titleEcho 覆盖 {echo_present}/{len(entries)},"
          f"错绑丢弃 {echo_drop_count}", file=sys.stderr)

    urls_seen = set()
    title_history = []

    def _admit(entry):
        """URL 精确去重 + 标题三规则去重,通过则登记并返回 True。"""
        if entry["url"] in urls_seen:
            return False
        if not _title_not_duplicate(entry["title"], title_history, entry["source"]):
            return False
        urls_seen.add(entry["url"])
        return True

    # breaking 截断到 MAX_BREAKING
    breaking_left = MAX_BREAKING
    result = []
    for e in entries:
        if e["breaking"] and breaking_left > 0:
            breaking_left -= 1
        else:
            e["breaking"] = False
            e["breakingReason"] = ""
        if _admit(e):
            result.append(e)

    # 去重后不足 MAX_TOP:从输入池按热度回填(先 append 到 AI 条目之后;随后统一
    # 排序里新鲜回填条目仍排在 carryover 的 AI 条目之前——新鲜优先于承接)
    if len(result) < MAX_TOP:
        result.extend(_backfill_items(
            result, extracted, used_positions, urls_seen, title_history, data_today,
            prev_state=prev_state))

    # 排序:breaking 居首;增量批次与上一期同事件(carryover)的普通条目软降位到
    # 全部新鲜条目之后(稳定排序,两组内部保持 AI 次序);breaking 豁免——重大事件
    # 的后续进展仍可居首。整体截断到 MAX_TOP。
    result.sort(key=lambda e: (
        0 if e["breaking"] else 1,
        0 if e["breaking"] or not _is_carryover(e["url"], e["title"], e["source"], prev_state) else 1,
    ))
    return result[:MAX_TOP]


# ===== 入口 =====

def generate_overview(out_dir, now, previous_overview=None):
    """
    读本次 out_dir 下 8 源快照,生成今日总览。成功返回 dict(写入 index.json latest_overview),
    失败返回 None(调用方从 previous_index 继承上次的 latest_overview)。

    now: datetime(北京时间,带 tzinfo),用于 generatedAt。
    previous_overview: 上一期 latest_overview dict(增量批次注入:上一期 digest +
    Top10 进 prompt 做增量研判,数据侧对同事件条目软降位);None = 首期/上期缺失,
    行为与旧版一致。残缺(无 digest 且无 items)时自动当首期处理。
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
    # breaking 时效窗口:数据日期及其前一天(每日批次制,前一日大事对未及阅读的用户仍是突发)
    data_yesterday = _beijing_date_key_of_ms(data_date_ms - 86400000)
    breaking_dates = {d for d in (data_today, data_yesterday) if d}

    # 组 user prompt:增量批次先注入「上一期总览」段(残缺上期返回空串,当首期)
    previous_section = _build_previous_section(previous_overview, data_today)
    user_prompt = (
        f"数据日期(北京):{data_today};breaking 时效窗口:{data_yesterday} 或 {data_today}\n\n"
        + (previous_section + "\n\n" if previous_section else "")
        + "\n\n".join(
            _build_section(src, snapshots[src], data_today) for src in SOURCE_KEYS if src in snapshots
        )
    )
    previous_items = (previous_overview.get("items") if isinstance(previous_overview, dict) else None)
    prev_state = _carryover_state(previous_items)

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
            items = _parse_result(ai_data, snapshots, breaking_dates, data_today,
                                  previous_items=previous_items)
            if not items:
                raise RuntimeError("解析后无有效条目")
            overview = {
                "generatedAt": int(now.timestamp() * 1000),
                "dataFetchedAt": data_date_ms,
                "missingSources": [k for k in SOURCE_KEYS if k not in snapshots],
                # 今日综述:不强制(缺失不判失败),App 端空串不渲染
                "digest": (ai_data.get("digest") or "").strip(),
                "items": items,
            }
            carry = sum(1 for i in items
                        if not i["breaking"] and _is_carryover(i["url"], i["title"], i["source"], prev_state))
            print(f"[OVERVIEW] 生成成功:{len(items)} 条(第 {attempt} 次成功),"
                  f"breaking {sum(1 for i in items if i['breaking'])} 条,"
                  f"承接上期 {carry} 条")
            return overview
        except Exception as e:
            last_err = e
            print(f"[OVERVIEW] 第 {attempt}/{MAX_ATTEMPTS} 次失败:"
                  f"{type(e).__name__}: {e}", file=sys.stderr)
            if attempt < MAX_ATTEMPTS:
                time.sleep(2 ** attempt)  # 2s, 4s

    print(f"[OVERVIEW] {MAX_ATTEMPTS} 次全败,跳过:{last_err}", file=sys.stderr)
    return None
