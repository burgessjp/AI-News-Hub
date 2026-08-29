"""数据源抓取器包 —— 每源一个模块,本 __init__ 组装注册表。

抓取器契约(新增/修改源必读):
  - 每源一个模块 sources/<name>.py,导出 fetch_<name>(),无必需参数
    (hackernews 额外接受 limit 关键字,由 fetch_data.fetch_with_retry 闭包分派)。
  - 返回 (items: list[dict], meta: dict);meta 的键值由 fetch_data.write_snapshot
    拍扁进快照顶层(如 stormzhang 的 pageDate)。
  - 失败抛异常(网络/解析/CF 拦截),由 fetch_data.fetch_with_retry 统一 3 次重试;
    HTTP 一律走 .httpio 的 fetch_text / SESSION(UA 与 CF 检测收口在那一层)。
  - 空结果语义在 main 层判定:默认 = 失败(疑似选择器失效);只有 EMPTY_OK_SOURCES
    里的源(时间窗口内可无新文)允许正常返回空列表。

items 通用字段:title / url 必有,summary 可空;多数源有 rank(1 起);
publishedAt 形如 'YYYY-MM-DD' 或 ISO(可空,无日期条目排序沉底)。各源差异字段
见各自模块 docstring;字段命名 camelCase,对齐 App 端各 model 的 fromJson。

SOURCES 顺序约定(承重,勿随手重排):字典顺序 = main 串行抓取顺序 =
index.json latest 键序 = history.json 源键序;与 common.SOURCE_KEYS 的展示序
(App 端 DEFAULT_SOURCE_ORDER)刻意不同——那是 UI 概念,与本表无关。
scripts/tests/test_sources_registry.py 把两套顺序分别钉死。

新增数据源 checklist(全链路,一处不落):
  1. scripts/common.py 的 SOURCE_KEYS 加 key(决定 App 展示序);
  2. 新建 sources/<name>.py(契约见上),在本文件 SOURCES 注册;
     空结果合法的源才加 EMPTY_OK_SOURCES;
  3. overview_summary.py:SOURCE_TITLES 加标题 + _extract_items 加字段分支;
  4. ai_summary.py:items 的落地页 URL 字段不是 url 时,_item_url 加特判;
  5. App 端:data/source/SourceKeys.kt、ui/more/SourceMeta.kt(顺序/图标/名称)、
     SourceBrandColors.kt、对应 ArchiveRepository + model fromJson、总览/榜单 UI
     分支、values/ + values-en/ 双语词条;
  6. 测试护栏同步:tests/test_common.py(SOURCE_KEYS 序)与
     tests/test_sources_registry.py(SOURCES 序 + 集合相等)。
"""

import os
import sys

# 子模块顶层 `from common import ...` 依赖 scripts/ 在 sys.path:经 fetch_data.py
# 脚本入口或 tests/conftest.py 导入时已满足,这里兜底其他调用方(如 backfill)。
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from .hackernews import fetch_hackernews
from .github_trending import fetch_github_trending
from .stormzhang_ai import fetch_stormzhang_ai
from .huggingface_papers import fetch_huggingface_papers
from .producthunt import fetch_producthunt
from .rundown_ai import fetch_rundown_ai
from .aihot_featured import fetch_aihot_featured
from .openai_anthropic_news import fetch_openai_anthropic_news

# ===== 数据源注册表:name → 抓取函数(顺序承重,见模块 docstring) =====

SOURCES = {
    "hackernews": fetch_hackernews,
    "github-trending": fetch_github_trending,
    "stormzhang-ai": fetch_stormzhang_ai,
    "huggingface-papers": fetch_huggingface_papers,
    "producthunt": fetch_producthunt,
    "rundown-ai": fetch_rundown_ai,
    "aihot-featured": fetch_aihot_featured,
    "openai-anthropic-news": fetch_openai_anthropic_news,
}

# 允许「空结果」的源:这些源在时间窗口内无新内容时正常返回空列表,不应视为源站故障。
# openai-anthropic-news 含 Claude Blog/Engineering 等月级更新子源,2 天窗口常无新文。
# 其余源空结果 = 选择器失效/接口异常(按失败处理,不落盘 0 条快照、由 previous_latest 兜底)。
EMPTY_OK_SOURCES = frozenset({"openai-anthropic-news"})
