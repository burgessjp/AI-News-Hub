"""数据源 stormzhang-ai —— stormzhang AI 资讯(HTML 抓取,a.item)。

items 字段:rank / url / summary / english / source / time。
meta:{pageDate}(取自页面 <title> 的站点日期,如 "2026.07.13",
write_snapshot 会拍扁进快照顶层);抓取器契约见 sources/__init__.py。
"""

import re

from bs4 import BeautifulSoup

from .httpio import fetch_text

# ===== 数据源 4:stormzhang AI 资讯 =====

SZ_TITLE_DATE_RE = re.compile(r"\d{4}\.\d{2}\.\d{2}")


def fetch_stormzhang_ai():
    """
    HTML 抓取 https://news.stormzhang.ai(对齐 StormzhangAiNewsRepository +
    StormzhangAiNews.fromItem)。选择器:a.item。
    """
    html = fetch_text(
        "https://news.stormzhang.ai",
        extra_headers={
            "Accept": "text/html,application/xhtml+xml",
            "Accept-Language": "zh-CN,zh;q=0.9",
        },
    )
    soup = BeautifulSoup(html, "lxml")
    items = []
    for idx, el in enumerate(soup.select("a.item")):
        url = (el.get("href") or "").strip()
        if not url.startswith("http"):
            continue
        summary_el = el.select_one(".item-summary")
        summary = summary_el.get_text(strip=True) if summary_el else ""
        if not summary:
            continue
        idx_el = el.select_one(".item-index")
        rank = 0
        if idx_el:
            try:
                rank = int(idx_el.get_text(strip=True))
            except ValueError:
                rank = idx + 1
        else:
            rank = idx + 1
        en_el = el.select_one(".item-en")
        english = en_el.get_text(strip=True) if en_el else ""
        badge_el = el.select_one(".badge")
        source = badge_el.get_text(strip=True) if badge_el else ""
        time_el = el.select_one(".item-time")
        tval = time_el.get_text(strip=True) if time_el else ""
        items.append({
            "rank": rank,
            "url": url,
            "summary": summary,
            "english": english,
            "source": source,
            "time": tval,
        })

    # 页面日期取自 <title>(如 "AI Daily — 2026.07.13")
    title = soup.title.get_text(strip=True) if soup.title else ""
    m = SZ_TITLE_DATE_RE.search(title)
    page_date = m.group(0) if m else ""
    return items, {"pageDate": page_date}
