"""数据源 github-trending —— GitHub Trending 仓库(HTML 抓取,article.Box-row)。

items 字段:rank / owner / name / url / description / language / languageColor /
totalStars / forks / starsToday。返回 (items, {});
parse_count 仅本源使用('64,846' → int),随源收在本文件;
抓取器契约见 sources/__init__.py。
"""

import re

from bs4 import BeautifulSoup

from .httpio import fetch_text


def parse_count(s):
    """把 '64,846' / '' / None 统一解析成 int;无法解析返回 0。
    对齐 TrendingRepo.kt 的 parseCount()。"""
    if not s:
        return 0
    return int(s.replace(",", "").strip()) if s.replace(",", "").strip().isdigit() else 0


# ===== 数据源 2:GitHub Trending =====

GH_TODAY_RE = re.compile(r"([\d,]+)\s*stars\s*today")
GH_COLOR_RE = re.compile(r"#[0-9a-fA-F]{3,6}")


def fetch_github_trending():
    """
    HTML 抓取 https://github.com/trending(对齐 GitHubTrendingRepository +
    TrendingRepo.fromArticle)。选择器:article.Box-row。
    """
    html = fetch_text(
        "https://github.com/trending",
        extra_headers={
            "Accept": "text/html,application/xhtml+xml",
            "Accept-Language": "en-US,en;q=0.9",
        },
    )
    soup = BeautifulSoup(html, "lxml")
    items = []
    for idx, article in enumerate(soup.select("article.Box-row")):
        link = article.select_one("h2 a")
        if not link:
            continue
        path = (link.get("href") or "").strip().lstrip("/")
        parts = path.split("/")
        if len(parts) < 2 or any(not p for p in parts):
            continue
        owner, name = parts[0], parts[1]

        p = article.select_one("p")
        description = p.get_text(strip=True) if p else ""
        lang_el = article.select_one("[itemprop=programmingLanguage]")
        language = lang_el.get_text(strip=True) if lang_el else ""
        color_el = article.select_one(".repo-language-color")
        language_color = ""
        if color_el:
            m = GH_COLOR_RE.search(color_el.get("style") or "")
            if m:
                language_color = m.group(0)

        stars_el = article.select_one('a[href$="/stargazers"]')
        forks_el = article.select_one('a[href$="/forks"]')
        total_stars = parse_count(stars_el.get_text(strip=True) if stars_el else "")
        forks = parse_count(forks_el.get_text(strip=True) if forks_el else "")

        m = GH_TODAY_RE.search(article.get_text(" ", strip=True))
        stars_today = parse_count(m.group(1)) if m else 0

        items.append({
            "rank": idx + 1,
            "owner": owner,
            "name": name,
            "url": f"https://github.com/{owner}/{name}",
            "description": description,
            "language": language,
            "languageColor": language_color,
            "totalStars": total_stars,
            "forks": forks,
            "starsToday": stars_today,
        })
    return items, {}


# ===== 源身份与下游适配配置(2026-10 收口:fetch_data/ai_summary/
# overview_summary/trend_keywords 的分源配置表与字段映射全部由此派生) =====

SOURCE_KEY = "github-trending"

# 注册表统一入口别名(sources/__init__.SOURCES 经它组装)
fetch = fetch_github_trending

# 五键契约(缺失会被 test_sources_registry 的适配契约测试当场红):
#   display_title 总览 prompt 源段标题 / empty_ok 空结果是否合法 /
#   min_items 抓取健康哨兵下限 / top_n 摘要喂 AI 条数 / has_metrics 热度档位有无真实指标
META = {
    "display_title": "GitHub Trending",
    "empty_ok": False,
    "min_items": 8,
    "top_n": 10,
    "has_metrics": True,
}


def item_url(o):
    """落地页 URL(摘要回填 / trend / overview 共用口径)。"""
    return (o.get("url") or "").strip()


def item_title(o):
    """标题字段(fingerprint 口径):owner/name 复合。"""
    owner = (o.get("owner") or "").strip()
    name = (o.get("name") or "").strip()
    return f"{owner}/{name}" if owner or name else ""


# ===== overview 适配(总览候选池字段提取,原 overview_summary._extract_items 分支) =====

import math  # noqa: E402(适配段自含 import)

from common import str_field, int_field


def _fmt_count(n):
    return f"{n:,}"


def overview_fields(o, fallback_date_key):
    """总览输入行字段:(title, url, metrics, blurb, date_key)。Trending 无文章日期,
    用抓取日兜底(fallback_date_key)。"""
    return (
        item_title(o),
        item_url(o),
        f"今日 star +{int_field(o, 'starsToday')} · 累计 {_fmt_count(int_field(o, 'totalStars'))}",
        str_field(o, "description"),
        fallback_date_key,
    )


def raw_heat(o):
    """总览原始热度:今日新增 star * 3 + 累计 star 对数权重。"""
    today = int_field(o, "starsToday")
    total = int_field(o, "totalStars")
    return today * 3.0 + (math.log10(total) * 10 if total > 0 else 0.0)


# ===== trend 适配(趋势统计字段提取,原 trend_keywords._item_fields 分支) =====

def trend_fields(o):
    """趋势统计字段:(text, title, url)。

    标题保留 strip("/") 语义(与 fingerprint 口径 item_title 微差,刻意);
    多字段拼接用换行:拼接边界是假相邻(见 trend _extract_terms 间隔校验)。
    """
    name = f"{str_field(o, 'owner')}/{str_field(o, 'name')}".strip("/")
    return f"{name}\n{str_field(o, 'description')}", name, str_field(o, "url")
