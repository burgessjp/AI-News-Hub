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
