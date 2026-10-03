"""数据源 huggingface-papers —— HuggingFace Trending Papers(HTML 抓取)。

items 字段:rank / id / url / title / summary / upvotes / published /
authors / githubUrl。返回 (items, {});抓取器契约见 sources/__init__.py。
"""

import re

from bs4 import BeautifulSoup

from .httpio import fetch_text

# ===== 数据源 5:HuggingFace Trending Papers =====

def fetch_huggingface_papers():
    """
    HTML 抓取 https://huggingface.co/papers/trending(对齐 HuggingFacePapersRepository
    + HuggingFacePaper.fromArticle)。选择器:article.relative.overflow-hidden.rounded-xl.border。
    """
    html = fetch_text(
        "https://huggingface.co/papers/trending",
        extra_headers={
            "Accept": "text/html,application/xhtml+xml",
            "Accept-Language": "en-US,en;q=0.9,zh-CN,zh;q=0.8",
        },
    )
    soup = BeautifulSoup(html, "lxml")
    items = []
    for idx, article in enumerate(soup.select("article.relative.overflow-hidden.rounded-xl.border")):
        link = article.select_one('h3 > a[href^="/papers/"]')
        if not link:
            continue
        path = (link.get("href") or "").strip().removeprefix("/papers/")
        if not path.strip():
            continue
        title = link.get_text(strip=True)
        if not title:
            continue

        summary_el = article.select_one("p.line-clamp-2")
        summary = summary_el.get_text(strip=True) if summary_el else ""
        upvotes_el = article.select_one("div.font-semibold.text-orange-500")
        upvotes = 0
        if upvotes_el:
            try:
                upvotes = int(upvotes_el.get_text(strip=True))
            except ValueError:
                upvotes = 0

        published = ""
        for span in article.select("span"):
            txt = span.get_text(strip=True)
            if txt.startswith("Published on"):
                published = txt.replace("Published on", "", 1).strip()
                break

        # 作者:优先 "N authors";否则聚合 li[title]
        authors = ""
        n_auth = None
        for sub in article.find_all(True):
            txt = sub.get_text(strip=True)
            if re.fullmatch(r"\d+ authors", txt):
                n_auth = txt
                break
        if n_auth:
            authors = n_auth
        else:
            names = []
            for li in article.select("li[title]"):
                v = li.get("title", "").strip()
                if v and v not in names:
                    names.append(v)
            if names:
                authors = ", ".join(names)

        github_url = ""
        for a in article.select('a[href^="https://github.com/"][target="_blank"]'):
            href = (a.get("href") or "").strip()
            if href and "github.com/huggingface" not in href:
                github_url = href
                break

        items.append({
            "rank": idx + 1,
            "id": path,
            "url": f"https://huggingface.co/papers/{path}",
            "title": title,
            "summary": summary,
            "upvotes": upvotes,
            "published": published,
            "authors": authors,
            "githubUrl": github_url,
        })
    return items, {}


# ===== 源身份与下游适配配置(2026-10 收口:fetch_data/ai_summary/
# overview_summary/trend_keywords 的分源配置表与字段映射全部由此派生) =====

SOURCE_KEY = "huggingface-papers"

# 注册表统一入口别名(sources/__init__.SOURCES 经它组装)
fetch = fetch_huggingface_papers

# 五键契约(缺失会被 test_sources_registry 的适配契约测试当场红):
#   display_title 总览 prompt 源段标题 / empty_ok 空结果是否合法 /
#   min_items 抓取健康哨兵下限 / top_n 摘要喂 AI 条数 / has_metrics 热度档位有无真实指标
META = {
    "display_title": "HuggingFace Papers",
    "empty_ok": False,
    "min_items": 20,
    "top_n": 10,
    "has_metrics": True,
}


def item_url(o):
    """落地页 URL(摘要回填 / trend / overview 共用口径)。"""
    return (o.get("url") or "").strip()


def item_title(o):
    """标题字段(fingerprint 口径)。"""
    return (o.get("title") or "").strip()


# ===== overview 适配(总览候选池字段提取,原 overview_summary._extract_items 分支) =====

from common import (str_field, int_field,  # noqa: E402(适配段自含 import)
                    beijing_date_key_of_en_date)


def overview_fields(o, fallback_date_key):
    """总览输入行字段:(title, url, metrics, blurb, date_key)。

    published 是英文月份格式(如 "Jul 8, 2026",站点本地时间按北京处理)。
    """
    return (
        item_title(o),
        item_url(o),
        f"upvotes {int_field(o, 'upvotes')}",
        str_field(o, "summary"),
        beijing_date_key_of_en_date(str_field(o, "published")),
    )


def raw_heat(o):
    """总览原始热度:upvotes。"""
    return float(int_field(o, "upvotes"))


# ===== trend 适配(趋势统计字段提取,原 trend_keywords._item_fields 分支) =====

def trend_fields(o):
    """趋势统计字段:(text, title, url)。"""
    title = item_title(o)
    return title, title, item_url(o)
