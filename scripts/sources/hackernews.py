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
