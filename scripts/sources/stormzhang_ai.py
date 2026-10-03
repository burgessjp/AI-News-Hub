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


# ===== 源身份与下游适配配置(2026-10 收口:fetch_data/ai_summary/
# overview_summary/trend_keywords 的分源配置表与字段映射全部由此派生) =====

SOURCE_KEY = "stormzhang-ai"

# 注册表统一入口别名(sources/__init__.SOURCES 经它组装)
fetch = fetch_stormzhang_ai

# 五键契约(缺失会被 test_sources_registry 的适配契约测试当场红):
#   display_title 总览 prompt 源段标题 / empty_ok 空结果是否合法 /
#   min_items 抓取健康哨兵下限 / top_n 摘要喂 AI 条数 / has_metrics 热度档位有无真实指标
META = {
    "display_title": "stormzhang AI",
    "empty_ok": False,
    "min_items": 10,
    "top_n": 15,
    "has_metrics": False,
}


def item_url(o):
    """落地页 URL(摘要回填 / trend / overview 共用口径)。"""
    return (o.get("url") or "").strip()


def item_title(o):
    """标题字段(fingerprint 口径):该源无独立标题字段,取 summary。"""
    return (o.get("summary") or "").strip()


# ===== overview 适配(总览候选池字段提取,原 overview_summary._extract_items 分支) =====

from common import str_field  # noqa: E402(适配段自含 import)


def overview_fields(o, fallback_date_key):
    """总览输入行字段:(title, url, metrics, blurb, date_key)。

    time 形如 "2026-07-15 20:00"(北京时间无时区),直接取前 10 字符(yyyy-MM-dd)。
    """
    t = str_field(o, "time")
    return (
        item_title(o),
        item_url(o),
        f"信源 {str_field(o, 'source')}",
        str_field(o, "english"),
        t[:10] if len(t) >= 10 else "",
    )


# ===== trend 适配(趋势统计字段提取,原 trend_keywords._item_fields 分支) =====

def trend_fields(o):
    """趋势统计字段:(text, title, url)。

    english 尾部常带 TLDR 赞助行("PLUS: <软广> <作者>, +N"),且赞助条目整条
    english 就是 "PLUS: ...";partition 两种都覆盖,避免作者名/赞助商混进词频。
    """
    english = str_field(o, "english").partition("PLUS:")[0]
    return f"{english}\n{item_title(o)}", item_title(o), item_url(o)
