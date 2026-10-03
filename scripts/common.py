"""数据流水线公共定义 —— 8 源 key + 北京时区 helper + 通用重试。

收口此前分散在 ai_summary.py / overview_summary.py / fetch_data.py / push_data.py /
backfill_history.py 各自定义的源 key 列表(且顺序不一致)与时区常量(命名不统一:
CST vs BEIJING_TZ)。新增/改名源时只改本文件一处,各脚本统一 import。

注意:
  - SOURCE_KEYS 顺序为固定展示序(总览默认顺序),与 App 端
    ui/more/SourceMeta.kt 的 DEFAULT_SOURCE_ORDER 对齐。各脚本如需不同迭代顺序
    可自行 sorted() 或重排,但 key 集合必须与本元组一致。
  - 北京时间统一叫 BEIJING_TZ(此前 fetch_data/push_data/backfill 叫 CST,
    与美国 Central Standard Time 同名易混淆)。
"""

import sys
import time
from datetime import datetime, timezone, timedelta

# 8 个数据源 key(对齐 App ui/more/SourceMeta.kt 的 DEFAULT_SOURCE_ORDER)。
# 新增/改名源时同步改本元组 + App 端 SourceMeta + index.json 目录名。
SOURCE_KEYS = (
    "hackernews",
    "github-trending",
    "openai-anthropic-news",
    "huggingface-papers",
    "producthunt",
    "rundown-ai",
    "aihot-featured",
    "stormzhang-ai",
)

# 北京时间(UTC+8)。流水线所有时间戳/文件名/提交信息均用此时区。
BEIJING_TZ = timezone(timedelta(hours=8))


def now_cst():
    """当前北京时间(GitHub Actions 设了 TZ=Asia/Shanghai 时与系统时间一致)。"""
    return datetime.now(BEIJING_TZ)


def retry(fn, *, attempts=3, backoff_base=2, log_tag="RETRY", on_exhausted=None):
    """
    业务层指数退避重试 —— 收口 fetch_data.py 中 fetch_with_retry / load_previous_index
    的同构重试骨架(成功 return / 失败 sleep(backoff_base ** attempt) 后再试)。

    与 ai_client.py 的传输层 429/503 感知重试(读 Retry-After 头)语义不同,不统一。

    参数:
      - fn: 无参可调用,返回值即本函数返回值;抛异常则触发重试。
      - attempts: 最大尝试次数(含首次)。
      - backoff_base: 退避基数,第 n 次失败后 sleep(backoff_base ** n) 秒。
      - log_tag: 日志前缀(如 "RETRY" / "INDEX"),区分调用方。
      - on_exhausted: attempts 次全败时的回调(收 last_exc);不传则抛最后一个异常。

    返回 fn() 的返回值;全败且有 on_exhausted 则走回调,否则抛异常。
    """
    last_exc = None
    for attempt in range(1, attempts + 1):
        try:
            return fn()
        except Exception as e:
            last_exc = e
            if attempt < attempts:
                wait = backoff_base ** attempt
                print(f"[{log_tag}] 第 {attempt}/{attempts} 次失败,"
                      f"{wait}s 后重试:{type(e).__name__}: {e}", file=sys.stderr)
                time.sleep(wait)
    if on_exhausted is not None:
        on_exhausted(last_exc)
    else:
        raise last_exc


# ===== 字段/日期共享 helper(2026-10 收口:原 overview_summary 与 trend_keywords
# 各持一份的 _s/_as_int,及 overview 的三个日期 key 函数;现供各源适配层共用) =====

def str_field(o, key, default=""):
    """安全取字符串,剥白边,None 转默认值(原 overview/trend 各持一份的 _s)。"""
    v = o.get(key, default)
    return str(v).strip() if v is not None else default


def int_field(o, key, default=0):
    """兼容取 int,字符串数字也接受(原 overview_summary 的 _as_int)。"""
    v = o.get(key, default)
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


def beijing_date_key_of_ms(epoch_ms):
    """Unix 毫秒 → 北京日期(yyyy-MM-dd);0 或负数返回空串。"""
    if not epoch_ms or epoch_ms <= 0:
        return ""
    try:
        return datetime.fromtimestamp(epoch_ms / 1000, tz=BEIJING_TZ).strftime("%Y-%m-%d")
    except Exception:
        return ""


def beijing_date_key_of_iso(iso):
    """ISO UTC 字符串(如 2026-07-18T07:01:00Z)→ 北京日期;解析失败返回空串。"""
    s = (iso or "").strip()
    if not s:
        return ""
    try:
        dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.astimezone(BEIJING_TZ).strftime("%Y-%m-%d")
    except Exception:
        return ""


def beijing_date_key_of_en_date(text):
    """英文月份格式日期(如 "Jul 8, 2026")→ 北京日期;解析失败返回空串。"""
    s = (text or "").strip()
    if not s:
        return ""
    for fmt in ("%b %d, %Y", "%B %d, %Y"):
        try:
            dt = datetime.strptime(s, fmt).replace(tzinfo=BEIJING_TZ)
            return dt.strftime("%Y-%m-%d")
        except ValueError:
            continue
    return ""
