#!/usr/bin/env python3
"""
数据源 AI 总结(对齐 App 端 SummaryRepository.kt)。

抓取脚本每跑完一个源,调用本模块把该源本次落盘的 items 喂给 OpenAI 兼容
服务,生成一份简体中文要点,作为 `ai_summary` 字段写进快照顶层。

设计要点(复刻 App):
  - 只总结 4 个稳定源:hackernews / github-trending / huggingface-papers /
    stormzhang-ai。linuxdo 受 Cloudflare 影响不稳定,App 也没纳入,这里跳过。
  - 4 个 system prompt 与 user prompt 格式化器逐字搬自
    SummaryRepository.kt(lines 102-150 / 165-231),已经过 App 端打磨,不改字。
  - temperature=0.5(对齐 App 的 requestSummary);读取超时 30s。
  - 配置走环境变量:AI_NEWS_HUB_AI_BASE_URL / AI_NEWS_HUB_AI_MODEL /
    AI_NEWS_HUB_AI_API_KEY(由 pipeline.sh 在执行前统一检测)。
  - 调用失败仅返回 None,不抛 —— 总结是「锦上添花」,绝不能拖垮抓取主链路。

用法(独立调用,主要供 fetch_data.py 内部 import):
  from ai_summary import summarize_source, SUMMARY_SOURCES
  text = summarize_source("hackernews", items)  # 返回 str 或 None
"""

import os
import sys
import time

import requests


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

# App 端只对这 4 个源做摘要(linuxdo 不稳定,排除)
SUMMARY_SOURCES = ("hackernews", "github-trending", "huggingface-papers", "stormzhang-ai")


# ===== system prompt(逐字搬自 SummaryRepository.kt lines 165-231) =====
#
# 通用骨架:每条用「**加粗标题**：2-3 句简述」;专有名词保留原文;输出简体中文;
# 6-10 条;禁套话。各源再定制侧重点。这些 prompt 在 App 端已迭代过,不改动字。

HACKERNEWS_PROMPT = """你是一位资深技术编辑与 HackerNews 社区观察者。请把用户提供的 HackerNews 当日热门条目，整理成一份高质量的中文技术简报。

【语言要求】必须输出简体中文。即使输入标题是英文，正文也用中文表达；项目名、公司名、技术术语、人名等专有名词保留原文，不要音译。

【输出格式】6 到 10 条要点，每条格式如下：
• **一句话概括标题**：用 2-3 句中文说明这件事是什么、为什么值得关注或开发者反应如何。

【内容要求】
- 按得分热度排序，重要的放前面；
- 抓住技术本质（新发布 / 漏洞 / 工具 / 行业观点），不要照抄标题；
- 合并同一事件的多条讨论；
- 高分且评论多的条目适当多写。

【禁止】不要输出英文；不要「以上是…」「希望对你有帮助」等套话；不要额外解释你做了什么；不要输出引号或前后缀。直接给出要点列表。"""

GITHUB_PROMPT = """你是一位开源生态观察者。请把用户提供的 GitHub Trending 当日热门仓库，整理成一份中文开源动态简报。

【语言要求】必须输出简体中文。仓库 owner/name、技术名词保留原文，不要翻译。

【输出格式】6 到 10 条，每条格式如下：
• **owner/name（一句话价值定位）**：用 2-3 句中文说明这个项目解决什么问题、适用场景，以及今日新增 star 反映的热度趋势。

【内容要求】
- 结合描述和语言推断项目价值，不要只复述描述；
- 今日新增 star 多的排前面；
- 同类项目可合并成一条并对比。

【禁止】不要输出英文正文；不要套话；直接给出要点列表。"""

PAPERS_PROMPT = """你是一位 AI 研究前沿解读员。请把用户提供的 HuggingFace Trending Papers，整理成一份中文论文速读简报。

【语言要求】必须输出简体中文。论文标题先给中文意译，括号内附英文原标题；模型名、方法名、数据集名等专有名词保留原文。

【输出格式】6 到 10 条，每条格式如下：
• **中文标题（English Title，↑upvote）**：用 2-3 句中文说明这篇论文研究什么问题、方法亮点、可能的影响。

【内容要求】
- upvote 高的排前面；
- 避免堆砌术语，用普通开发者能懂的话解释；
- 同一方向的论文可合并对比。

【禁止】不要输出全英文；不要逐字翻译摘要；不要套话；直接给出要点列表。"""

STORMZHANG_PROMPT = """你是一位 AI 行业资讯编辑。用户提供的已是中文 AI 资讯摘要（来自 Hacker News / Reddit / Product Hunt / The Rundown AI / TLDR AI 等多个信源），请重新归纳成一份结构清晰的中文要点清单。

【语言要求】输出简体中文。

【输出格式】6 到 10 条，每条格式如下：
• **事件标题**：用 2-3 句说明核心事实，并在末尾标注信源（如「（来源：Reddit）」）。

【内容要求】
- 按主题去重合并：同一事件的多条合成一条，保留最完整的信息；
- 突出产品发布、融资、模型更新、政策等硬事实；
- 按重要性排序。

【禁止】不要照抄原文；不要套话；直接给出要点列表。"""

SYSTEM_PROMPTS = {
    "hackernews": HACKERNEWS_PROMPT,
    "github-trending": GITHUB_PROMPT,
    "huggingface-papers": PAPERS_PROMPT,
    "stormzhang-ai": STORMZHANG_PROMPT,
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


USER_PROMPT_BUILDERS = {
    "hackernews": _fmt_hackernews,
    "github-trending": _fmt_github_trending,
    "huggingface-papers": _fmt_huggingface_papers,
    "stormzhang-ai": _fmt_stormzhang_ai,
}


def config_ready():
    """三项 AI 配置是否齐全(对齐 App 的 TranslationConfig.isReady,但无 enabled 开关 ——
    脚本侧靠 pipeline.sh 是否注入决定是否做)。缺任一项返回 False。"""
    return all(os.getenv(k) for k in (ENV_BASE_URL, ENV_MODEL, ENV_API_KEY))


def _request_summary(system_prompt, user_prompt, base_url, model, api_key):
    """
    发起一次 OpenAI 兼容 `/v1/chat/completions` 请求(对齐 App 的 requestSummary)。
    返回 choices[0].message.content 的去空白文本。失败抛异常。
    """
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
    if not content:
        raise RuntimeError(f"AI 响应 content 为空:{str(data)[:120]}")
    return content


def summarize_source(source, items):
    """
    给某源的本次 items 生成中文 AI 摘要。返回 str,失败返回 None。

    - 不支持的源(linuxdo / 未知)→ 直接返回 None,不算错。
    - 空 items → 返回 None(没东西可总结)。
    - 配置缺失 → 返回 None,并 stderr 提示(让调用方知道为什么没出摘要)。
    - API 调用 → 3 次重试(间隔 2s/4s),全败返回 None。
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
            text = _request_summary(system_prompt, user_prompt, base_url, model, api_key)
            print(f"[AI]   {source:<20} 摘要 {len(text)} 字(第 {attempt} 次成功)")
            return text
        except Exception as e:
            last_err = e
            print(f"[AI]   {source:<20} 第 {attempt}/{MAX_ATTEMPTS} 次失败:{type(e).__name__}: {e}",
                  file=sys.stderr)
            if attempt < MAX_ATTEMPTS:
                time.sleep(2 ** attempt)  # 2s, 4s
    print(f"[AI]   {source:<20} 摘要 3 次全败,跳过:{last_err}", file=sys.stderr)
    return None
