"""数据源 hackernews —— HackerNews Top Stories(Firebase API 两步拉取)。

items 字段:id / title / url / by / score / descendants / time(秒)/ time_iso /
discussion_url(HN 讨论页)/ target_url(外链优先,无外链回退讨论页);
无 rank(条目按 topstories 顺序落盘)。返回 (items, {});
抓取器契约见 sources/__init__.py。
"""

import json
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

from .httpio import fetch_text

# ===== 数据源 1:HackerNews =====

HN_BASE = "https://hacker-news.firebaseio.com/v0"


def _hn_item(item_id):
    """拉单个 item JSON;失败返回 None(对齐 fetchItemJson 的 getOrNull 行为)。"""
    try:
        text = fetch_text(f"{HN_BASE}/item/{item_id}.json",
                          extra_headers={"Accept": "application/json"}, expect_json=True)
        obj = json.loads(text)
        if not obj or obj.get("id") in (None, -1):
            return None
        return obj
    except Exception:
        return None


def fetch_hackernews(limit=20):
    """
    两步拉取(对齐 HackerNewsRepository.fetchTopStoriesFromNetwork):
      1. /topstories.json → id 数组,取前 limit 条
      2. 并发逐条拉 /item/{id}.json
    返回 (items, {})。
    """
    ids_raw = fetch_text(f"{HN_BASE}/topstories.json",
                         extra_headers={"Accept": "application/json"}, expect_json=True)
    ids = json.loads(ids_raw)[:limit]

    items = []
    with ThreadPoolExecutor(max_workers=8) as pool:
        objs = list(pool.map(_hn_item, ids))
    for obj in objs:
        if not obj:
            continue
        item_id = obj.get("id")
        title = (obj.get("title") or "").strip()
        url = obj.get("url") or ""
        if item_id is None or not title:
            continue
        t = obj.get("time", 0) or 0
        discussion_url = f"https://news.ycombinator.com/item?id={item_id}"
        items.append({
            "id": item_id,
            "title": title,
            "url": url,
            "by": obj.get("by") or "",
            "score": obj.get("score", 0) or 0,
            "descendants": obj.get("descendants", 0) or 0,
            "time": t,
            "time_iso": datetime.fromtimestamp(t, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ") if t else "",
            "discussion_url": discussion_url,
            "target_url": url if url else discussion_url,
        })
    return items, {}


# ===== 源身份与下游适配配置(2026-10 收口:fetch_data/ai_summary/
# overview_summary/trend_keywords 的分源配置表与字段映射全部由此派生) =====

SOURCE_KEY = "hackernews"

# 注册表统一入口别名(sources/__init__.SOURCES 经它组装)
fetch = fetch_hackernews

# 五键契约(缺失会被 test_sources_registry 的适配契约测试当场红):
#   display_title 总览 prompt 源段标题 / empty_ok 空结果是否合法 /
#   min_items 抓取健康哨兵下限 / top_n 摘要喂 AI 条数 / has_metrics 热度档位有无真实指标
META = {
    "display_title": "HackerNews",
    "empty_ok": False,
    "min_items": 10,
    "top_n": 15,
    "has_metrics": True,
}


def item_url(o):
    """落地页 URL(摘要回填 / trend / overview 共用口径)。"""
    return (o.get("target_url") or "").strip() or (o.get("url") or "").strip()


def item_title(o):
    """标题字段(fingerprint 口径)。"""
    return (o.get("title") or "").strip()


# ===== overview 适配(总览候选池字段提取,原 overview_summary._extract_items 分支) =====

from common import str_field, int_field, beijing_date_key_of_ms  # noqa: E402(适配段自含 import)


def overview_fields(o, fallback_date_key):
    """总览输入行字段:(title, url, metrics, blurb, date_key)。

    URL 口径是纯 target_url、无讨论页兜底 —— 与摘要回填/趋势的 item_url
    (target_url → url 兜底)微差,刻意保持原行为(overview 候选池不收 HN
    讨论页链接)。
    """
    return (
        item_title(o),
        str_field(o, "target_url"),
        f"得分 {int_field(o, 'score')} · 评论 {int_field(o, 'descendants')}",
        "",
        beijing_date_key_of_ms(int_field(o, "time") * 1000),
    )


def raw_heat(o):
    """总览原始热度:得分 + 评论数 * 0.3。"""
    return int_field(o, "score") + int_field(o, "descendants") * 0.3


# ===== trend 适配(趋势统计字段提取,原 trend_keywords._item_fields 分支) =====

def trend_fields(o):
    """趋势统计字段:(text, title, url)。"""
    title = item_title(o)
    return title, title, item_url(o)
