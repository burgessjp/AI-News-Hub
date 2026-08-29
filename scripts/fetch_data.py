#!/usr/bin/env python3
"""
AI News Hub「Hub」tab 浏览区域数据抓取脚本(编排层)。

8 个数据源的抓取器按源拆在 sources/ 包(每源一个模块;抓取器契约、注册表与
新增源 checklist 见 sources/__init__.py),本文件只负责编排与落盘:

  1. 阶段 1 串行抓取:逐源 fetch_with_retry(3 次重试)→ write_snapshot 落盘;
  2. 阶段 2 并发 AI 摘要(ai_summary)→ patch 回各快照;
  3. 今日总览(overview_summary)→ 按日归档 + 内嵌 index;
  4. 索引:index.json(latest 失败继承)+ history.json / overview_history.json。

源清单(各源 items 字段与解析细节见 sources/ 对应模块):

  - hackernews           HackerNews Top Stories(两步拉取,Firebase API)
  - github-trending      GitHub Trending 仓库(HTML 抓取)
  - stormzhang-ai        stormzhang AI 资讯(HTML 抓取)
  - huggingface-papers   HuggingFace Trending Papers(HTML 抓取)
  - producthunt          Product Hunt 当日热门(GraphQL API,需 PRODUCT_HUNT_KEY)
  - rundown-ai           The Rundown AI newsletter(/articles 列表页,RSC 内嵌 JSON 主路径 + DOM 卡片兜底)
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
  - 每源抓完调 ai_summary.summarize_source 生成简体中文要点,写入快照顶层 `ai_summary_v2`。
  - 总结 8 个稳定源(hackernews/github-trending/huggingface-papers/stormzhang-ai/
    producthunt/rundown-ai/aihot-featured/openai-anthropic-news)。AI 调用失败仅 warn,不阻断落盘。
  - 需 3 个 AI 环境变量(AI_NEWS_HUB_AI_BASE_URL/_MODEL/_API_KEY)齐全;缺失则跳过总结。
    加 --no-summary 可显式跳过(本地调试用)。

用法:
  python3 scripts/fetch_data.py --out-dir /tmp/aihot-data-test
  python3 scripts/fetch_data.py --out-dir out --only hackernews,github-trending
  python3 scripts/fetch_data.py --out-dir out --no-summary --no-previous-index  # 本地干跑
"""

import argparse
import json
import os
import re
import sys
from concurrent.futures import ThreadPoolExecutor

import requests

# AI 总结:每源抓完调一次,失败不阻断(对齐 App SummaryRepository)
import ai_summary
import overview_summary

# 北京时间(UTC+8)—— 统一从 common 引入(命名 BEIJING_TZ;此处保留 CST 别名供
# 本文件内部及 backfill_history 的 `from fetch_data import CST` 向后兼容)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import BEIJING_TZ as CST, now_cst, retry

# 8 源抓取器注册表(顺序承重,契约见包 docstring)+ 抓取层公共件
from sources import SOURCES, EMPTY_OK_SOURCES
from sources.httpio import fetch_text
from sources.producthunt import PH_TOKEN_ENV


# 单源抓取最大重试次数(需求 a:失败重试,最多 3 次)。首次 + 2 次重试。
FETCH_MAX_ATTEMPTS = 3


class EmptyResultError(RuntimeError):
    """抓取本身成功(HTTP 200 且解析正常)但结果为空,按失败处理。

    与网络/接口错误区分:这条路径不经过 fetch_with_retry 的 3 次重试,
    main 的失败日志单独报「未重试」,避免误导排查方向。"""


def fetch_with_retry(name, fn, limit_hn=None):
    """
    包装单源抓取,失败重试最多 FETCH_MAX_ATTEMPTS 次(需求 a)。

    每次失败间指数退避:2s / 4s。全 3 次都败才抛最后一个异常
    (交给 main 的 try/except 记 fail,并触发「保留旧 latest」逻辑)。

    HackerNews 签名带 limit,其余源无参,这里用闭包统一调用入口,重试骨架走 common.retry。
    """
    # 把 HackerNews 的 limit 分派收敛进闭包,统一成无参 callable 交给 common.retry
    def attempt():
        if name == "hackernews" and limit_hn is not None:
            return fn(limit=limit_hn)
        return fn()

    return retry(
        attempt,
        attempts=FETCH_MAX_ATTEMPTS,
        log_tag=f"RETRY {name:<14}",
    )


