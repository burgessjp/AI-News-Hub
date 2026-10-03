"""数据源 rundown-ai —— The Rundown AI(/articles 列表页,RSC 内嵌 JSON 主路径 + DOM 卡片兜底)。

items 字段:rank / slug / url / title / subtitle(已剥「PLUS: 」前缀)/
authors(「首作者, +N」)/ coverUrl / publishedAt(北京时间 yyyy-MM-dd HH:mm,
DOM 兜底路径恒空)。返回 (items, {});抓取器契约见 sources/__init__.py。
"""

import json
import re
from datetime import datetime

from bs4 import BeautifulSoup

from common import BEIJING_TZ as CST

from .httpio import fetch_text

# ===== 数据源 4.5:The Rundown AI(beehiiv 托管的 AI newsletter) =====

def _rundown_authors_str(authors):
    """
    RSC 的 authors 是字符串数组(全体作者全名),压成旧版展示格式「首作者, +N」。

    对齐改版前 beehiiv 卡片的「Zach Mink, +3」样式;空数组返回空串,
    非数组值原样返回(理论上不会出现)。
    """
    if isinstance(authors, list):
        names = [a.strip() for a in authors if isinstance(a, str) and a.strip()]
        if not names:
            return ""
        if len(names) == 1:
            return names[0]
        return f"{names[0]}, +{len(names) - 1}"
    return authors if isinstance(authors, str) else ""


def _extract_rundown_rsc_articles(html):
    """
    从 /articles 页 <script> 的 RSC flight payload 里抠出文章数组。

    2026-08 改版后完整元数据不在 DOM(仅渲染约 8 张卡),而在
    self.__next_f.push([1,"..."]) 的 JS 字符串里,形如
    \\"articles\\":[{\\"slug\\":...},...](JSON 整体被 JS 转义)。
    锚定 \\"articles\\":[ 后按转义感知的括号配对截取数组原文,包一层引号借
    json.loads 还原转义,再二次解析成对象列表。

    @return 文章 dict 列表(多段合并;解析失败的段跳过,可能为空列表)
    """
    arrays = []
    for m in re.finditer(r'\\"articles\\":\[', html):
        start = m.end() - 1  # 回退一个字符,指向 '[' 本身
        n = len(html)
        j = start
        depth = 0
        end = -1
        while j < n:
            c = html[j]
            if c == "\\":
                j += 2  # 跳过转义对,\\[ \\] 等不影响括号配对
                continue
            if c in "[{":
                depth += 1
            elif c in "]}":
                depth -= 1
                if depth == 0:
                    end = j
                    break
            j += 1
        if end < 0:
            continue
        try:
            arr = json.loads(json.loads('"' + html[start:end + 1] + '"'))
        except ValueError:
            continue
        if isinstance(arr, list):
            arrays.extend(a for a in arr if isinstance(a, dict))
    return arrays


def _rundown_items_from_rsc(html):
    """主路径:RSC 内嵌 JSON → 落盘 item 列表(全量约 48 篇,带 publishDate)。"""
    items = []
    seen = set()
    for a in _extract_rundown_rsc_articles(html):
        slug = (a.get("slug") or "").strip()
        title = (a.get("title") or "").strip()
        if not slug or not title or slug in seen:
            continue
        seen.add(slug)
        # subtitle 自带「PLUS: 」前缀,剥掉对齐旧快照格式
        subtitle = re.sub(r"^PLUS:\s*", "", (a.get("subtitle") or "").strip(),
                          flags=re.IGNORECASE)
        # publishDate(UTC ISO)转北京时间「yyyy-MM-dd HH:mm」;解析失败留空
        published_at = ""
        pub = (a.get("publishDate") or "").strip()
        if pub:
            try:
                dt = datetime.fromisoformat(pub.replace("Z", "+00:00"))
                published_at = dt.astimezone(CST).strftime("%Y-%m-%d %H:%M")
            except ValueError:
                pass
        items.append({
            "rank": len(items) + 1,
            "slug": slug,
            "url": f"https://www.therundown.ai/articles/{slug}",
            "title": title,
            "subtitle": subtitle,
            "authors": _rundown_authors_str(a.get("authors")),
            "coverUrl": (a.get("thumbnailUrl") or "").strip(),
            "publishedAt": published_at,
        })
    return items


