#!/usr/bin/env python3
"""
数据源 AI 总结(对齐 App 端 SummaryRepository.kt)。

抓取脚本每跑完一个源,调用本模块把该源本次落盘的 items 喂给 OpenAI 兼容
服务,生成一份简体中文要点,作为 `ai_summary_v2` 字段写进快照顶层。

`ai_summary_v2` 是 JSON 数组,每个对象含 `title`(一句话标题)与 `desc`
(2-3 句描述),替代旧的纯文本 `ai_summary`(已停用,App 端兼容回退)。

设计要点(复刻 App):
  - 总结 8 个稳定源:hackernews / github-trending / openai-anthropic-news /
    huggingface-papers / stormzhang-ai / producthunt / rundown-ai / aihot-featured。
  - 8 个 system prompt 要求模型只输出 JSON 数组(无 markdown / 无解释);
    user prompt 格式化器搬自 App SummaryRepository.kt(lines 102-150)。
  - temperature=0.5(对齐 App 的 requestSummary);读取超时 30s。
  - 配置走环境变量:AI_NEWS_HUB_AI_BASE_URL / AI_NEWS_HUB_AI_MODEL /
    AI_NEWS_HUB_AI_API_KEY(由 pipeline.sh 在执行前统一检测)。
  - 调用失败仅返回 None,不抛 —— 总结是「锦上添花」,绝不能拖垮抓取主链路。

用法(独立调用,主要供 fetch_data.py 内部 import):
  from ai_summary import summarize_source, SUMMARY_SOURCES
  items = summarize_source("hackernews", raw_items)  # 返回 list[dict] 或 None
"""

import os
import sys
import time

import ai_client


# ===== 环境变量 =====
# 由调用方(pipeline.sh)在执行前统一检测;这里只在 config_ready() 里判一次。
ENV_BASE_URL = "AI_NEWS_HUB_AI_BASE_URL"  # 如 https://api.deepseek.com(填到根)
ENV_MODEL = "AI_NEWS_HUB_AI_MODEL"        # 如 deepseek-chat
ENV_API_KEY = "AI_NEWS_HUB_AI_API_KEY"    # 用户的 OpenAI 兼容 key

# 对齐 App:connectTimeout 15s, readTimeout 30s(摘要比翻译慢,放宽读取)
TIMEOUT = (15, 30)
TEMPERATURE = 0.5
# 自带重试 3 次(对齐 fetch_data 主链路的重试上限);失败间隔 2s/4s
MAX_ATTEMPTS = 3

# App 端只对这 8 个源做摘要
SUMMARY_SOURCES = ("hackernews", "github-trending", "huggingface-papers", "stormzhang-ai", "producthunt", "rundown-ai", "aihot-featured", "openai-anthropic-news")


# ===== system prompt =====
#
# 通用骨架:只输出 JSON 数组(6-10 个对象,每个含 title + desc);专有名词保留原文;
# 简体中文;禁套话、禁 markdown 代码块、禁解释性文字。各源再定制侧重点。

HACKERNEWS_PROMPT = """你是一位资深技术编辑与 HackerNews 社区观察者。请把用户提供的 HackerNews 当日热门条目，整理成一份高质量的中文技术简报。

【语言要求】必须输出简体中文。即使输入标题是英文，正文也用中文表达；项目名、公司名、技术术语、人名等专有名词保留原文，不要音译。

【输出格式】只输出一个 JSON 数组，6 到 10 个对象，不要输出任何其它内容。每个对象两个字段：
{"title": "一句话概括标题", "desc": "用 2-3 句中文说明这件事是什么、为什么值得关注或开发者反应如何"}

【内容要求】
- 按得分热度排序，重要的放前面；
- 抓住技术本质（新发布 / 漏洞 / 工具 / 行业观点），不要照抄标题；
- 合并同一事件的多条讨论；
- 高分且评论多的条目适当多写。

【禁止】不要输出英文；不要输出 markdown 代码块标记（```）、不要输出解释性文字、前后缀或引导句（如「以下是今日…简报」）；不要「以上是…」「希望对你有帮助」等套话。直接给出 JSON 数组。"""

GITHUB_PROMPT = """你是一位开源生态观察者。请把用户提供的 GitHub Trending 当日热门仓库，整理成一份中文开源动态简报。

【语言要求】必须输出简体中文。仓库 owner/name、技术名词保留原文，不要翻译。

【输出格式】只输出一个 JSON 数组，6 到 10 个对象，不要输出任何其它内容。每个对象两个字段：
{"title": "owner/name（一句话价值定位）", "desc": "用 2-3 句中文说明这个项目解决什么问题、适用场景，以及今日新增 star 反映的热度趋势"}

【内容要求】
- 结合描述和语言推断项目价值，不要只复述描述；
- 今日新增 star 多的排前面；
- 同类项目可合并成一条并对比。

【禁止】不要输出英文正文；不要输出 markdown 代码块标记或解释性文字；不要套话。直接给出 JSON 数组。"""

