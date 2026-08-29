"""数据源 openai-anthropic-news —— OpenAI x Anthropic 厂商动态(四子源合并)。

OpenAI RSS + Anthropic /news + Claude Blog + Anthropic Engineering 分别抓取
合并,任一子源失败不阻断其余(全部失败才抛异常触发源级重试)。
items 字段:rank / title / url / summary / vendor(OpenAI|Anthropic)/ category /
publishedAt(OpenAI 为 ISO 带时区,Anthropic 三家为纯日期)。
meta:{feedTitle};时间窗口硬过滤 VENDOR_NEWS_MAX_AGE_DAYS 天,窗口内可无新文
→ 正常返回空列表(在 sources.EMPTY_OK_SOURCES 豁免);抓取器契约见 sources/__init__.py。
"""

import re
import sys
from datetime import timedelta

from bs4 import BeautifulSoup

from common import now_cst

from .httpio import fetch_text

# ===== 数据源 9:OpenAI x Anthropic 厂商动态(OpenAI RSS + Anthropic HTML 合并源) =====

# OpenAI RSS + Anthropic HTML 列表页 + Claude Blog + Anthropic Engineering 四子源合并。
# 两家头部 AI 厂商的官方动态,用户最关心的「发版/研究/政策」第一手来源,补齐现有源
# 无厂商一方的缺口。Claude Blog(claude.com/blog)覆盖 Claude 产品公告,Anthropic
# Engineering(anthropic.com/engineering)覆盖技术深度文章——与 follow-builders 思路一致。
OPENAI_RSS_URL = "https://openai.com/news/rss.xml"
ANTHROPIC_NEWS_URL = "https://www.anthropic.com/news"
CLAUDE_BLOG_URL = "https://claude.com/blog"
ANTHROPIC_ENGINEERING_URL = "https://www.anthropic.com/engineering"

# 合并后保留的最新条目数(用户确认:最多 20 条)
VENDOR_NEWS_MAX = 20
# 只保留最近 N 天的条目(用户确认:硬过滤 2 天)。以 CST 当天为基准,纯日期 publishedAt
# 也按日期比较。过滤后整源可能为空(尤其 Claude Blog/Engineering 是月级更新),此时不抛
# 异常,正常返回空列表,index 继承兜底让 App 拿到上次成功快照。
VENDOR_NEWS_MAX_AGE_DAYS = 2

# Anthropic 列表页日期格式(Jul 22, 2026)
_ANTHROPIC_DATE_RE = re.compile(r"^([A-Z][a-z]{2})\s+(\d{1,2}),\s*(\d{4})$")
_ANTHROPIC_MONTHS = {
    "Jan": 1, "Feb": 2, "Mar": 3, "Apr": 4, "May": 5, "Jun": 6,
    "Jul": 7, "Aug": 8, "Sep": 9, "Oct": 10, "Nov": 11, "Dec": 12,
}

# Claude Blog 列表页日期有两种格式:缩写月(Jul 24, 2026,新文)与全月名(June 18, 2026,旧文)。
# 用统一正则同时匹配两种,再在 _CLAUDE_MONTHS 里查(键统一小写)。
_CLAUDE_DATE_RE = re.compile(
    r"^([A-Za-z]+)\s+(\d{1,2}),\s*(\d{4})$",
)
_CLAUDE_MONTHS = {
    # 缩写(对齐 _ANTHROPIC_MONTHS)
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
    # 全月名
    "january": 1, "february": 2, "march": 3, "april": 4, "june": 6,
    "july": 7, "august": 8, "september": 9, "october": 10, "november": 11, "december": 12,
}


def _anthropic_date_to_iso(s):
    """Anthropic 列表页日期 'Jul 22, 2026' → ISO '2026-07-22'。无法解析返回空串。"""
    m = _ANTHROPIC_DATE_RE.match(s.strip())
    if not m:
        return ""
    mon = _ANTHROPIC_MONTHS.get(m.group(1))
    if not mon:
        return ""
    return f"{int(m.group(3)):04d}-{mon:02d}-{int(m.group(2)):02d}"


