"""数据流水线公共定义 —— 8 源 key + 北京时区 helper。

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
