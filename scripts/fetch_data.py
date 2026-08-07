#!/usr/bin/env python3
"""
AI News Hub「Hub」tab 浏览区域数据抓取脚本。

复刻 App 端的抓取逻辑(见 app/src/main/java/com/peng/ainewshub/data/ 下的各
Repository 与对应 model 类),把 9 个数据源解析成 JSON 落盘:

  - hackernews           HackerNews Top Stories(两步拉取,Firebase API)
  - github-trending      GitHub Trending 仓库(HTML 抓取)
  - linuxdo              LinuxDo 热榜(Discourse JSON)
  - stormzhang-ai        stormzhang AI 资讯(HTML 抓取)
  - huggingface-papers   HuggingFace Trending Papers(HTML 抓取)
  - producthunt          Product Hunt 当日热门(GraphQL API,需 PRODUCT_HUNT_KEY)
  - rundown-ai           The Rundown AI newsletter(beehiiv 首页文章卡片墙,HTML 抓取)
  - aihot-featured       AIHot 精选 TOP20(第三方服务 aihot.virxact.com 公开 API,仅供摘要卡消费)
  - openai-anthropic-news OpenAI x Anthropic 厂商动态(OpenAI RSS + Anthropic HTML 合并源)

输出目录结构:
  <out-dir>/<source>/<YYYY-MM-DD>/<HH-MM>-data.json
  <out-dir>/index.json  顶层 latest(各源最新快照指针)+ history(按日期寻址的
                        历史索引,每天指向当日最后一次快照;每源保留最近 31 天
                        且不早于 HISTORY_START_DATE=2026-07-18)

日期/时间统一用北京时间(UTC+8);CI 里设 TZ=Asia/Shanghai 即可。

失败策略(需求 a):
  - 每个源独立重试,最多 3 次(间隔 2s/4s);3 次全败才记失败、跳过,其余源照常落盘。
  - 失败源的 index.json latest 指针从上一次 index.json 继承(--previous-index-url,
    默认 gitcode 数据仓库),保证客户端永远拿到有效数据。可加 --no-previous-index 关闭。
  - 只要 ≥1 个源成功,退出码 0;全部失败才非 0。

AI 总结(需求 c):
  - 每源抓完调 ai_summary.summarize_source 生成简体中文要点,写入快照顶层 `ai_summary`。
  - 总结 8 个稳定源(hackernews/github-trending/huggingface-papers/stormzhang-ai/
    producthunt/rundown-ai/aihot-featured/openai-anthropic-news),linuxdo 不做(对齐 App)。AI 调用失败仅 warn,不阻断落盘。
  - 需 3 个 AI 环境变量(AI_NEWS_HUB_AI_BASE_URL/_MODEL/_API_KEY)齐全;缺失则跳过总结。
    加 --no-summary 可显式跳过(本地调试用)。

用法:
  python3 scripts/fetch_data.py --out-dir /tmp/aihot-data-test
  python3 scripts/fetch_data.py --out-dir out --only hackernews,linuxdo
  python3 scripts/fetch_data.py --out-dir out --no-summary --no-previous-index  # 本地干跑
"""

import argparse
import calendar
import json
import os
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta

import requests
from bs4 import BeautifulSoup

# AI 总结:每源抓完调一次,失败不阻断(对齐 App SummaryRepository)
import ai_summary
import overview_summary


# ===== 全局:对齐 App 端 OkHttp 配置 + 浏览器 UA(避免被 nginx/CF 403) =====

UA = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Mobile Safari/537.36"
)

# 对齐 App:connectTimeout 15s, readTimeout 20s, followRedirects(true)
SESSION = requests.Session()
SESSION.headers.update({"User-Agent": UA})
TIMEOUT = (15, 20)

# 北京时间(UTC+8)—— App 端虽未显式设时区,但文件名用本地时间存历史快照更直观
CST = timezone(timedelta(hours=8))


def now_cst():
    """当前北京时间(GitHub Actions 设了 TZ=Asia/Shanghai 时与系统时间一致)。"""
    return datetime.now(CST)


def fetch_text(url, extra_headers=None, expect_json=False):
    """
    GET 一个 URL,返回响应正文文本。

    带 Cloudflare 挑战页检测:返回正文含 "Just a moment" 或(期望 JSON 时)
    以 '<' 开头,说明被 CF 拦截,抛 RuntimeError 而非让后续解析报含糊错误。
    对齐 App 端各 Repository 的 CF 检测套路。
    """
    headers = dict(extra_headers or {})
    resp = SESSION.get(url, headers=headers, timeout=TIMEOUT, allow_redirects=True)
    resp.raise_for_status()
    text = resp.text or ""
    if "Just a moment" in text:
        raise RuntimeError("被 Cloudflare 拦截,请稍后重试")
    if expect_json:
        stripped = text.lstrip()
        if stripped.startswith("<"):
            raise RuntimeError("被 Cloudflare 拦截(返回 HTML 而非 JSON)")
    return text


