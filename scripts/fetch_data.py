#!/usr/bin/env python3
"""
AIHot 「Hub」tab 浏览区域数据抓取脚本。

复刻 App 端的抓取逻辑(见 app/src/main/java/com/example/aihot/data/ 下的 5 个
Repository 与对应 model 类),把 5 个数据源解析成 JSON 落盘:

  - hackernews         HackerNews Top Stories(两步拉取,Firebase API)
  - github-trending    GitHub Trending 仓库(HTML 抓取)
  - linuxdo            LinuxDo 热榜(Discourse JSON)
  - stormzhang-ai      stormzhang AI 资讯(HTML 抓取)
  - huggingface-papers HuggingFace Trending Papers(HTML 抓取)

输出目录结构:
  <out-dir>/<source>/<YYYY-MM-DD>/<HH-MM>-data.json

日期/时间统一用北京时间(UTC+8);CI 里设 TZ=Asia/Shanghai 即可。

失败策略:每个源独立 try/except,单源失败(如 Cloudflare 拦截)记错误日志并跳过,
其余源照常落盘。只要 ≥1 个源成功,退出码 0;全部失败才非 0。

用法:
  python3 scripts/fetch_data.py --out-dir /tmp/aihot-data-test
  python3 scripts/fetch_data.py --out-dir out --only hackernews,linuxdo
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
    """
    raw = fetch_text(
        "https://linux.do/c/develop/4/l/hot.json",
        extra_headers={
            "Accept": "application/json",
            "Accept-Language": "zh-CN,zh;q=0.9",
        },
        expect_json=True,
    )
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


# ===== 数据源注册表:name → 抓取函数 =====

SOURCES = {
    "hackernews": fetch_hackernews,
    "github-trending": fetch_github_trending,
    "linuxdo": fetch_linuxdo,
    "stormzhang-ai": fetch_stormzhang_ai,
    "huggingface-papers": fetch_huggingface_papers,
}


def write_snapshot(out_dir, source_name, items, meta, now):
    """
    落盘单源快照:<out-dir>/<source>/<YYYY-MM-DD>/<HH-MM>-data.json。
    顶层结构:source / fetched_at(ISO CST)/ fetched_at_ms / count / items / meta。
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
    with open(file_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    return file_path


def main():
    parser = argparse.ArgumentParser(description="AIHot Hub 浏览区域数据抓取")
    parser.add_argument("--out-dir", default="out", help="输出根目录(默认 ./out)")
    parser.add_argument("--only", default="", help="逗号分隔的源名,只跑指定源(调试用)")
    parser.add_argument("--limit-hn", type=int, default=20, help="HackerNews 取前 N 条(默认 20)")
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

    results = {}  # source -> {"status": "ok"|"fail"|"skipped", ...}
    for name, fn in targets:
        try:
            if name == "hackernews":
                items, meta = fn(limit=args.limit_hn)
            else:
                items, meta = fn()
            file_path = write_snapshot(args.out_dir, name, items, meta, now)
            print(f"[OK]   {name:<20} {len(items):>4} 条 → {file_path}")
            results[name] = {"status": "ok", "count": len(items), "file": file_path}
        except Exception as e:
            # 单源失败不拖垮其余:记错误、跳过、继续
            print(f"[FAIL] {name:<20} {type(e).__name__}: {e}", file=sys.stderr)
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

    ok_count = sum(1 for r in results.values() if r["status"] == "ok")
    print(f"\n汇总: {ok_count}/{len(results)} 源成功;manifest → {manifest_path}")
    return 0 if ok_count > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