def _rundown_items_from_dom(soup):
    """
    降级路径:解析 /articles 页 DOM 卡片(仅约 8 张,无 PLUS 副标题、无日期)。

    RSC payload 结构再变导致主路径空手时兜底,字段语义对齐旧版卡片解析:
    h3 标题 + PLUS 段(若渲染)+ 作者行「姓名 • N minutes」取 • 前段 + beehiiv 封面图。
    """
    items = []
    seen = set()
    for el in soup.select('a[href^="/articles/"]'):
        href = (el.get("href") or "").strip()
        slug = href.split("?")[0].split("#")[0].removeprefix("/articles/").strip("/")
        if not slug or slug in seen:
            continue
        h3 = el.find("h3")
        title = h3.get_text(strip=True) if h3 else ""
        if not title:
            continue
        subtitle = ""
        authors = ""
        for p in el.find_all("p"):
            txt = p.get_text(" ", strip=True)
            if txt.upper().startswith("PLUS"):
                subtitle = re.sub(r"^PLUS:\s*", "", txt, flags=re.IGNORECASE)
            elif "\u2022" in txt:  # 「姓名 • 5 minutes」
                authors = txt.split("\u2022")[0].strip()
        cover_url = ""
        for img in el.find_all("img"):
            src = (img.get("src") or "").strip()
            if not src or "width=256" in src:
                continue
            if "beehiiv.com/cdn-cgi/image" in src or "beehiiv-images-production" in src:
                cover_url = src
                break
        seen.add(slug)
        items.append({
            "rank": len(items) + 1,
            "slug": slug,
            "url": f"https://www.therundown.ai/articles/{slug}",
            "title": title,
            "subtitle": subtitle,
            "authors": authors,
            "coverUrl": cover_url,
            "publishedAt": "",
        })
    return items


def fetch_rundown_ai():
    """
    抓 https://www.therundown.ai/articles 文章列表页(2026-08 改版后的主列表)。

    改版排查结论(2026-08-25):
      - 文章 URL 从 /p/<slug> 迁到 /articles/<slug>(旧 /p/ 链接 301 跳转仍可打开)
      - 首页只剩 5 张精选卡,完整列表在 /articles;DOM 只渲染首屏约 8 张卡,
        全量约 48 篇元数据(含 publishDate/category/readTimeMinutes)藏在
        <script> 的 RSC flight payload 里
      - robots.txt 对 /articles 无限制(仅禁 /api/ /landing/),无 token 无 paywall

    解析双路径:
      1. 主路径:RSC 内嵌 JSON(全量,副标题/作者/封面/发布时间齐全)
      2. 兜底:DOM 卡片 a[href^="/articles/"](数量少且无日期),RSC 结构再变时
         保底不断供

    与旧版快照的兼容:
      - subtitle 继续剥「PLUS: 」前缀;authors 数组压成「首作者, +N」
      - 新增 publishedAt 字段(北京时间 yyyy-MM-dd HH:mm);旧快照无此字段,
        消费端(App fromJson / overview / trend)均按可选字段处理,不影响
      - url 改用 /articles/<slug>;历史快照里的 /p/ 链接靠 301 仍可打开

    字段命名 camelCase,对齐 App 端 RundownAiArticle.kt 的 fromJson。
    """
    html = fetch_text(
        "https://www.therundown.ai/articles",
        extra_headers={
            "Accept": "text/html,application/xhtml+xml",
            "Accept-Language": "en-US,en;q=0.9",
        },
    )
    items = _rundown_items_from_rsc(html)
    if not items:
        items = _rundown_items_from_dom(BeautifulSoup(html, "lxml"))
    return items, {}


# ===== 源身份与下游适配配置(2026-10 收口:fetch_data/ai_summary/
# overview_summary/trend_keywords 的分源配置表与字段映射全部由此派生) =====

SOURCE_KEY = "rundown-ai"

# 注册表统一入口别名(sources/__init__.SOURCES 经它组装)
fetch = fetch_rundown_ai

# 五键契约(缺失会被 test_sources_registry 的适配契约测试当场红):
#   display_title 总览 prompt 源段标题 / empty_ok 空结果是否合法 /
#   min_items 抓取健康哨兵下限 / top_n 摘要喂 AI 条数 / has_metrics 热度档位有无真实指标
META = {
    "display_title": "The Rundown AI",
    "empty_ok": False,
    "min_items": 8,
    "top_n": 15,
    "has_metrics": False,
}


def item_url(o):
    """落地页 URL(摘要回填 / trend / overview 共用口径)。"""
    return (o.get("url") or "").strip()


def item_title(o):
    """标题字段(fingerprint 口径)。"""
    return (o.get("title") or "").strip()


# ===== overview 适配(总览候选池字段提取,原 overview_summary._extract_items 分支) =====

from common import str_field  # noqa: E402(适配段自含 import)


def overview_fields(o, fallback_date_key):
    """总览输入行字段:(title, url, metrics, blurb, date_key)。

    2026-08 站点改版后快照带真实 publishedAt(北京时间 yyyy-MM-dd HH:mm);
    旧快照无此字段,回退抓取日期(消费方标注「抓取日期」)。
    """
    pub = str_field(o, "publishedAt")
    date_key = pub[:10] if len(pub) >= 10 else fallback_date_key
    return (item_title(o), item_url(o), "", str_field(o, "subtitle"), date_key)


# ===== trend 适配(趋势统计字段提取,原 trend_keywords._item_fields 分支) =====

def trend_fields(o):
    """趋势统计字段:(text, title, url)。"""
    title = item_title(o)
    return title, title, item_url(o)