# ===== 通用小工具 =====

def parse_count(s):
    """把 '64,846' / '' / None 统一解析成 int;无法解析返回 0。
    对齐 TrendingRepo.kt 的 parseCount()。"""
    if not s:
        return 0
    return int(s.replace(",", "").strip()) if s.replace(",", "").strip().isdigit() else 0


def strip_html(s):
    """粗略去 HTML 标签(对齐 HtmlUtil.stripHtml)。
    LinuxDo excerpt 是 HTML 片段,存 JSON 前清理成纯文本更易消费。"""
    if not s:
        return ""
    return re.sub(r"<[^>]+>", "", s).replace("&nbsp;", " ").strip()


def parse_iso_to_ms(iso):
    """解析 ISO 8601(如 '2026-07-13T04:28:29.805Z')为毫秒;失败返回 0。
    对齐 LinuxDoTopic.kt 的 parseIsoMillis。"""
    if not iso or not iso.strip():
        return 0
    for fmt in ("%Y-%m-%dT%H:%M:%S.%fZ", "%Y-%m-%dT%H:%M:%SZ"):
        try:
            dt = datetime.strptime(iso, fmt).replace(tzinfo=timezone.utc)
            return int(dt.timestamp() * 1000)
        except ValueError:
            continue
    return 0


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
    soup = BeautifulSoup(html, "html.parser")
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


# ===== 数据源 3:LinuxDo 热榜 =====
# 特殊:linux.do 套 Cloudflare 强挑战(带 Turnstile 的 cf-mitigated: challenge),
# 普通.requests/curl_cffi 都会被 403(实测所有 Chrome 指纹变种均被拦,首页都进不去)。
# 这里用 Playwright 跑真 Chromium 内核过 CF:先访问首页让 CF 认它是真浏览器,
# 再用页面内 fetch 请求 API(自动带 _cfuvid 等 cookie)。本地实测能直接 200 拿到 JSON。
# Playwright 没装时 _fetch_linuxdo_raw 抛 RuntimeError,被主流程当单源失败跳过,
# 不影响其余 4 个源。

def _fetch_linuxdo_raw():
    """用 Playwright(真 Chromium)过 CF 拿 linux.do hot.json 原文。
    返回 JSON 字符串。Playwright/浏览器未就绪时抛 RuntimeError。"""
    try:
        from playwright.sync_api import sync_playwright
    except ImportError as e:
        raise RuntimeError("LinuxDo 需要 Playwright 过 CF,但未安装(见 requirements.txt)") from e

    api_url = "https://linux.do/c/develop/4/l/hot.json"
    with sync_playwright() as p:
        try:
            browser = p.chromium.launch(headless=True)
        except Exception as e:
            raise RuntimeError(
                "Playwright Chromium 未下载,请先跑 `python -m playwright install chromium`"
            ) from e
        try:
            context = browser.new_context(
                user_agent="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                           "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                locale="zh-CN",
            )
            page = context.new_page()
            # 1) 先访问首页:让 CF 跑完挑战 JS、认它是真浏览器,落地 _cfuvid cookie。
            page.goto("https://linux.do/", wait_until="domcontentloaded", timeout=30000)
            # 等"Just a moment..."挑战页(若有)自动跳转完成,最多等 20s。
            for _ in range(20):
                time.sleep(1)
                if "just a moment" not in page.title().lower():
                    break
            # 2) 用页面内 fetch 请求 API:自动复用浏览器 cookie + 指纹,过 CF。
            result = page.evaluate("""async (url) => {
                const r = await fetch(url, {headers: {'Accept': 'application/json'}});
                return {status: r.status, ct: r.headers.get('content-type'), text: await r.text()};
            }""", api_url)
            status = result.get("status")
            ct = result.get("ct") or ""
            text = result.get("text") or ""
            if status != 200 or "json" not in ct:
                raise RuntimeError(
                    f"LinuxDo 仍被 CF 拦截(HTTP {status}, {ct});"
                    f"body 前 80 字符: {text[:80]!r}"
                )
            return text
        finally:
            browser.close()


def _resolve_avatar(template):
    """补全头像 URL(对齐 LinuxDoTopic.resolveAvatar)。
    {size}→48;相对路径补 https://linux.do 前缀。"""
    if not template:
        return ""
    sized = template.replace("{size}", "48")
    if sized.startswith("https://") or sized.startswith("http://"):
        return sized
    if sized.startswith("//"):
        return "https:" + sized
    if sized.startswith("/"):
        return "https://linux.do" + sized
    return sized


