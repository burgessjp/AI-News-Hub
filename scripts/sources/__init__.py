"""数据源抓取器包 —— 每源一个模块,本 __init__ 组装注册表。

抓取器契约(新增/修改源必读):
  - 每源一个模块 sources/<name>.py,导出 fetch_<name>()(无必需参数;
    hackernews 额外接受 limit 关键字,由 fetch_data.fetch_with_retry 闭包分派)
    及统一别名 fetch = fetch_<name>(注册表经它组装)。
  - 返回 (items: list[dict], meta: dict);meta 的键值由 fetch_data.write_snapshot
    拍扁进快照顶层(如 stormzhang 的 pageDate)。
  - 失败抛异常(网络/解析/CF 拦截),由 fetch_data.fetch_with_retry 统一 3 次重试;
    HTTP 一律走 .httpio 的 fetch_text / SESSION(UA 与 CF 检测收口在那一层)。
  - 空结果语义在 main 层判定:默认 = 失败(疑似选择器失效);只有 EMPTY_OK_SOURCES
    里的源(时间窗口内可无新文)允许正常返回空列表。

适配层契约(2026-10 收口:每源字段知识单点住在源模块,消费方一律派生/委托):
  - SOURCE_KEY:本源注册 key(注册表键由它派生,注册处不手写源名);
  - META 五键:display_title(总览 prompt 源标题)/ empty_ok(空结果合法性)/
    min_items(抓取健康哨兵下限)/ top_n(摘要喂 AI 条数)/ has_metrics(热度档位
    有无真实指标)—— fetch_data / ai_summary / overview_summary 的分源配置表
    全部由此派生;
  - item_url / item_title:落地页 URL 与标题字段口径(摘要回填、fingerprint、
    trend 共用;注意消费方微差:overview 的 HN URL 是纯 target_url 无兜底,
    trend 的 github 标题带 strip("/")——各自的 overview_fields / trend_fields
    里保留自己的表达式);
  - overview_fields(o, fallback_date_key) -> (title, url, metrics, blurb, date_key):
    总览候选池字段;指标源另配 raw_heat(o) -> float;
  - trend_fields(o) -> (text, title, url):趋势统计字段(空值判定由消费方共享)。

items 通用字段:title / url 必有,summary 可空;多数源有 rank(1 起);
publishedAt 形如 'YYYY-MM-DD' 或 ISO(可空,无日期条目排序沉底)。各源差异字段
见各自模块 docstring;字段命名 camelCase,对齐 App 端各 model 的 fromJson。

SOURCES 顺序约定(承重,勿随手重排):字典顺序 = main 串行抓取顺序 =
index.json latest 键序 = history.json 源键序;与 common.SOURCE_KEYS 的展示序
(App 端 DEFAULT_SOURCE_ORDER)刻意不同——那是 UI 概念,与本表无关。
scripts/tests/test_sources_registry.py 把两套顺序分别钉死。

新增数据源 checklist(全链路,一处不落):
  1. scripts/common.py 的 SOURCE_KEYS 加 key(决定 App 展示序);
  2. 新建 sources/<name>.py:SOURCE_KEY + fetch_<name> + fetch 别名 + META(五键)
     + item_url/item_title/overview_fields/trend_fields(指标源另加 raw_heat),
     在本文件 _SOURCE_MODULES 列表按承重序注册;空结果合法的源 META["empty_ok"]=True;
  3. ai_summary.py:SYSTEM_PROMPTS 加 prompt + USER_PROMPT_BUILDERS 加 builder
     (经 _emit 构行,titleEcho 锚点自动登记);
  4. App 端:data/source/SourceKeys.kt、ui/more/SourceMeta.kt(顺序/图标/名称)、
     SourceBrandColors.kt、对应 ArchiveRepository + model fromJson、总览/榜单 UI
     分支、values/ + values-en/ 双语词条;
  5. 测试护栏自动覆盖:test_sources_registry 的五表完整性 + 适配契约测试当场红;
     test_common.py(SOURCE_KEYS 序)仍需人工确认展示序决策。
"""

import os
import sys

# 子模块顶层 `from common import ...` 依赖 scripts/ 在 sys.path:经 fetch_data.py
# 脚本入口或 tests/conftest.py 导入时已满足,这里兜底其他调用方(如 backfill)。
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from . import (
    aihot_featured,
    github_trending,
    hackernews,
    huggingface_papers,
    openai_anthropic_news,
    producthunt,
    rundown_ai,
    stormzhang_ai,
)

# ===== 数据源注册表(顺序承重,见模块 docstring) =====
# key 来自各模块内声明的 SOURCE_KEY —— 注册处不再手写源名,typo 在此即暴露。

SOURCE_MODULES = {m.SOURCE_KEY: m for m in (
    hackernews,
    github_trending,
    stormzhang_ai,
    huggingface_papers,
    producthunt,
    rundown_ai,
    aihot_featured,
    openai_anthropic_news,
)}
# name → 抓取函数(旧注册表语义不变,顺序 = SOURCE_MODULES 序)
SOURCES = {k: m.fetch for k, m in SOURCE_MODULES.items()}
# name → META(下游配置表派生源)
SOURCE_META = {k: m.META for k, m in SOURCE_MODULES.items()}
# 允许「空结果」的源:这些源在时间窗口内无新内容时正常返回空列表,不应视为源站故障
# (openai-anthropic-news 含 Claude Blog/Engineering 等月级更新子源,2 天窗口常无新文)。
# 其余源空结果 = 选择器失效/接口异常(按失败处理,不落盘 0 条快照、由 previous_latest 兜底)。
EMPTY_OK_SOURCES = frozenset(k for k, m in SOURCE_META.items() if m["empty_ok"])