def _fetch_openai_rss_items():
    """拉 OpenAI RSS,返回 item dict 列表(未编号)。失败抛异常,交由重试机制处理。"""
    from email.utils import parsedate_to_datetime
    xml = fetch_text(
        OPENAI_RSS_URL,
        extra_headers={
            "Accept": "application/rss+xml,application/xml,text/xml",
            "Accept-Language": "en-US,en;q=0.9",
        },
    )
    soup = BeautifulSoup(xml, "xml")
    items = []
    for it in soup.find_all("item"):
        title = it.find("title").get_text(strip=True) if it.find("title") else ""
        link = it.find("link").get_text(strip=True) if it.find("link") else ""
        if not title or not link:
            continue
        desc = it.find("description")
        summary = desc.get_text(strip=True) if desc else ""
        cat = it.find("category")
        category = cat.get_text(strip=True) if cat else ""
        pub = it.find("pubDate")
        pub_text = pub.get_text(strip=True) if pub else ""
        published_at = ""
        if pub_text:
            try:
                dt = parsedate_to_datetime(pub_text)
                if dt is not None:
                    published_at = dt.strftime("%Y-%m-%dT%H:%M:%S%z").replace("+0000", "Z")
            except (TypeError, ValueError):
                pass
        items.append({
            "title": title,
            "url": link,
            "summary": summary,
            "vendor": "OpenAI",
            "category": category,
            "publishedAt": published_at,
        })
    return items


def _fetch_anthropic_html_items():
    """抓 Anthropic 新闻列表页 HTML,返回 item dict 列表(未编号)。

    页面 SSR 渲染,两种卡片格式(均在一个 <a href="/news/..."> 锚点内):
      - 大卡(featured):「Category | Date | Title | Summary」4 段
      - 列表项:         「Date | Category | Title」3 段(无 summary)
    解析策略:按日期正则定位 date 段,而非依赖 Next.js 动态 class(不稳定)。
    失败抛异常,交由重试机制处理。
    """
    html = fetch_text(
        ANTHROPIC_NEWS_URL,
        extra_headers={
            "Accept": "text/html,application/xhtml+xml",
            "Accept-Language": "en-US,en;q=0.9",
        },
    )
    soup = BeautifulSoup(html, "lxml")
    items = []
    for el in soup.select('a[href^="/news/"]'):
        href = (el.get("href") or "").strip().split("?")[0].split("#")[0]
        # 跳过落地页自身(/news)与无 slug 的导航项
        slug = href.removeprefix("/news/").strip("/")
        if not slug or slug == "hard-questions":
            continue

        parts = [p.strip() for p in el.get_text(" | ", strip=True).split("|")]
        cat = date_s = title = summary = ""
        # 列表项:Date | Category | Title [ | Summary? ]
        if len(parts) >= 3 and _ANTHROPIC_DATE_RE.match(parts[0]):
            date_s, cat = parts[0], parts[1]
            title = parts[2]
            summary = " | ".join(parts[3:]) if len(parts) > 3 else ""
        # 大卡:Category | Date | Title | Summary
        elif len(parts) >= 4 and _ANTHROPIC_DATE_RE.match(parts[1]):
            cat, date_s = parts[0], parts[1]
            title = parts[2]
            summary = " | ".join(parts[3:])
        else:
            continue

        if not title:
            continue
        items.append({
            "title": title,
            "url": f"https://www.anthropic.com{href}",
            "summary": summary,
            "vendor": "Anthropic",
            "category": cat,
            "publishedAt": _anthropic_date_to_iso(date_s),
        })
    return items