PAPERS_PROMPT = """你是一位 AI 研究前沿解读员。请把用户提供的 HuggingFace Trending Papers，整理成一份中文论文速读简报。

【语言要求】必须输出简体中文。论文标题先给中文意译，括号内附英文原标题；模型名、方法名、数据集名等专有名词保留原文。

【输出格式】只输出一个 JSON 数组，6 到 10 个对象，不要输出任何其它内容。每个对象两个字段：
{"title": "中文标题（English Title，↑upvote）", "desc": "用 2-3 句中文说明这篇论文研究什么问题、方法亮点、可能的影响"}

【内容要求】
- upvote 高的排前面；
- 避免堆砌术语，用普通开发者能懂的话解释；
- 同一方向的论文可合并对比。

【禁止】不要输出全英文；不要逐字翻译摘要；不要输出 markdown 代码块标记或解释性文字；不要「以上是…」「希望对你有帮助」等套话；不要输出前后缀（如「以下是今日…简报」这类引导句）。直接给出 JSON 数组。"""

STORMZHANG_PROMPT = """你是一位 AI 行业资讯编辑。用户提供的已是中文 AI 资讯摘要（来自 Hacker News / Reddit / Product Hunt / The Rundown AI / TLDR AI 等多个信源），请重新归纳成一份结构清晰的中文要点清单。

【语言要求】输出简体中文。

【输出格式】只输出一个 JSON 数组，6 到 10 个对象，不要输出任何其它内容。每个对象两个字段：
{"title": "事件标题", "desc": "用 2-3 句说明核心事实，并在末尾标注信源（如「（来源：Reddit）」）"}

【内容要求】
- 按主题去重合并：同一事件的多条合成一条，保留最完整的信息；
- 突出产品发布、融资、模型更新、政策等硬事实；
- 按重要性排序。

【禁止】不要照抄原文；不要输出 markdown 代码块标记或解释性文字；不要套话。直接给出 JSON 数组。"""

PRODUCTHUNT_PROMPT = """你是一位资深产品观察者与 Product Hunt 社区编辑。请把用户提供的 Product Hunt 当日热门产品，整理成一份中文产品发现简报。

【语言要求】必须输出简体中文。产品名、公司名保留原文，不翻译；产品定位（tagline）用中文意译，保留原意。

【输出格式】只输出一个 JSON 数组，6 到 10 个对象，不要输出任何其它内容。每个对象两个字段：
{"title": "产品名（一句话价值定位）", "desc": "用 2-3 句中文说明它解决什么问题、面向谁、有什么亮点（AI/开发者工具/效率等），并在末尾标注热度（如「（↑upvote，💬评论）」）"}

【内容要求】
- 按 upvote 热度排序，重要的放前面；
- 抓住产品本质（解决了什么痛点、有何创新），不要只复述 tagline；
- 同类产品（如多个 AI 工具）可合并对比；
- 明显是 AI/开发者相关的产品适当多写，纯消费类一句话带过。

【禁止】不要输出英文正文；不要逐字翻译 tagline；不要输出 markdown 代码块标记或解释性文字；不要「以上是…」「希望对你有帮助」等套话；不要输出前后缀（如「以下是今日…简报」这类引导句）。直接给出 JSON 数组。"""

RUNDOWN_AI_PROMPT = """你是一位资深 AI 行业观察者与英文 newsletter 解读者。请把用户提供的 The Rundown AI 近期 newsletter 标题列表，整理成一份中文 AI 动态简报。

【背景】The Rundown AI 是头部英文日更 AI newsletter，每期围绕一个主事件（标题）+ 一个次要工具/技巧（PLUS 副标题）。输入只有标题和副标题，没有正文，请基于标题本身的事实信息归纳，不要臆测细节。

【语言要求】必须输出简体中文。公司名、产品名、模型名、人名等专有名词保留原文，不要音译。

【输出格式】只输出一个 JSON 数组，6 到 10 个对象，不要输出任何其它内容。每个对象两个字段：
{"title": "事件标题", "desc": "用 2-3 句中文说明这件事是什么、为什么值得关注（结合标题与 PLUS 副标题的信息）"}

【内容要求】
- 按事件重要性排序，重大发布（新模型、融资、政策、独家访谈）放前面；
- 抓住标题里的事实（谁做了什么），不要展开没有依据的推测；
- 同一主题的多期 newsletter 可合并成一条；
- 工具类条目（PLUS 副标题）一句话带过即可，重点放在主事件。

【禁止】不要输出英文正文；不要逐字翻译标题；不要输出 markdown 代码块标记或解释性文字；不要「以上是…」「希望对你有帮助」等套话；不要输出前后缀（如「以下是今日…简报」这类引导句）。直接给出 JSON 数组。"""