def fetch_linuxdo():
    """
    Discourse JSON(对齐 LinuxDoHotRepository + LinuxDoTopic.fromJson)。
    users[] 建索引,topic_list.topics[] 解析。置顶帖 rank=0。

    数据获取走 Playwright 过 CF(见 _fetch_linuxdo_raw);字段解析逻辑与 App 端一致。
    """
    raw = _fetch_linuxdo_raw()
    root = json.loads(raw)
    users = root.get("users") or []
    users_by_id = {u.get("id"): u for u in users if isinstance(u, dict)}

    topics = (((root.get("topic_list") or {}).get("topics")) or [])
    items = []
    rank = 0
    for t in topics:
        if not isinstance(t, dict):
            continue
        tid = t.get("id")
        if not tid or tid <= 0:
            continue
        title = (t.get("title") or "").strip() or (t.get("fancy_title") or "").strip()
        if not title:
            continue

        is_pinned = bool(t.get("pinned_globally")) or bool(t.get("pinned"))
        r = 0 if is_pinned else (rank := rank + 1)

        # 作者:posters[0] 中 description 含"原始发帖"的 user_id;否则取 posters 第一个
        op_user_id = -1
        posters = t.get("posters") or []
        if posters:
            op = next((p for p in posters if "原始发帖" in (p.get("description") or "")), None)
            if op:
                op_user_id = op.get("user_id", -1)
            elif isinstance(posters[0], dict):
                op_user_id = posters[0].get("user_id", -1)
        user = users_by_id.get(op_user_id) or {}
        author_name = (user.get("name") or "").strip() or (user.get("username") or "")
        avatar_url = _resolve_avatar(user.get("avatar_template") or "")

        excerpt = strip_html(t.get("excerpt") or "")
        tags = []
        for tag in (t.get("tags") or []):
            if isinstance(tag, dict):
                n = (tag.get("name") or "").strip()
                if n:
                    tags.append(n)
        tags = tags[:2]

        items.append({
            "rank": r,
            "title": title,
            "url": f"https://linux.do/t/topic/{tid}",
            "excerpt": excerpt,
            "authorName": author_name,
            "avatarUrl": avatar_url,
            "views": t.get("views", 0) or 0,
            "replyCount": t.get("reply_count", 0) or 0,
            "likeCount": t.get("like_count", 0) or 0,
            "tags": tags,
            "createdAtMs": parse_iso_to_ms(t.get("created_at") or ""),
            "pinned": is_pinned,
            "closed": bool(t.get("closed")),
        })
    return items, {}


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
    soup = BeautifulSoup(html, "html.parser")
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


# ===== 数据源 4.5:The Rundown AI(beehiiv 托管的 AI newsletter) =====

def _split_rundown_card_text(text):
    """
    拆 The Rundown AI 首页卡片的合并文本「标题 | PLUS: 副标题 | 作者, +N」。

    beehiiv 首页卡的 get_text(' | ') 会把三段挤成一个字符串,需逆向拆分:
      - 标题:第一段(PLUS 之前)
      - subtitle:PLUS: 之后(到作者段前)
      - authors:最后一段(通常是「姓名, +N」格式)

    返回 (title, subtitle, authors)。任何一段缺失返回空串。
    """
    if not text:
        return "", "", ""
    # 按 ' | ' 切,首段恒为标题
    parts = [p.strip() for p in text.split(" | ") if p.strip()]
    if not parts:
        return "", "", ""
    title = parts[0]
    subtitle = ""
    authors = ""
    # PLUS 段:副标题
    for p in parts[1:]:
        if p.upper().startswith("PLUS"):
            subtitle = re.sub(r"^PLUS:\s*", "", p, flags=re.IGNORECASE).strip()
        else:
            # 非标题、非 PLUS 的最后一段视为作者(形如「Zach Mink, +4」)
            authors = p
    return title, subtitle, authors