def _claude_date_to_iso(s):
    """Claude Blog 列表页日期 → ISO 'YYYY-MM-DD'。无法解析返回空串。

    支持两种格式:缩写月(Jul 24, 2026,近期文章)与全月名(June 18, 2026,历史文章)。
    月名统一小写后查 _CLAUDE_MONTHS(缩写 + 全月名都收录)。
    """
    m = _CLAUDE_DATE_RE.match(s.strip())
    if not m:
        return ""
    mon = _CLAUDE_MONTHS.get(m.group(1).lower())
    if not mon:
        return ""
    return f"{int(m.group(3)):04d}-{mon:02d}-{int(m.group(2)):02d}"


def _fetch_claude_blog_items():
    """抓 Claude Blog 列表页(claude.com/blog,Webflow CMS),返回 item dict 列表(未编号)。

    Claude Blog 是 Anthropic 旗下 Claude 产品的官方公告入口(区别于 anthropic.com/news 的
    公司级新闻),覆盖 artifacts / agents / 工具更新等产品向内容。主列表卡片选择器
    .blog_cms_item(实测 30 张,覆盖近期与历史全部文章;另有 .marquee_cms_blog_list_item
    是顶部 featured 区,只覆盖部分,不要用)。每张含 <a href="/blog/...">(标题取自其
    data-cta-copy 属性,最可靠)+ .u-text-style-caption 日期 + .w-dyn-item 分类。卡片不带摘要。

    日期格式有两种:近期文章用缩写月(Jul 24, 2026),历史文章用全月名(June 18, 2026),
    _claude_date_to_iso 两种都支持。

    vendor 用 'Anthropic'(Claude 是 Anthropic 旗下产品,保持厂商归属一致),用 category
    'Claude' 区分子频道——这样 App 端 vendor 枚举与 ai_summary 的 system prompt 均无需改动。
    失败抛异常,交由重试机制处理。
    """
    html = fetch_text(
        CLAUDE_BLOG_URL,
        extra_headers={
            "Accept": "text/html,application/xhtml+xml",
            "Accept-Language": "en-US,en;q=0.9",
        },
    )
    soup = BeautifulSoup(html, "lxml")
    items = []
    seen_slugs = set()
    for card in soup.select(".blog_cms_item"):
        a = card.select_one('a[href^="/blog/"]')
        if not a:
            continue
        href = (a.get("href") or "").strip().split("?")[0].split("#")[0]
        slug = href.removeprefix("/blog/").strip("/")
        if not slug or slug in seen_slugs:
            continue
        seen_slugs.add(slug)

        # 标题优先取 a 标签的 data-cta-copy 属性(Webflow 为分析埋点埋的完整标题,最可靠),
        # 回退到 .card_blog_title 的文本(部分卡片可能没有该属性)。
        title = (a.get("data-cta-copy") or "").strip()
        if not title:
            t_el = card.select_one(".card_blog_title")
            title = t_el.get_text(strip=True) if t_el else ""
        if not title:
            continue

        # 日期在 .u-text-style-caption(如 'Jul 24, 2026' / 'June 18, 2026')
        date_el = card.select_one(".u-text-style-caption")
        date_s = date_el.get_text(strip=True) if date_el else ""

        # 分类在卡片末尾的 .w-dyn-item(如 'Enterprise AI' / 'Claude Code'),作为 category 补充
        cat_els = card.select(".w-dyn-item")
        category = cat_els[-1].get_text(strip=True) if cat_els else ""

        items.append({
            "title": title,
            "url": f"https://claude.com{href}",
            "summary": "",  # Webflow 列表卡片不带摘要
            "vendor": "Anthropic",
            "category": category or "Claude",
            "publishedAt": _claude_date_to_iso(date_s),
        })
    return items