AIHOT_FEATURED_PROMPT = """你是一位资深 AI 行业资讯编辑。用户提供的已是中文 AI 资讯精选（来自 AIHot 后端聚合的多源 RSS/X 等，已人工/算法筛选），请重新归纳成一份结构清晰的中文要点清单。

【背景】AIHot 精选覆盖产品发布、融资、模型更新、政策、行业观点等硬事实，可能存在同一事件被多源覆盖的情况。输入含标题和一句中文摘要，请基于此归纳，不要臆测细节。

【语言要求】输出简体中文。公司名、产品名、模型名、人名等专有名词保留原文。

【输出格式】只输出一个 JSON 数组，6 到 10 个对象，不要输出任何其它内容。每个对象两个字段：
{"title": "事件标题", "desc": "用 2-3 句说明核心事实，必要时在末尾标注信源（如「（来源：TechCrunch）」）"}

【内容要求】
- 按主题去重合并：同一事件的多条合成一条，保留最完整的信息；
- 突出产品发布、融资、模型更新、政策等硬事实，观点类适当靠后；
- 按重要性排序，重大事件放前面；
- score 高的条目适当多写（score 反映后端筛选权重）。

【禁止】不要照抄摘要原文；不要输出 markdown 代码块标记或解释性文字；不要「以上是…」「希望对你有帮助」等套话；不要输出前后缀（如「以下是今日…简报」这类引导句）。直接给出 JSON 数组。"""

OPENAI_ANTHROPIC_NEWS_PROMPT = """你是一位资深 AI 厂商动态观察者。请把用户提供的 OpenAI 与 Anthropic 近期官方动态，整理成一份中文厂商动态简报。

【背景】这是两家头部 AI 公司的官方博客/新闻（OpenAI 与 Anthropic 各自标注 vendor），输入含标题、英文摘要、分类（如 Product/Research/Announcements/Engineering/Claude）。Anthropic 旗下含 Claude 产品公告（category=Claude）与工程深度文（category=Engineering）两个子频道，均为一手官方信息。请基于标题与摘要归纳，不要臆测细节。

【语言要求】必须输出简体中文。公司名、产品名、模型名（如 GPT、Claude、Codex）、人名等专有名词保留原文，不要音译。

【输出格式】只输出一个 JSON 数组，6 到 10 个对象，不要输出任何其它内容。每个对象两个字段：
{"title": "事件标题", "desc": "用 2-3 句中文说明这是哪家厂商（OpenAI/Anthropic）做了什么、有什么影响，必要时在末尾标注厂商（如「（OpenAI）」）"}

【内容要求】
- 按重要性排序：新模型发布、重大产品更新、融资/政策放前面，常规案例、活动、教程靠后；
- 抓住硬事实（谁发布了什么），不要展开没有依据的推测；
- 同一厂商的多条动态可按主题合并；
- 两家厂商对比性动态（如同期发布竞品模型）可合并成一条对比。

【禁止】不要输出英文正文；不要逐字翻译摘要；不要输出 markdown 代码块标记或解释性文字；不要「以上是…」「希望对你有帮助」等套话；不要输出前后缀（如「以下是今日…简报」这类引导句）。直接给出 JSON 数组。"""

SYSTEM_PROMPTS = {
    "hackernews": HACKERNEWS_PROMPT,
    "github-trending": GITHUB_PROMPT,
    "huggingface-papers": PAPERS_PROMPT,
    "stormzhang-ai": STORMZHANG_PROMPT,
    "producthunt": PRODUCTHUNT_PROMPT,
    "rundown-ai": RUNDOWN_AI_PROMPT,
    "aihot-featured": AIHOT_FEATURED_PROMPT,
    "openai-anthropic-news": OPENAI_ANTHROPIC_NEWS_PROMPT,
}


# ===== user prompt 格式化(逐字搬自 SummaryRepository.kt lines 102-150) =====
#
# Top-N 控 token,与 App 取相同条数与格式。字段名对齐 fetch_data.py 的 items 结构。

