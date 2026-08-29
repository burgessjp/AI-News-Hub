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