def fetch_rundown_ai():
    """
    HTML 抓取 https://www.therundown.ai 首页文章卡片墙(对齐 RundownAiRepository +
    RundownAiArticle.fromJson)。选择器:a[href^="/p/"]。

    The Rundown AI 是 beehiiv 托管的 AI 日更 newsletter(每日 1 篇大综合,含 5-8 个
    AI 要点)。首页固定展示约 16 篇近况 newsletter 卡片,每张卡含:
      - 标题(主)
      - PLUS: 副标题(辅,即今日次要点)
      - 作者(如「Zach Mink, +4」)
      - 封面图(beehiiv cdn-cgi 图,排除作者头像 width=256 那张)

    决策(用户确认):
      - 只抓列表元数据,不抓正文(对齐 stormzhang)
      - 无 token、无 paywall、robots.txt 允许(只禁 /login)
      - 列表页无日期字段,故不返回 meta.pageDate

    字段命名 camelCase,对齐 App 端 RundownAiArticle.kt 的 fromJson。
    """
    html = fetch_text(
        "https://www.therundown.ai/",
        extra_headers={
            "Accept": "text/html,application/xhtml+xml",
            "Accept-Language": "en-US,en;q=0.9",
        },
    )
    soup = BeautifulSoup(html, "html.parser")
    items = []
    seen_slugs = set()
    rank = 0
    for el in soup.select('a[href^="/p/"]'):
        href = (el.get("href") or "").strip()
        # 提取 slug(可能带 query/anchor,先剥离)
        slug = href.split("?")[0].split("#")[0].removeprefix("/p/").strip("/")
        if not slug or slug in seen_slugs:
            continue

        # 卡内文本三段:标题 | PLUS: 副标题 | 作者
        raw_text = el.get_text(" | ", strip=True)
        title, subtitle, authors = _split_rundown_card_text(raw_text)
        if not title:
            continue

        # 封面图:首张非作者头像(width=256 是作者头像特征)的 beehiiv cdn-cgi 图
        cover_url = ""
        for img in el.find_all("img"):
            src = (img.get("src") or "").strip()
            if not src or "width=256" in src:
                continue
            if "beehiiv.com/cdn-cgi/image" in src or "beehiiv-images-production" in src:
                cover_url = src
                break

        rank += 1
        seen_slugs.add(slug)
        items.append({
            "rank": rank,
            "slug": slug,
            "url": f"https://www.therundown.ai/p/{slug}",
            "title": title,
            "subtitle": subtitle,
            "authors": authors,
            "coverUrl": cover_url,
        })

    return items, {}


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
    soup = BeautifulSoup(html, "html.parser")
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


# ===== 数据源 6:Product Hunt 当日热门 =====

# PH 主站 www.producthunt.com 套 Cloudflare 强挑战(首页 403 + Just a moment),
# 但 PH 提供 V2 GraphQL API(api.producthunt.com/v2/api/graphql),用 Developer
# Token 走 Bearer 鉴权,稳定不过 CF。Token 走环境变量 PRODUCT_HUNT_KEY(不进仓库),
# 缺失或失效时本源抛错被单源失败跳过,不影响其余源。
PH_GQL_URL = "https://api.producthunt.com/v2/api/graphql"
PH_TOKEN_ENV = "PRODUCT_HUNT_KEY"
# 当日榜单:取当日 UTC 0 点后上线的、按 votes 排序的前 20(贴合 PH「Product of the Day」语义)。
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


def _ph_today_utc_start():
    """当日 UTC 0 点的 ISO 字符串(如 '2026-07-18T00:00:00Z')。
    PH 按太平洋时间排「Product of the Day」,但 API 的 postedAfter 用 UTC 最直观,
    且北京 07:00/14:00 抓取时 UTC 当天已覆盖 PH 当日榜单。"""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT00:00:00Z")