def _fmt_hackernews(items):
    """top 15,每条「• <title>（得分 X，评论 Y）」(对齐 App 的 HACKERNEWS.load)。"""
    lines = []
    for s in items[:15]:
        title = (s.get("title") or "").strip()
        if not title:
            continue
        lines.append(f"• {title}（得分 {s.get('score', 0)}，评论 {s.get('descendants', 0)}）")
    return "以下是今日 HackerNews 热门（按得分排序）：\n" + "\n".join(lines)


def _fmt_github_trending(items):
    """top 10,每条「• owner/name（今日 +N★，共 M★，lang）：desc」(对齐 App 的 GITHUB_TRENDING.load)。"""
    lines = []
    for r in items[:10]:
        owner = r.get("owner", "")
        name = r.get("name", "")
        desc = (r.get("description") or "").strip() or "（无描述）"
        lang = (r.get("language") or "").strip() or "未知语言"
        lines.append(
            f"• {owner}/{name}（今日 +{r.get('starsToday', 0)}★，"
            f"共 {r.get('totalStars', 0)}★，{lang}）：{desc}"
        )
    return "以下是今日 GitHub Trending（按今日新增 star 排序）：\n" + "\n".join(lines)


def _fmt_huggingface_papers(items):
    """top 10,每条「• <title>（↑upvotes）：summary」(对齐 App 的 HUGGINGFACE_PAPERS.load)。"""
    lines = []
    for p in items[:10]:
        title = (p.get("title") or "").strip()
        if not title:
            continue
        summary = (p.get("summary") or "").strip() or "（无摘要）"
        lines.append(f"• {title}（↑{p.get('upvotes', 0)}）：{summary}")
    return "以下是今日 HuggingFace 热门论文（按 upvote 排序）：\n" + "\n".join(lines)


def _fmt_stormzhang_ai(items):
    """top 15,每条「• [source] summary」(对齐 App 的 STORMZHANG_AI.load)。"""
    lines = []
    for n in items[:15]:
        src = (n.get("source") or "").strip() or "未知来源"
        summary = (n.get("summary") or "").strip()
        if not summary:
            continue
        lines.append(f"• [{src}] {summary}")
    return "以下是今日聚合的 AI 资讯（含多个信源）：\n" + "\n".join(lines)


def _fmt_producthunt(items):
    """top 15,每条「• name(↑votes,💬comments)：tagline」(对齐 App PRODUCTHUNT.load)。"""
    lines = []
    for p in items[:15]:
        name = (p.get("name") or "").strip()
        if not name:
            continue
        tagline = (p.get("tagline") or "").strip() or "（无定位）"
        topics = p.get("topics") or []
        topic_str = f"[{','.join(topics[:2])}] " if topics else ""
        lines.append(
            f"• {name}（↑{p.get('votesCount', 0)}，💬{p.get('commentsCount', 0)}）：{topic_str}{tagline}"
        )
    return "以下是今日 Product Hunt 热门产品（按 upvote 排序）：\n" + "\n".join(lines)


def _fmt_rundown_ai(items):
    """top 15,每条「• title：subtitle」(对齐 App RUNDOWN_AI.load)。

    The Rundown AI 每篇 newsletter 含一个主标题 + 一个 PLUS 副标题(次要工具/技巧),
    合并成一行喂给 AI,无统计字段(列表页无 upvote/comments)。
    """
    lines = []
    for n in items[:15]:
        title = (n.get("title") or "").strip()
        if not title:
            continue
        subtitle = (n.get("subtitle") or "").strip()
        if subtitle:
            lines.append(f"• {title}（PLUS：{subtitle}）")
        else:
            lines.append(f"• {title}")
    return "以下是近期 The Rundown AI 的 newsletter 标题（按时间倒序）：\n" + "\n".join(lines)


def _fmt_aihot_featured(items):
    """top 15,每条「• title(score)：summary」(对齐 App AIHOT_FEATURED.load)。

    AIHot 精选是中文 AI 资讯(后端已聚合多源),输入含标题和中文摘要,
    无需翻译。附 score 让 AI 感知后端筛选权重(不强制按 score 排序)。
    """
    lines = []
    for n in items[:15]:
        title = (n.get("title") or "").strip()
        if not title:
            continue
        summary = (n.get("summary") or "").strip()
        score = n.get("score", 0) or 0
        if summary:
            lines.append(f"• {title}（score {score}）：{summary}")
        else:
            lines.append(f"• {title}（score {score}）")
    return "以下是今日 AIHot 精选热门（按后端 score 排序）：\n" + "\n".join(lines)