def _fetch_anthropic_engineering_items():
    """抓 Anthropic Engineering 列表页(anthropic.com/engineering,Next.js SSR + Sanity),
    返回 item dict 列表(未编号)。

    工程博客,覆盖架构/部署/容器化等技术深度文章,与 anthropic.com/news 的产品新闻互补。
    与 /news 同栈(Next.js App Router + Sanity CMS),HTML 卡片锚点 a[href^='/engineering/']。
    实测页面有两种卡片:
      - featured 大卡(首位):'Featured | <标题> | <摘要>' 三段,首段是字面量 'Featured'
      - 普通列表项:'<标题> | <日期>' 两段,日期为 Anthropic 缩写月(Apr 23, 2026)

    解析策略:按段数区分。featured 卡(parts[0]=='Featured')取 parts[1] 当标题、parts[2]
    当摘要;普通卡取 parts[0] 当标题、用日期正则在 parts 里找日期。

    日期复用 _ANTHROPIC_DATE_RE(Anthropic 缩写月格式)。vendor 'Anthropic',
    category 'Engineering' 区分子频道。失败抛异常,交由重试机制处理。
    """
    html = fetch_text(
        ANTHROPIC_ENGINEERING_URL,
        extra_headers={
            "Accept": "text/html,application/xhtml+xml",
            "Accept-Language": "en-US,en;q=0.9",
        },
    )
    soup = BeautifulSoup(html, "lxml")
    items = []
    seen_slugs = set()
    for el in soup.select('a[href^="/engineering/"]'):
        href = (el.get("href") or "").strip().split("?")[0].split("#")[0]
        slug = href.removeprefix("/engineering/").strip("/")
        # 跳过落地页自身与无 slug 的导航项
        if not slug or slug in seen_slugs:
            continue
        seen_slugs.add(slug)

        parts = [p.strip() for p in el.get_text(" | ", strip=True).split("|") if p.strip()]
        title = summary = date_s = ""
        if parts and parts[0].lower() == "featured":
            # featured 大卡:'Featured | <标题> | <摘要>'
            title = parts[1] if len(parts) > 1 else ""
            summary = parts[2] if len(parts) > 2 else ""
        else:
            # 普通列表项:标题 + 日期(日期可能在末段)
            # 找到日期段,其余段拼回当标题
            date_idx = -1
            for i, p in enumerate(parts):
                if _ANTHROPIC_DATE_RE.match(p) or _looks_like_iso_date(p):
                    date_idx = i
                    break
            if date_idx >= 0:
                date_s = parts[date_idx]
                title = " | ".join(parts[:date_idx]) if date_idx > 0 else (parts[0] if parts else "")
            else:
                # 没找到日期,整段拼回当标题
                title = " | ".join(parts)

        if not title:
            continue

        items.append({
            "title": title,
            "url": f"https://www.anthropic.com{href}",
            "summary": summary,
            "vendor": "Anthropic",
            "category": "Engineering",
            "publishedAt": _parse_engineering_date(date_s),
        })
    return items


def _looks_like_iso_date(s):
    """快速判断字符串是否形如 YYYY-MM-DD。"""
    return bool(re.match(r"^\d{4}-\d{2}-\d{2}$", s.strip()))


def _parse_engineering_date(s):
    """工程站日期解析:先试 Anthropic 缩写月(Jul 22, 2026),再试 ISO 直通(2026-05-25)。

    两者都失败返回空串(publishedAt 留空,该条排到列表末尾)。
    """
    s = s.strip()
    if not s:
        return ""
    iso = _anthropic_date_to_iso(s)
    if iso:
        return iso
    return s if _looks_like_iso_date(s) else ""