def fetch_producthunt():
    """
    Product Hunt 当日热门(对齐 App 端归档模式:数据结构供 ProductHuntArchiveRepository 消费)。

    GraphQL V2 + Bearer Developer Token(api.producthunt.com/v2/api/graphql):
      - 查询 posts(first:20, order:VOTES, postedAfter: 今日UTC0点)
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

    variables = {"first": 20, "after": _ph_today_utc_start()}
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
    soup = BeautifulSoup(html, "html.parser")
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
    soup = BeautifulSoup(html, "html.parser")
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
    soup = BeautifulSoup(html, "html.parser")
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


# ===== 数据源注册表:name → 抓取函数 =====

SOURCES = {
    "hackernews": fetch_hackernews,
    "github-trending": fetch_github_trending,
    "linuxdo": fetch_linuxdo,
    "stormzhang-ai": fetch_stormzhang_ai,
    "huggingface-papers": fetch_huggingface_papers,
    "producthunt": fetch_producthunt,
    "rundown-ai": fetch_rundown_ai,
    "aihot-featured": fetch_aihot_featured,
    "openai-anthropic-news": fetch_openai_anthropic_news,
}

# 单源抓取最大重试次数(需求 a:失败重试,最多 3 次)。首次 + 2 次重试。
FETCH_MAX_ATTEMPTS = 3


def fetch_with_retry(name, fn, limit_hn=None):
    """
    包装单源抓取,失败重试最多 FETCH_MAX_ATTEMPTS 次(需求 a)。

    每次失败间指数退避:2s / 4s。全 3 次都败才抛最后一个异常
    (交给 main 的 try/except 记 fail,并触发「保留旧 latest」逻辑)。

    HackerNews 签名带 limit,其余源无参,这里按 name 分派。
    """
    last_exc = None
    for attempt in range(1, FETCH_MAX_ATTEMPTS + 1):
        try:
            if name == "hackernews" and limit_hn is not None:
                return fn(limit=limit_hn)
            return fn()
        except Exception as e:
            last_exc = e
            if attempt < FETCH_MAX_ATTEMPTS:
                wait = 2 ** attempt  # 2s, 4s
                print(f"[RETRY] {name:<20} 第 {attempt}/{FETCH_MAX_ATTEMPTS} 次失败,"
                      f"{wait}s 后重试:{type(e).__name__}: {e}", file=sys.stderr)
                time.sleep(wait)
    raise last_exc


def write_snapshot(out_dir, source_name, items, meta, now, ai_summary_v2=None):
    """
    落盘单源快照:<out-dir>/<source>/<YYYY-MM-DD>/<HH-MM>-data.json。
    顶层结构:source / fetched_at(ISO CST)/ fetched_at_ms / count / items / meta / ai_summary_v2?。

    ai_summary_v2:AI 摘要对象列表(list[dict],每项含 title + desc),非空时写入顶层
    `ai_summary_v2` 字段。调用 AI 失败或源不支持(linuxdo)时传 None,该字段直接省略。
    App 端同时兼容旧的纯文本 `ai_summary`(历史快照),新快照只写 v2。
    """
    date_str = now.strftime("%Y-%m-%d")
    time_str = now.strftime("%H-%M")
    dir_path = os.path.join(out_dir, source_name, date_str)
    os.makedirs(dir_path, exist_ok=True)
    file_path = os.path.join(dir_path, f"{time_str}-data.json")
    payload = {
        "source": source_name,
        "fetched_at": now.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "fetched_at_ms": int(now.timestamp() * 1000),
        "count": len(items),
        "items": items,
    }
    # 把抓取附带元信息(如 stormzhang 的 pageDate)拍扁进顶层,方便消费
    for k, v in (meta or {}).items():
        payload.setdefault(k, v)
    # AI 总结:非空才写,保持与无总结时的结构兼容
    if ai_summary_v2:
        payload["ai_summary_v2"] = ai_summary_v2
    with open(file_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    return file_path


def _iter_snapshots(out_dir, source_name):
    """
    遍历某源目录下所有合法 <YYYY-MM-DD>/<HH-MM>-data.json,yield (date, time) 元组。

    只接受定宽格式(日期目录形如 2026-07-15、文件名形如 08-00-data.json),
    其余目录/文件一律跳过。latest 指针(_scan_latest)与历史索引(_scan_history)
    共用这一套扫描与过滤逻辑。
    """
    src_root = os.path.join(out_dir, source_name)
    if not os.path.isdir(src_root):
        return
    for date_dir in os.listdir(src_root):
        full_date_dir = os.path.join(src_root, date_dir)
        if not os.path.isdir(full_date_dir):
            continue
        # 日期目录名形如 2026-07-15,只接受这种格式
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date_dir):
            continue
        for fname in os.listdir(full_date_dir):
            # 文件名形如 08-00-data.json,只接受这种格式
            m = re.fullmatch(r"(\d{2}-\d{2})-data\.json", fname)
            if not m:
                continue
            yield date_dir, m.group(1)


def _scan_latest(out_dir, source_name):
    """
    扫描某源目录下所有 <YYYY-MM-DD>/<HH-MM>-data.json,返回最新的相对路径
    (相对于源目录,如 "2026-07-15/00-44-data.json")。没有任何文件返回 None。

    用于 index.json 的 latest 指针:跨天也能正确取到最新成功快照
    (即使今天这次失败,昨天的仍是最新的)。

    date / time 都用字典序排:YYYY-MM-DD 与 HH-MM 定宽格式下,字典序 == 时间序。
    """
    best = None  # (date, time) 元组,取最大
    for date_str, time_str in _iter_snapshots(out_dir, source_name):
        key = (date_str, time_str)
        if best is None or key > best:
            best = key
    if best is None:
        return None
    return f"{best[0]}/{best[1]}-data.json"


def _scan_history(out_dir, source_name):
    """
    扫描某源目录下所有合法 <YYYY-MM-DD>/<HH-MM>-data.json,返回 {date: relpath}
    字典:每个日期取当天字典序最大(=最后一次)的快照,relpath 相对源目录
    (如 "2026-07-15/00-44-data.json")。没有任何文件返回 {}。

    用于 index.json 的 history 索引(App「历史摘要」按日期寻址当日快照)。
    与 _scan_latest 同一套扫描逻辑(_iter_snapshots)与字典序 == 时间序前提。
    """
    last_of_day = {}  # date -> 当天最大 time
    for date_str, time_str in _iter_snapshots(out_dir, source_name):
        if date_str not in last_of_day or time_str > last_of_day[date_str]:
            last_of_day[date_str] = time_str
    return {d: f"{d}/{t}-data.json" for d, t in last_of_day.items()}


# 上一次 index.json 的拉取地址(需求 a:失败源继承旧 latest 指针)。
# 用 gitcode 官方 API(公开仓库匿名可读,稳定;raw 直链背后华为云 WAF 易 403)。
DEFAULT_PREVIOUS_INDEX_URL = (
    "https://api.gitcode.com/api/v5/repos/peng1818/AI-News-Hub-Data"
    "/raw/index.json?ref=news-hub-data"
)


def load_previous_index(url):
    """
    拉取上一次的 index.json,返回 (latest, history, previous_overview) 三元组:
      latest            : source → 相对路径,失败源继承旧 latest 指针用;
      history           : source → {date: 相对路径},历史索引的合并基线;
      previous_overview : 上次的 latest_overview dict(本次总览生成失败时继承用)。
    任一字段缺失或拉取失败时,对应项返回 {} / {} / None。

    需求 a:CI 是干净跑(每次只有本次产物),某源本次抓取失败时本地目录不存在,
    _scan_latest 会返回 None,导致 index.json 丢掉该源 —— 与 docs 承诺的
    「保留旧指向」不符。这里从 gitcode 拉上一次 index.json,失败源继承其指针,
    让客户端永远能拿到有效数据。history 同理:CI 本地只有当天快照,历史日期索引
    必须从旧 index 继承合并,否则 history 里永远只剩当天一条。
    previous_overview 同样:本次总览 AI 生成失败时,继承上次的 latest_overview,
    避免一次失败导致 App 端总览空掉(对齐单源失败保留旧指向的兜底语义)。
    拉取本身失败(HTTP/解析)时重试 3 次;仍失败则退出非 0(fail-closed):
    拿不到旧 index 就意味着 history 会从 31 天塌缩成当天、失败源 latest 丢失,
    且推送后被逐次继承永久损毁 —— 宁可本轮不更新,也不推降级 index。
    (--no-previous-index 显式关闭时不在此列,走 url 为空的早退分支。)

    与 App 的 ArchiveHttpClient.fetchIndex 同源(都是 gitcode API raw),匿名公开读。
    """
    if not url:
        return {}, {}, None
    last_exc = None
    for attempt in range(1, FETCH_MAX_ATTEMPTS + 1):
        try:
            return _load_previous_index_once(url)
        except Exception as e:
            last_exc = e
            if attempt < FETCH_MAX_ATTEMPTS:
                wait = 2 ** attempt  # 2s, 4s
                print(f"[INDEX] 拉上次 index.json 第 {attempt}/{FETCH_MAX_ATTEMPTS} 次失败,"
                      f"{wait}s 后重试:{type(e).__name__}: {e}", file=sys.stderr)
                time.sleep(wait)
    print(f"[INDEX] 拉上次 index.json {FETCH_MAX_ATTEMPTS} 次全败,中断本轮"
          f"(不推降级 index):{type(last_exc).__name__}: {last_exc}", file=sys.stderr)
    sys.exit(1)


def _load_previous_index_once(url):
    """load_previous_index 的单次尝试(成功返回三元组,失败抛异常由调用方重试)。"""
    try:
        text = fetch_text(
            url,
            extra_headers={"Accept": "application/json"},
            expect_json=True,
        )
        data = json.loads(text)
        latest = data.get("latest") or {}
        if not isinstance(latest, dict):
            latest = {}
        latest = {k: v for k, v in latest.items() if isinstance(v, str) and v}
        # history 是双层结构(source → {date: relpath}),逐层过滤掉非法项
        history = data.get("history") or {}
        if not isinstance(history, dict):
            history = {}
        history = {
            src: {d: p for d, p in dates.items()
                  if isinstance(d, str) and d and isinstance(p, str) and p}
            for src, dates in history.items()
            if isinstance(src, str) and isinstance(dates, dict)
        }
        # previous_overview:顶层 latest_overview 字段,本次生成失败时原样继承
        prev_overview = data.get("latest_overview")
        if not isinstance(prev_overview, dict) or not isinstance(prev_overview.get("items"), list):
            prev_overview = None
        print(f"[INDEX] 拉到上次 index.json,{len(latest)} 个源旧指向,"
              f"latest_overview {'有' if prev_overview else '无'}")
        return latest, history, prev_overview
    except Exception as e:
        raise RuntimeError(f"拉取/解析上次 index.json 失败:{type(e).__name__}: {e}") from e


# App 端「历史摘要」只暴露最近 31 天,index.json 的 history 索引按此截断。
# 仓库里更旧的日期目录保留不删(push_data.py 只增不删),只是不再被索引指向。
HISTORY_RETENTION_DAYS = 31

# 历史摘要的起始日期:更早的快照源覆盖不全(producthunt / rundown-ai / aihot-featured
# 尚未接入),对应的日期目录已从数据仓库删除;history 索引一律不收录早于此日期的条目
# (日期是定宽 YYYY-MM-DD 字符串,字典序 == 时间序,直接比较即可)。
HISTORY_START_DATE = "2026-07-18"


def write_index(out_dir, now, results, previous_latest=None, previous_history=None,
                previous_overview=None, overview=None):
    """
    写根目录 index.json:各源最新快照指针(latest)+ 按日期寻址的历史索引(history)
    + 今日总览(latest_overview)。

    结构:
      {
        "updated_at": "2026-07-15T00:44:02+0800",
        "updated_at_ms": 1784047442756,
        "latest": { ... },
        "history": { ... },
        "latest_overview": {                  # 今日总览(跨源综合,流水线预生成)
          "generatedAt": ..., "dataFetchedAt": ...,
          "missingSources": [...],
          "items": [{source,title,url,metrics,comment,breaking,breakingReason}, ...]
        }
      }

    latest / history 的相对路径都是「相对于源目录」的(如 2026-07-15/00-44-data.json),
    客户端拼上 <source>/ 前缀即得完整路径。

    latest 指针来源(需求 a 修复):
      1) 优先扫本地 out/ 取最新成功快照(_scan_latest)—— 本次成功的源。
      2) 本地扫不到的源(本次抓取失败 → out/<source>/ 不存在),从 previous_latest
         (上一次 index.json 的 latest)继承旧指针 —— 让客户端永远拿到有效数据。
         previous_latest 为空(未拉到 / --no-previous-index)时该源直接缺省。

    history 索引(App「历史摘要」按日期查看当日快照/ai_summary):
      每源 merge(previous_history, 本地 _scan_history)—— 同日以本地新扫描为准
      (覆盖旧指向);再过滤掉早于 HISTORY_START_DATE 的日期,按日期字符串倒序
      只保留前 HISTORY_RETENTION_DAYS 天(dict 保持插入序,json.dump 后按日期倒序可读)。
      previous_history 为空(--no-previous-index)时只含本地扫描结果,与 latest 同语义。

    latest_overview(今日总览):
      本次 overview 非空 → 写入新的;本次为 None + previous_overview 非空 → 继承上次
      (避免一次 AI 生成失败导致 App 端总览空掉,对齐单源失败保留旧指向的兜底语义);
      都为空 → 不写该字段(客户端见缺省走 NoData 态)。
    """
    previous_latest = previous_latest or {}
    previous_history = previous_history or {}
    latest = {}
    history = {}
    for name in SOURCES:
        rel = _scan_latest(out_dir, name)
        if rel:
            latest[name] = rel
        elif name in previous_latest:
            # 本次失败:继承上次成功指向(需求 a)
            latest[name] = previous_latest[name]
            print(f"[INDEX] {name:<20} 本次失败,保留旧指向 {previous_latest[name]}")
        # history:旧索引整体继承,本地新扫描覆盖同日;再按起始日过滤、倒序截断
        merged = dict(previous_history.get(name) or {})
        merged.update(_scan_history(out_dir, name))
        keep = [d for d in sorted(merged, reverse=True)
                if d >= HISTORY_START_DATE][:HISTORY_RETENTION_DAYS]
        history[name] = {d: merged[d] for d in keep}
    index = {
        "updated_at": now.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "updated_at_ms": int(now.timestamp() * 1000),
        "latest": latest,
        "history": history,
    }
    # latest_overview:本次新生成优先,失败时继承上次,都无则不写
    effective_overview = overview if overview else previous_overview
    if effective_overview:
        index["latest_overview"] = effective_overview
        if not overview and previous_overview:
            print("[INDEX] 本次总览生成失败,继承上次 latest_overview")
    index_path = os.path.join(out_dir, "index.json")
    with open(index_path, "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=2)
    return index_path


def main():
    parser = argparse.ArgumentParser(description="AIHot Hub 浏览区域数据抓取")
    parser.add_argument("--out-dir", default="out", help="输出根目录(默认 ./out)")
    parser.add_argument("--only", default="", help="逗号分隔的源名,只跑指定源(调试用)")
    parser.add_argument("--limit-hn", type=int, default=20, help="HackerNews 取前 N 条(默认 20)")
    parser.add_argument(
        "--no-summary", action="store_true",
        help="跳过 AI 总结(本地调试 / 无 AI key 时用)",
    )
    parser.add_argument(
        "--previous-index-url", default=DEFAULT_PREVIOUS_INDEX_URL,
        help="上一次 index.json 的 URL(失败源继承其 latest 指针);空串关闭",
    )
    parser.add_argument(
        "--no-previous-index", action="store_true",
        help="不拉上一次 index.json(失败源在本次 index 中直接缺省)",
    )
    args = parser.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    now = now_cst()

    if args.only:
        wanted = [s.strip() for s in args.only.split(",") if s.strip()]
        unknown = [s for s in wanted if s not in SOURCES]
        if unknown:
            print(f"[FATAL] 未知数据源: {unknown};可选: {list(SOURCES)}", file=sys.stderr)
            return 2
        targets = [(n, SOURCES[n]) for n in wanted]
    else:
        targets = list(SOURCES.items())

    # 需求 a:拉上一次 index.json,失败源继承其 latest 指针、history 索引整体合并
    # (见 write_index)。previous_overview 在本次总览生成失败时继承(同兜底语义)。
    previous_latest = {}
    previous_history = {}
    previous_overview = None
    if args.previous_index_url and not args.no_previous_index:
        previous_latest, previous_history, previous_overview = load_previous_index(args.previous_index_url)

    do_summary = not args.no_summary
    if do_summary and not ai_summary.config_ready():
        missing = [k for k in (
            ai_summary.ENV_BASE_URL, ai_summary.ENV_MODEL, ai_summary.ENV_API_KEY
        ) if not os.getenv(k)]
        print(f"[AI] 未配置 {missing},本次跳过 AI 总结(可加 --no-summary 显式关闭)",
              file=sys.stderr)

    # Product Hunt 源的可选 token 提示:缺失时该源会失败(被单源失败跳过),这里提前告知。
    # 与 AI 配置一样不阻断流水线;与 4 个 REQUIRED_ENVS 不同(AI/GITCODE 缺失直接 exit 1)。
    if not os.getenv(PH_TOKEN_ENV) and ("producthunt" in dict(targets)):
        print(f"[PH] 未配置 {PH_TOKEN_ENV},producthunt 源将失败(其余源不受影响)",
              file=sys.stderr)

    results = {}  # source -> {"status": "ok"|"fail"|"skipped", ...}
    for name, fn in targets:
        try:
            # 需求 a:失败重试最多 3 次
            items, meta = fetch_with_retry(name, fn, limit_hn=args.limit_hn)

            # 空结果视同失败(选择器失效/接口异常):不落盘 0 条快照、不前移 latest,
            # 由 previous_latest 继承旧指针,避免 App 端拿到空列表
            if not items:
                raise RuntimeError("抓取结果为空(疑似源站改版/接口异常),按失败处理")

            # 需求 c:每源抓完做 AI 总结(失败仅 warn,不阻断落盘)
            ai_v2 = None
            if do_summary:
                ai_v2 = ai_summary.summarize_source(name, items)

            file_path = write_snapshot(args.out_dir, name, items, meta, now, ai_summary_v2=ai_v2)
            extra = "(含 AI 摘要)" if ai_v2 else ""
            print(f"[OK]   {name:<20} {len(items):>4} 条{extra} → {file_path}")
            results[name] = {"status": "ok", "count": len(items), "file": file_path}
        except Exception as e:
            # 单源 3 次重试全败:记错误、跳过、继续(需求 a:由 previous_latest 兜底 index)
            print(f"[FAIL] {name:<20} 重试 {FETCH_MAX_ATTEMPTS} 次仍失败:"
                  f"{type(e).__name__}: {e}", file=sys.stderr)
            results[name] = {"status": "fail", "error": f"{type(e).__name__}: {e}"}

    # manifest:本次运行总览(放输出根,便于 CI 提交后回溯)
    manifest = {
        "run_at": now.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "run_at_ms": int(now.timestamp() * 1000),
        "sources": results,
    }
    manifest_path = os.path.join(args.out_dir, "manifest.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    # 今日总览:跨源综合分析(失败仅 warn,不阻断推送;失败时 write_index 继承 previous_overview)
    overview = None
    if do_summary:
        overview = overview_summary.generate_overview(args.out_dir, now)
    else:
        print("[OVERVIEW] --no-summary 模式,跳过总览生成", file=sys.stderr)

    # index.json:每源最新快照指针(本地扫最新;失败源继承 previous_latest)
    # + 按日期寻址的历史索引(与 previous_history 合并后按保留期截断)
    # + 今日总览 latest_overview(本次生成优先,失败继承 previous_overview)
    index_path = write_index(args.out_dir, now, results, previous_latest=previous_latest,
                             previous_history=previous_history,
                             previous_overview=previous_overview, overview=overview)

    ok_count = sum(1 for r in results.values() if r["status"] == "ok")
    print(f"\n汇总: {ok_count}/{len(results)} 源成功;manifest → {manifest_path};index → {index_path}")
    return 0 if ok_count > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