def write_snapshot(out_dir, source_name, items, meta, now, ai_summary_v2=None):
    """
    落盘单源快照:<out-dir>/<source>/<YYYY-MM-DD>/<HH-MM>-data.json。
    顶层结构:source / fetched_at(ISO CST)/ fetched_at_ms / count / items / meta / ai_summary_v2?。

    ai_summary_v2:AI 摘要对象列表(list[dict],每项含 title + desc + url;
    url 由 ai_summary.py 按 AI 返回的 ref 编号回填,可能为空串),非空时写入顶层
    `ai_summary_v2` 字段。调用 AI 失败时传 None,该字段直接省略。
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


def patch_ai_summary_v2(file_path, ai_summary_v2):
    """
    把 AI 摘要回填到已落盘的快照顶层 `ai_summary_v2` 字段(两阶段抓取用)。

    P1 优化:抓取阶段先不带摘要落盘(快),拿到 8 源 items 后再并发调 AI 总结;
    本函数把并发产出的摘要 patch 回各自快照文件,避免重写整个快照。
    ai_summary_v2 为 None/空时跳过(保持无摘要时的结构兼容)。
    """
    if not ai_summary_v2:
        return
    with open(file_path, "r", encoding="utf-8") as f:
        payload = json.load(f)
    payload["ai_summary_v2"] = ai_summary_v2
    with open(file_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)


def write_overview_snapshot(out_dir, overview, now):
    """
    落盘今日总览归档:<out-dir>/overview/<YYYY-MM-DD>/<HH-MM>-data.json。

    内容与 index.json 内嵌的 latest_overview 是同一个对象(一处生成、两处落盘):
    index 内嵌让 App 冷启动/小组件/通知一次请求拿到最新值;按日期归档的文件
    不可变、可回看,补上「总览无历史」的缺口。

    目录/命名规则与 write_snapshot 同构,因此 _scan_history(out_dir, "overview")
    可直接复用为总览的日期索引扫描。总览生成失败时调用方不落盘,当日历史条目
    由 previous_overview_history 继承兜底(与 latest 指针继承同语义)。
    """
    date_str = now.strftime("%Y-%m-%d")
    time_str = now.strftime("%H-%M")
    dir_path = os.path.join(out_dir, "overview", date_str)
    os.makedirs(dir_path, exist_ok=True)
    file_path = os.path.join(dir_path, f"{time_str}-data.json")
    with open(file_path, "w", encoding="utf-8") as f:
        json.dump(overview, f, ensure_ascii=False, indent=2)
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
# 历史索引已拆出 index.json 为根级独立文件(防 index 随保留期/源数量无限增长;
# App 端「历史摘要」「历史总览」按需拉取,不再拖累所有 tab 共享的 index)。
DEFAULT_PREVIOUS_HISTORY_URL = (
    "https://api.gitcode.com/api/v5/repos/peng1818/AI-News-Hub-Data"
    "/raw/history.json?ref=news-hub-data"
)
DEFAULT_PREVIOUS_OVERVIEW_HISTORY_URL = (
    "https://api.gitcode.com/api/v5/repos/peng1818/AI-News-Hub-Data"
    "/raw/overview_history.json?ref=news-hub-data"
)


def fetch_optional_json(url):
    """
    拉一个「可能尚不存在」的 JSON 文件:200 → 解析后的 dict;404 → None
    (语义:文件未生成,调用方走过渡回退);其它错误抛异常(由 retry 兜底,
    重试耗尽后 fail-closed)。"""
    try:
        text = fetch_text(url, extra_headers={"Accept": "application/json"}, expect_json=True)
    except requests.HTTPError as e:
        status = e.response.status_code if e.response is not None else None
        if status == 404:
            return None
        raise
    return json.loads(text)


def _fetch_required_json(url, label):
    """拉必须存在的 JSON 文件,失败包装统一异常(由 retry 重试)。"""
    try:
        text = fetch_text(url, extra_headers={"Accept": "application/json"}, expect_json=True)
        return json.loads(text)
    except Exception as e:
        raise RuntimeError(f"拉取/解析{label}失败:{type(e).__name__}: {e}") from e


def _valid_history_map(value):
    """{source: {date: relpath}} 双层结构逐层过滤非法项(history.json 用)。"""
    if not isinstance(value, dict):
        return {}
    return {
        src: {d: p for d, p in dates.items()
              if isinstance(d, str) and d and isinstance(p, str) and p}
        for src, dates in value.items()
        if isinstance(src, str) and isinstance(dates, dict)
    }


def _valid_date_map(value):
    """{date: relpath} 单层结构逐项过滤非法项(overview_history.json 用)。"""
    if not isinstance(value, dict):
        return {}
    return {d: p for d, p in value.items()
            if isinstance(d, str) and d and isinstance(p, str) and p}


def load_previous_index(index_url, history_url="", overview_history_url=""):
    """
    拉上一次的 index.json + history.json + overview_history.json,
    返回 (latest, history, previous_overview, previous_overview_history) 四元组:
      latest                  : source → 相对路径,失败源继承旧 latest 指针用;
      history                 : source → {date: 相对路径},独立 history.json 的合并基线;
      previous_overview       : 上次的 latest_overview dict(本次总览生成失败时继承用);
      previous_overview_history: date → 相对路径,独立 overview_history.json 的合并基线。
    任一字段缺失或拉取失败时,对应项返回 {} / {} / None / {}。

    需求 a:CI 是干净跑(每次只有本次产物),某源本次抓取失败时本地目录不存在,
    _scan_latest 会返回 None,导致 latest 丢掉该源 —— 与 docs 承诺的「保留旧指向」
    不符。这里从 gitcode 拉上一次数据,失败源继承其指针,让客户端永远能拿到有效
    数据。history 同理:CI 本地只有当天快照,历史日期索引必须从旧文件继承合并,
    否则永远只剩当天一条。previous_overview 同样:本次总览 AI 生成失败时继承。

    history / overview_history 已拆出 index.json(独立文件优先):
      - 独立文件 200 → 直接用(即使旧版 index 还带着内联字段,也以独立文件为准
        —— 防旧代码批次写坏内联字段后污染新索引);
      - 404 + index 有内联字段 → 过渡期回退内联(拆分上线后的首次运行即完成迁移,
        写出独立文件且 index 不再内联);
      - 404 + 无内联 + index 无历史数据信号(首跑)→ {};
      - 404 + 无内联 + 有历史数据信号 → 独立文件意外丢失,fail-closed —— 宁可本轮
        不更新,也不推塌缩的历史索引(会被逐次继承永久损毁)。
    index.json 本身拉取失败同样 fail-closed(缺它 latest / 总览都会降级)。
    (--no-previous-index / index_url 为空时三者全部跳过,走首跑语义。)
    """
    if not index_url:
        return {}, {}, None, {}

    # 重试骨架走 common.retry;全败时 fail-closed(sys.exit 1,不推降级 index)
    def on_exhausted(exc):
        print(f"[INDEX] 拉上一次 index/历史索引 {FETCH_MAX_ATTEMPTS} 次全败,中断本轮"
              f"(不推降级 index):{type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(1)

    index = retry(
        lambda: _fetch_required_json(index_url, "上次 index.json"),
        attempts=FETCH_MAX_ATTEMPTS,
        log_tag="INDEX 拉上一次 index.json",
        on_exhausted=on_exhausted,
    )
    latest = index.get("latest") or {}
    if not isinstance(latest, dict):
        latest = {}
    latest = {k: v for k, v in latest.items() if isinstance(v, str) and v}
    prev_overview = index.get("latest_overview")
    if not isinstance(prev_overview, dict) or not isinstance(prev_overview.get("items"), list):
        prev_overview = None

    history = _load_split_index(
        history_url, index.get("history"),
        has_prior_data=bool(latest),
        label="history.json",
        on_exhausted=on_exhausted,
        validator=_valid_history_map,
    )
    overview_history = _load_split_index(
        overview_history_url, index.get("overview_history"),
        has_prior_data=prev_overview is not None,
        label="overview_history.json",
        on_exhausted=on_exhausted,
        validator=_valid_date_map,
    )
    print(f"[INDEX] 拉到上次 index.json,{len(latest)} 个源旧指向,"
          f"latest_overview {'有' if prev_overview else '无'},"
          f"history 共 {sum(len(v) for v in history.values())} 天,"
          f"overview_history {len(overview_history)} 天")
    return latest, history, prev_overview, overview_history


def _load_split_index(url, inline_value, has_prior_data, label, on_exhausted, validator):
    """
    拉拆分出的独立索引文件(history.json / overview_history.json),迁移与兜底规则
    见 load_previous_index 注释:独立文件优先 → 404 回退 index 内联字段 →
    无内联且仓库已有历史数据则 fail-closed(文件意外丢失)→ 首跑返回 {}。
    """
    if not url:
        return {}
    result = retry(
        lambda: fetch_optional_json(url),
        attempts=FETCH_MAX_ATTEMPTS,
        log_tag=f"INDEX 拉上一次 {label}",
        on_exhausted=on_exhausted,
    )
    if result is not None:
        return validator(result)
    inline = validator(inline_value)
    if inline:
        print(f"[INDEX] {label} 尚不存在,过渡期回退 index.json 内联字段")
        return inline
    if has_prior_data:
        print(f"[FATAL] {label} 不存在且 index 无内联字段,但仓库已有历史数据"
              f"(疑似文件丢失),中断本轮不推降级索引", file=sys.stderr)
        sys.exit(1)
    return {}


# App 端「历史摘要」只暴露最近 31 天,index.json 的 history 索引按此截断。
# 仓库里更旧的日期目录保留不删(push_data.py 只增不删),只是不再被索引指向。
HISTORY_RETENTION_DAYS = 31

# 历史摘要的起始日期:更早的快照源覆盖不全(producthunt / rundown-ai / aihot-featured
# 尚未接入),对应的日期目录已从数据仓库删除;history 索引一律不收录早于此日期的条目
# (日期是定宽 YYYY-MM-DD 字符串,字典序 == 时间序,直接比较即可)。
HISTORY_START_DATE = "2026-07-18"

# 总览历史(latest_overview 按日归档)的 index 指针保留天数。
# 与快照的 HISTORY_RETENTION_DAYS(31)刻意不同档:总览文件每份仅数 KB,
# 且是花钱调 AI 生成、覆盖即失的产物(AI 摘要随快照天然留痕、趋势可从快照重算),
# 多留价值高;90 天指针约 4KB,index.json 仍保持有界路由表。
OVERVIEW_RETENTION_DAYS = 90


def write_index(out_dir, now, results, previous_latest=None,
                previous_overview=None, overview=None):
    """
    写根目录 index.json:updated_at + 各源最新快照指针(latest)+ 今日总览(latest_overview)。

    index 只保留「即时字段」:history / overview_history 已拆出为根级独立文件
    (见 [build_history] / [build_overview_history]),index 不再随保留期/源数量
    增长 —— App 各 tab 共享拉取的入口文件保持小而有界。

    结构:
      {
        "updated_at": "2026-07-15T00:44:02+0800",
        "updated_at_ms": 1784047442756,
        "latest": { ... },
        "latest_overview": {                  # 今日总览(跨源综合,流水线预生成)
          "generatedAt": ..., "dataFetchedAt": ...,
          "missingSources": [...],
          "items": [{source,title,url,metrics,comment,breaking,breakingReason}, ...]
        }
      }

    latest 的相对路径是「相对于源目录」的(如 2026-07-15/00-44-data.json),
    客户端拼上 <source>/ 前缀即得完整路径。

    latest 指针来源(需求 a 修复):
      1) 优先扫本地 out/ 取最新成功快照(_scan_latest)—— 本次成功的源。
      2) 本地扫不到的源(本次抓取失败 → out/<source>/ 不存在),从 previous_latest
         (上一次 index.json 的 latest)继承旧指针 —— 让客户端永远拿到有效数据。
         previous_latest 为空(未拉到 / --no-previous-index)时该源直接缺省。

    latest_overview(今日总览):
      本次 overview 非空 → 写入新的;本次为 None + previous_overview 非空 → 继承上次
      (避免一次 AI 生成失败导致 App 端总览空掉,对齐单源失败保留旧指向的兜底语义);
      都为空 → 不写该字段(客户端见缺省走 NoData 态)。
    """
    previous_latest = previous_latest or {}
    latest = {}
    for name in SOURCES:
        rel = _scan_latest(out_dir, name)
        if rel:
            latest[name] = rel
        elif name in previous_latest:
            # 本次失败:继承上次成功指向(需求 a)
            latest[name] = previous_latest[name]
            print(f"[INDEX] {name:<20} 本次失败,保留旧指向 {previous_latest[name]}")
    index = {
        "updated_at": now.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "updated_at_ms": int(now.timestamp() * 1000),
        "latest": latest,
    }
    # latest_overview:本次新生成优先,失败时继承上次,都无则不写
    effective_overview = overview if overview else previous_overview
    if effective_overview:
        index["latest_overview"] = effective_overview
        if not overview and previous_overview:
            print("[INDEX] 本次总览生成失败,继承上次 latest_overview")
    index_path = os.path.join(out_dir, "index.json")
    write_json_file(index_path, index)
    return index_path


def _retain_recent(previous, local, retention_days):
    """
    历史索引共用合并规则:旧索引整体继承 + 本地新扫描覆盖同日 → 过滤早于
    HISTORY_START_DATE 的日期 → 按日期倒序截前 retention_days 天
    (dict 保持插入序,写出的 JSON 按日期倒序可读)。
    """
    merged = dict(previous or {})
    merged.update(local or {})
    keep = [d for d in sorted(merged, reverse=True)
            if d >= HISTORY_START_DATE][:retention_days]
    return {d: merged[d] for d in keep}


def build_history(out_dir, previous_history=None):
    """
    构造独立 history.json 内容:{source: {date: relpath}}(App「历史摘要」按日期
    寻址当日快照)。每源按 _retain_recent 规则合并截断(31 天);仅收录 SOURCES
    现存源(下线源的旧键不继承,自然淘汰)。
    """
    previous_history = previous_history or {}
    return {
        name: _retain_recent(previous_history.get(name),
                             _scan_history(out_dir, name),
                             HISTORY_RETENTION_DAYS)
        for name in SOURCES
    }


def build_overview_history(out_dir, previous_overview_history=None):
    """
    构造独立 overview_history.json 内容:{date: relpath}(总览按日归档索引,
    相对 overview/ 目录)。合并规则与 history 同构,保留 90 天;本次生成失败时
    当日无本地文件,早批次的归档从旧索引继承保留。
    """
    return _retain_recent(previous_overview_history,
                          _scan_history(out_dir, "overview"),
                          OVERVIEW_RETENTION_DAYS)


def write_json_file(path, payload):
    """统一 JSON 落盘格式(UTF-8 中文原样、2 空格缩进,与快照/index 一致)。"""
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)


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
        "--previous-history-url", default=DEFAULT_PREVIOUS_HISTORY_URL,
        help="上一次 history.json 的 URL(历史索引合并基线);随 --previous-index-url 一同关闭",
    )
    parser.add_argument(
        "--previous-overview-history-url", default=DEFAULT_PREVIOUS_OVERVIEW_HISTORY_URL,
        help="上一次 overview_history.json 的 URL(总览历史索引合并基线);随 --previous-index-url 一同关闭",
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

    # 需求 a:拉上一次 index.json + history.json + overview_history.json,失败源
    # 继承其 latest 指针、两个历史索引整体合并(见 load_previous_index;拆分过渡期
    # 独立文件 404 时自动回退 index 内联字段)。previous_overview 在本次总览生成
    # 失败时继承(同兜底语义)。
    previous_latest = {}
    previous_history = {}
    previous_overview = None
    previous_overview_history = {}
    if args.previous_index_url and not args.no_previous_index:
        (previous_latest, previous_history, previous_overview,
         previous_overview_history) = load_previous_index(
            args.previous_index_url, args.previous_history_url,
            args.previous_overview_history_url)

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
    # 阶段 1(串行抓取):逐源 fetch + 落盘(先不带 AI 摘要),成功源缓存待 AI 总结。
    # 抓取保持串行(各源 HTTP 请求相互独立但并发会叠加反爬/限流风险,刻意串行)。
    # AI 摘要阶段(P1)改为并发 —— 见下方阶段 2。
    pending_summary = []  # [(name, items, file_path), ...] 待 AI 总结的成功源
    for name, fn in targets:
        try:
            # 需求 a:失败重试最多 3 次
            items, meta = fetch_with_retry(name, fn, limit_hn=args.limit_hn)

            # 空结果处理:EMPTY_OK_SOURCES 里的源(如 openai-anthropic-news 的月级更新
            # 子源)在时间窗口无新文时正常返回空,落盘 0 条快照、不调 AI;其余源空结果
            # = 选择器失效/接口异常,按失败处理(不落盘,由 previous_latest 兜底)。
            if not items and name not in EMPTY_OK_SOURCES:
                raise EmptyResultError("抓取结果为空(疑似源站改版/接口异常),按失败处理")

            file_path = write_snapshot(args.out_dir, name, items, meta, now)
            print(f"[OK]   {name:<20} {len(items):>4} 条 → {file_path}")
            # manifest 的 file 存相对仓库根的路径(write_snapshot 返回的是带 out 前缀的
            # 本地路径,直接入库会对消费者产生 out/ 前缀误导)
            results[name] = {
                "status": "ok", "count": len(items),
                "file": os.path.relpath(file_path, args.out_dir),
            }
            if items:  # 空结果的源没有东西可总结,不进待总结队列
                pending_summary.append((name, items, file_path))
        except EmptyResultError as e:
            # 空结果路径:抓取本身成功、未经过重试,单独报避免「重试 3 次仍失败」误导排查
            print(f"[FAIL] {name:<20} 抓取成功但结果为空(未重试):"
                  f"{type(e).__name__}: {e}", file=sys.stderr)
            results[name] = {"status": "fail", "error": f"{type(e).__name__}: {e}"}
        except Exception as e:
            # 单源 3 次重试全败:记错误、跳过、继续(需求 a:由 previous_latest 兜底 index)
            print(f"[FAIL] {name:<20} 重试 {FETCH_MAX_ATTEMPTS} 次仍失败:"
                  f"{type(e).__name__}: {e}", file=sys.stderr)
            results[name] = {"status": "fail", "error": f"{type(e).__name__}: {e}"}

    # 阶段 2(并发 AI 摘要):P1 优化 —— 8 源的 summarize_source 彼此独立(LLM 调用是
    # IO-bound),用线程池并发跑,把 AI 阶段从串行 ~8× 压到 ~2-3×。summarize_source
    # 内部自带 3 次重试且无共享可变状态,线程安全。并发度限 4 控制对 AI 服务的压力。
    if do_summary and pending_summary:
        with ThreadPoolExecutor(max_workers=4, thread_name_prefix="ai") as pool:
            # map 保输入/输出顺序一一对应;每个任务返回 (name, ai_v2)
            ai_results = list(pool.map(
                lambda t: (t[0], ai_summary.summarize_source(t[0], t[1])),
                pending_summary,
            ))
        for name, ai_v2 in ai_results:
            if ai_v2:
                # 找到该源的快照路径并 patch 回写 ai_summary_v2
                file_path = next(p[2] for p in pending_summary if p[0] == name)
                patch_ai_summary_v2(file_path, ai_v2)
                print(f"[AI]   {name:<20} 摘要已回填 {len(ai_v2)} 条 → {file_path}")
            else:
                print(f"[AI]   {name:<20} 摘要失败/为空,快照不带 ai_summary_v2",
                      file=sys.stderr)

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

    # 总览生成成功:除内嵌进 index 外,同时按日期归档落盘(不可变文件,支持历史回看)
    if overview:
        print(f"[OVERVIEW] 总览已归档 → {write_overview_snapshot(args.out_dir, overview, now)}")

    # index.json(即时字段:updated_at / latest / latest_overview)
    # + 根级独立历史索引文件(拆出 index,不随保留期增长):
    #   history.json(摘要历史,31 天)/ overview_history.json(总览归档,90 天)
    index_path = write_index(args.out_dir, now, results, previous_latest=previous_latest,
                             previous_overview=previous_overview, overview=overview)
    history_path = os.path.join(args.out_dir, "history.json")
    write_json_file(history_path, build_history(args.out_dir, previous_history))
    overview_history_path = os.path.join(args.out_dir, "overview_history.json")
    write_json_file(overview_history_path,
                    build_overview_history(args.out_dir, previous_overview_history))

    ok_count = sum(1 for r in results.values() if r["status"] == "ok")
    print(f"\n汇总: {ok_count}/{len(results)} 源成功;manifest → {manifest_path};"
          f"index → {index_path};history → {history_path};"
          f"overview_history → {overview_history_path}")
    return 0 if ok_count > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