def fetch_openai_anthropic_news():
    """
    合并源:OpenAI RSS + Anthropic /news + Claude Blog + Anthropic Engineering 四子源。
    对齐 App 端 OpenAiAnthropicNews.fromJson 与 OverviewRepository 的 when 分支。

    四家无官方聚合 feed,此处分别抓取后合并;任一失败不影响其他家(各自独立 try,
    失败的记 stderr 警告后跳过,全部失败才抛异常触发源级重试)。

    vendor 保持二元 {OpenAI, Anthropic}:Claude Blog / Engineering 都是 Anthropic 旗下,
    用 category 'Claude' / 'Engineering' 区分子频道——这样 App 端 vendor 枚举与
    ai_summary 的 system prompt 均无需改动。

    合并后按 publishedAt 倒序、取最新 20 条,统一编号 rank(对齐用户确认的上限)。
    时间窗口:只保留最近 VENDOR_NEWS_MAX_AGE_DAYS 天(用户确认硬过滤 2 天)的条目,
    无 publishedAt 或超期的丢弃——对齐用户「只看最新动态」的诉求。过滤后整源可能
    为空(尤其 Claude Blog/Engineering 月级更新),此时正常返回空列表,index 继承兜底。

    字段命名 camelCase,对齐 App 端 model 与总览页 ItemView 映射:
      rank / title / url / summary / vendor / category / publishedAt
    """
    merged = []
    errors = []
    for label, fetcher in (("OpenAI",        _fetch_openai_rss_items),
                           ("Anthropic",     _fetch_anthropic_html_items),
                           ("Claude Blog",   _fetch_claude_blog_items),
                           ("Anthropic Eng", _fetch_anthropic_engineering_items)):
        try:
            merged.extend(fetcher())
        except Exception as e:  # 单家失败不阻断其他家
            print(f"[WARN] {label} 抓取失败,跳过:{type(e).__name__}: {e}", file=sys.stderr)
            errors.append(label)

    if not merged:
        # 全部失败 → 抛异常让 fetch_with_retry 重试(最终落盘走 index 继承兜底)
        raise RuntimeError(f"四个子源均抓取失败:{', '.join(errors) or '未知错误'}")

    # 去重:key = vendor|slug,防跨子源 slug 撞库(如 Claude Blog 与 Anthropic /news 可能共享
    # 同一文章的 slug);同 vendor 内重复 slug 也合并。用 vendor 前缀而非纯 slug 是为了
    # 极端情况下不同厂商撞通用 slug(如 'introducing-codex')不被误判重复。
    seen_keys = set()
    deduped = []
    for it in merged:
        slug = it["url"].rstrip("/").rsplit("/", 1)[-1]
        key = f'{it.get("vendor", "")}|{slug}'
        if key in seen_keys:
            continue
        seen_keys.add(key)
        deduped.append(it)

    # 时间窗口过滤:只保留最近 VENDOR_NEWS_MAX_AGE_DAYS 天的条目。
    # publishedAt 有两种格式:OpenAI 是 ISO 带时区(2026-07-23T00:00:00Z),
    # Anthropic 三家是纯日期(2026-07-24)。统一取前 10 位 YYYY-MM-DD 与 cutoff 日期串比较——
    # 字典序 == 时间序,无需解析时区。无 publishedAt 或早于 cutoff 的丢弃(硬过滤)。
    cutoff_date = (now_cst() - timedelta(days=VENDOR_NEWS_MAX_AGE_DAYS)).strftime("%Y-%m-%d")
    filtered = []
    for it in deduped:
        pub = (it.get("publishedAt") or "")[:10]
        if pub and pub >= cutoff_date:
            filtered.append(it)
    dropped = len(deduped) - len(filtered)
    if dropped > 0:
        print(f"[INFO] openai-anthropic-news 丢弃 {dropped} 条超过 {VENDOR_NEWS_MAX_AGE_DAYS} 天的旧条目",
              file=sys.stderr)
    deduped = filtered

    # 按 publishedAt 倒序(过滤后全有日期,直接比较即可)
    deduped.sort(key=lambda x: x.get("publishedAt") or "", reverse=True)

    # 取最新 20 条,统一编号 rank
    top = deduped[:VENDOR_NEWS_MAX]
    items = []
    for idx, it in enumerate(top):
        items.append({
            "rank": idx + 1,
            "title": it["title"],
            "url": it["url"],
            "summary": it.get("summary", ""),
            "vendor": it.get("vendor", ""),
            "category": it.get("category", ""),
            "publishedAt": it.get("publishedAt", ""),
        })
    return items, {"feedTitle": "OpenAI x Anthropic 官方动态"}
