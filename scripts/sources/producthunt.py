"""数据源 producthunt —— Product Hunt 当日热门(GraphQL V2 + Bearer Developer Token)。

items 字段:rank / id / slug / name / tagline / votesCount / commentsCount /
website(PH 跳转链)/ url(PH 产品页)/ createdAt / dailyRank / topics[] /
thumbnailUrl。返回 (items, {});token 走环境变量 PH_TOKEN_ENV(PRODUCT_HUNT_KEY,
不进仓库),缺失/失效抛 RuntimeError 由单源失败语义兜底;
抓取器契约见 sources/__init__.py。
"""

import os
from datetime import datetime
from zoneinfo import ZoneInfo

from .httpio import SESSION, TIMEOUT

# ===== 数据源 6:Product Hunt 当日热门 =====

# PH 主站 www.producthunt.com 套 Cloudflare 强挑战(首页 403 + Just a moment),
# 但 PH 提供 V2 GraphQL API(api.producthunt.com/v2/api/graphql),用 Developer
# Token 走 Bearer 鉴权,稳定不过 CF。Token 走环境变量 PRODUCT_HUNT_KEY(不进仓库),
# 缺失或失效时本源抛错被单源失败跳过,不影响其余源。
PH_GQL_URL = "https://api.producthunt.com/v2/api/graphql"
PH_TOKEN_ENV = "PRODUCT_HUNT_KEY"
# 当日榜单:取当日 PT 0 点后上线的、按 votes 排序的前 20(贴合 PH「Product of the Day」语义)。
PH_QUERY = """
query($first: Int!, $after: DateTime) {
  posts(first: $first, order: VOTES, postedAfter: $after) {
    edges {
      node {
        id
        slug
        name
        tagline
        votesCount
        commentsCount
        website
        url
        createdAt
        dailyRank
        topics(first: 3) { edges { node { name } } }
        thumbnail { url }
      }
    }
  }
}
"""


def _ph_today_pt_start():
    """当日太平洋时间(PT)0 点的 ISO 字符串(如 '2026-08-15T00:00:00-07:00')。

    PH 把每个帖子的 createdAt 规范化到上线日 PT 00:01(夏令时=UTC 07:01,
    冬令时=UTC 08:01),「Product of the Day」也按 PT 自然日排榜,所以
    postedAfter 用 PT 当日 0 点才能在任意批次时刻拿到当前榜单。

    不要改回 UTC 当日 0 点:北京 08:00(=UTC 00:00 整)抓取时,UTC 边界晚于
    当前榜单全部帖子的规范化时间,会把结果过滤成空列表、误报「源站改版」;
    冬令时下新批次 08:01(UTC)才上线,贴近边界的批次同样会拿空
    (本仓库 22:00 批 = UTC 14:00,距边界已远,无此风险)。"""
    pt = ZoneInfo("America/Los_Angeles")
    return datetime.now(pt).replace(
        hour=0, minute=0, second=0, microsecond=0
    ).isoformat()


def fetch_producthunt():
    """
    Product Hunt 当日热门(对齐 App 端归档模式:数据结构供 ProductHuntArchiveRepository 消费)。

    GraphQL V2 + Bearer Developer Token(api.producthunt.com/v2/api/graphql):
      - 查询 posts(first:20, order:VOTES, postedAfter: 当日PT 0点,带时区偏移)
      - 字段:id/slug/name/tagline/votesCount/commentsCount/website/url/createdAt/
        dailyRank/topics[]/thumbnail{url}
    - PRODUCT_HUNT_KEY 缺失 → RuntimeError(被单源失败跳过,index 继承旧 latest)
    - 401/403 → RuntimeError(token 无效/过期,错误信息明确便于发现)

    返回 (items, {}),item 字段对齐 App ProductHunt.kt fromJson。
    """
    token = os.environ.get(PH_TOKEN_ENV)
    if not token:
        raise RuntimeError(
            f"Product Hunt 需要 Developer Token,但环境变量 {PH_TOKEN_ENV} 未设置"
            "(去 https://api.producthunt.com/v2/dashboard 的 API Dashboard 拿)"
        )

    variables = {"first": 20, "after": _ph_today_pt_start()}
    resp = SESSION.post(
        PH_GQL_URL,
        json={"query": PH_QUERY, "variables": variables},
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        timeout=TIMEOUT,
    )
    # 401/403 单独给明确信息(token 问题最常见,便于排查)
    if resp.status_code in (401, 403):
        raise RuntimeError(
            f"Product Hunt token 无效或已过期(HTTP {resp.status_code}),"
            f"请去 API Dashboard 重新生成 Developer Token"
        )
    resp.raise_for_status()
    data = resp.json()

    # GraphQL 错误(schema 改版 / 字段失效)单独报
    if data.get("errors"):
        raise RuntimeError(f"Product Hunt GraphQL 返回错误:{str(data['errors'])[:200]}")

    edges = (((data.get("data") or {}).get("posts")) or {}).get("edges") or []
    items = []
    for idx, edge in enumerate(edges):
        node = edge.get("node") or {}
        pid = node.get("id")
        name = (node.get("name") or "").strip()
        slug = (node.get("slug") or "").strip()
        # id / name 必有(对齐 App 端 ProductHunt.kt:缺则跳过)
        if not pid or not name:
            continue
        topics = []
        for t_edge in (((node.get("topics") or {}).get("edges")) or []):
            tn = ((t_edge.get("node") or {}).get("name") or "").strip()
            if tn:
                topics.append(tn)
        # 产品主图(thumbnail 是 Media 对象,取 .url;列表缩略图用,无则为空)
        thumbnail = node.get("thumbnail") or {}
        thumbnail_url = (thumbnail.get("url") or "").strip() if isinstance(thumbnail, dict) else ""
        # website 是 PH 的跳转链接(含 utm),url 是 PH 产品页;两者都留,App 端优先用 url
        items.append({
            "rank": idx + 1,
            "id": pid,
            "slug": slug,
            "name": name,
            "tagline": (node.get("tagline") or "").strip(),
            "votesCount": node.get("votesCount", 0) or 0,
            "commentsCount": node.get("commentsCount", 0) or 0,
            "website": node.get("website") or "",
            "url": node.get("url") or "",
            "createdAt": node.get("createdAt") or "",
            "dailyRank": node.get("dailyRank", 0) or 0,
            "topics": topics,
            "thumbnailUrl": thumbnail_url,
        })
    return items, {}
