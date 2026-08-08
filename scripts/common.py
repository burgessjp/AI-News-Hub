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
