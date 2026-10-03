"""数据源 aihot-featured —— AIHot 精选 TOP20(第三方 aihot.virxact.com 公开 API)。

items 字段:rank / id / title / titleEn / summary / url / permalink(站内阅读页
深链)/ source / publishedAt / category / score / selected。
meta:{endpoint, count};抓取器契约见 sources/__init__.py。
"""

import json

from .httpio import fetch_text

# ===== 数据源 8:AIHot 精选(aihot.virxact.com 公开 API,第三方源) =====

AIHOT_API_BASE = "https://aihot.virxact.com/api/public"


def fetch_aihot_featured():
    """
    抓第三方服务 `aihot.virxact.com` 的「精选」TOP20(对齐 App 端 NewsRepository.fetchItems(mode=SELECTED))。

    与其余 7 个源的差异:本源的数据已被第三方服务预聚合(多源 RSS/X 等,人工/算法筛选为
    中文 AI 资讯),字段对齐 App 端 NewsItem.kt 的 fromJson;公开 API,无 token、无 Cloudflare、
    无 paywall,UA 必填(否则 nginx 403)。仅取 TOP20(take=20),不分页(摘要卡够用,
    App 端二级页仍走实时分页接口)。

    双通道说明:此归档仅供 App 摘要 Tab 第 7 张卡消费(ai_summary 由 ai_summary.py
    生成);App「AIHot 精选」二级页本身继续实时拉后端分页接口,数据更新鲜。
    """
    text = fetch_text(
        f"{AIHOT_API_BASE}/items?mode=selected&take=20",
        extra_headers={
            "Accept": "application/json",
            "Accept-Language": "zh-CN",
            # UA 必填:对齐 NewsRepository.kt,不带会被 nginx 403
            "User-Agent": "AIHot-Pipeline/1.0 (https://aihot.virxact.com)",
        },
        expect_json=True,
    )
    root = json.loads(text)
    raw_items = root.get("items") or []
    items = []
    for idx, it in enumerate(raw_items):
        # id / title 必有(对齐 App 端 NewsItem.fromJson)
        iid = (it.get("id") or "").strip()
        title = (it.get("title") or "").strip()
        if not iid or not title:
            continue
        items.append({
            "rank": idx + 1,
            "id": iid,
            "title": title,
            "titleEn": (it.get("title_en") or "").strip(),
            "summary": (it.get("summary") or "").strip(),
            "url": it.get("url") or "",
            # 站内中文阅读页深链(App 端深链优先用 permalink)
            "permalink": it.get("permalink") or "",
            "source": it.get("source") or "",
            "publishedAt": it.get("publishedAt") or "",
            "category": it.get("category") or "",
            "score": it.get("score", 0) or 0,
            "selected": bool(it.get("selected", False)),
        })
    return items, {"endpoint": "/items?mode=selected&take=20", "count": len(items)}


# ===== 源身份与下游适配配置(2026-10 收口:fetch_data/ai_summary/
# overview_summary/trend_keywords 的分源配置表与字段映射全部由此派生) =====

SOURCE_KEY = "aihot-featured"

# 注册表统一入口别名(sources/__init__.SOURCES 经它组装)
fetch = fetch_aihot_featured

# 五键契约(缺失会被 test_sources_registry 的适配契约测试当场红):
#   display_title 总览 prompt 源段标题 / empty_ok 空结果是否合法 /
#   min_items 抓取健康哨兵下限 / top_n 摘要喂 AI 条数 / has_metrics 热度档位有无真实指标
META = {
    "display_title": "AIHot 精选",
    "empty_ok": False,
    "min_items": 10,
    "top_n": 15,
    "has_metrics": True,
}


def item_url(o):
    """落地页 URL(摘要回填 / trend / overview 共用口径):站内阅读页深链优先。"""
    return (o.get("permalink") or "").strip() or (o.get("url") or "").strip()


def item_title(o):
    """标题字段(fingerprint 口径)。"""
    return (o.get("title") or "").strip()


# ===== overview 适配(总览候选池字段提取,原 overview_summary._extract_items 分支) =====

from common import (str_field, int_field,  # noqa: E402(适配段自含 import)
                    beijing_date_key_of_iso)


def overview_fields(o, fallback_date_key):
    """总览输入行字段:(title, url, metrics, blurb, date_key)。"""
    return (
        item_title(o),
        item_url(o),
        f"权重 {int_field(o, 'score')} · {str_field(o, 'source')}",
        str_field(o, "summary"),
        beijing_date_key_of_iso(str_field(o, "publishedAt")),
    )


def raw_heat(o):
    """总览原始热度:后端筛选权重 score。"""
    return float(int_field(o, "score"))


# ===== trend 适配(趋势统计字段提取,原 trend_keywords._item_fields 分支) =====

def trend_fields(o):
    """趋势统计字段:(text, title, url)。

    titleEn 英文原文优先;中文 title 一并带上(供 CJK 变体子串匹配)。
    """
    return f"{str_field(o, 'titleEn')}\n{item_title(o)}", item_title(o), item_url(o)