def _fmt_openai_anthropic_news(items):
    """top 15,每条「• [vendor] title（category）：summary」(对齐 App OPENAI_ANTHROPIC_NEWS.load)。

    OpenAI(RSS)与 Anthropic(HTML)合并源,输入含英文标题/摘要 + vendor/category 标注。
    附 vendor/category 让 AI 感知厂商归属与分类(不强制按某字段排序,本身已按时间倒序)。
    """
    lines = []
    for n in items[:15]:
        title = (n.get("title") or "").strip()
        if not title:
            continue
        vendor = (n.get("vendor") or "").strip()
        category = (n.get("category") or "").strip()
        summary = (n.get("summary") or "").strip()
        prefix = f"• [{vendor}]" if vendor else "•"
        meta = f"（{category}）" if category else ""
        if summary:
            lines.append(f"{prefix} {title}{meta}：{summary}")
        else:
            lines.append(f"{prefix} {title}{meta}")
    return "以下是近期 OpenAI / Anthropic 的官方动态（按发布时间倒序）：\n" + "\n".join(lines)


USER_PROMPT_BUILDERS = {
    "hackernews": _fmt_hackernews,
    "github-trending": _fmt_github_trending,
    "huggingface-papers": _fmt_huggingface_papers,
    "stormzhang-ai": _fmt_stormzhang_ai,
    "producthunt": _fmt_producthunt,
    "rundown-ai": _fmt_rundown_ai,
    "aihot-featured": _fmt_aihot_featured,
    "openai-anthropic-news": _fmt_openai_anthropic_news,
}


def config_ready():
    """三项 AI 配置是否齐全(对齐 App 的 AiConfig.isReady,但无 enabled 开关 ——
    脚本侧靠 pipeline.sh 是否注入决定是否做)。缺任一项返回 False。"""
    return all(os.getenv(k) for k in (ENV_BASE_URL, ENV_MODEL, ENV_API_KEY))


def summarize_source(source, items):
    """
    给某源的本次 items 生成中文 AI 摘要。返回 list[dict](每项含 title + desc),失败返回 None。

    - 不支持的源(未知 key)→ 直接返回 None,不算错。
    - 空 items → 返回 None(没东西可总结)。
    - 配置缺失 → 返回 None,并 stderr 提示(让调用方知道为什么没出摘要)。
    - API 调用 → 3 次重试(间隔 2s/4s),全败返回 None。

    AI 请求/解析(含 markdown 围栏剥离、429 限流快速重试、thinking 开关)统一经
    `ai_client.call_llm`;本函数只做该源的业务校验:过滤掉 title/desc 为空的项,
    过滤后为空则视为失败(抛 RuntimeError 触发本函数的 3 次业务层重试)。
    """
    if source not in SUMMARY_SOURCES:
        return None
    if not items:
        return None
    if not config_ready():
        missing = [k for k in (ENV_BASE_URL, ENV_MODEL, ENV_API_KEY) if not os.getenv(k)]
        print(f"[AI] 跳过 {source} 摘要:缺少环境变量 {missing}", file=sys.stderr)
        return None

    base_url = os.getenv(ENV_BASE_URL)
    model = os.getenv(ENV_MODEL)
    api_key = os.getenv(ENV_API_KEY)
    system_prompt = SYSTEM_PROMPTS[source]
    user_prompt = USER_PROMPT_BUILDERS[source](items)

    last_err = None
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            parsed = ai_client.call_llm(
                system_prompt, user_prompt, base_url, model, api_key,
                timeout=TIMEOUT, temperature=TEMPERATURE, expect="array",
            )
            # 业务校验:过滤掉 title/desc 为空的项(ai_client 只保证是非空数组)。
            cleaned = []
            for obj in parsed:
                if not isinstance(obj, dict):
                    continue
                title = (obj.get("title") or "").strip()
                desc = (obj.get("desc") or "").strip()
                if title and desc:
                    cleaned.append({"title": title, "desc": desc})
            if not cleaned:
                raise RuntimeError("AI 响应解析后无有效条目(无 title/desc 非空项)")
            print(f"[AI]   {source:<20} 摘要 {len(cleaned)} 条(第 {attempt} 次成功)")
            return cleaned
        except Exception as e:
            last_err = e
            print(f"[AI]   {source:<20} 第 {attempt}/{MAX_ATTEMPTS} 次失败:{type(e).__name__}: {e}",
                  file=sys.stderr)
            if attempt < MAX_ATTEMPTS:
                time.sleep(2 ** attempt)  # 2s, 4s
    print(f"[AI]   {source:<20} 摘要 3 次全败,跳过:{last_err}", file=sys.stderr)
    return None
