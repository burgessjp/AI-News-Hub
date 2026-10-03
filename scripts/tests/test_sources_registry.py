"""sources 注册表护栏 —— 两套「源顺序」契约的钉子。

SOURCES(抓取/索引键序,流水线概念)与 common.SOURCE_KEYS(展示序,App 端
DEFAULT_SOURCE_ORDER)刻意不同;任何一头被"顺手对齐"到另一头都会悄悄改变
index.json latest 键序或 App 展示序,必须在有意识决策下进行,所以分别钉死。
SOURCE_KEYS 展示序本体已在 test_common.py 钉过,此处只钉 SOURCES 侧 + 集合相等。
2026-10 起另钉五张分源配置表的覆盖完整性(新增源漏配点位在此红),见下方 coverage 测试。
"""

import ai_summary as asm
import common
import fetch_data as fd
import overview_summary as ovs
import trend_keywords as tk
from sources import EMPTY_OK_SOURCES, SOURCES


def test_sources_键集合与_source_keys_一致():
    """common.SOURCE_KEYS 是唯一真相源:少了 = 源没注册(该源永远不跑),
    多了 = key 拼错(永不被 SOURCE_KEYS 消费方承认)。"""
    assert set(SOURCES) == set(common.SOURCE_KEYS)


def test_sources_字典序钉死():
    """SOURCES 顺序三处承重:main 串行抓取顺序、index.json latest 键序、
    history.json 源键序(见 sources/__init__.py docstring)。"""
    assert list(SOURCES) == [
        "hackernews",
        "github-trending",
        "stormzhang-ai",
        "huggingface-papers",
        "producthunt",
        "rundown-ai",
        "aihot-featured",
        "openai-anthropic-news",
    ]


def test_empty_ok_是_sources_子集且值全可调用():
    # 豁免集合里出现未注册的 key = 拼写错误,静默失效,必须当场暴露
    assert EMPTY_OK_SOURCES <= set(SOURCES)
    assert all(callable(fn) for fn in SOURCES.values())


def test_fetch_with_retry_统一入口与_limit_分派():
    """契约冒烟:注册表的抓取器经 fetch_with_retry 统一无参入口调用,
    hackernews 的 limit 关键字由闭包分派(成功路径不走 sleep,无需去退避)。"""
    calls = []

    def hn_stub(limit=20):
        calls.append(limit)
        return ([{"id": 1}], {})

    items, meta = fd.fetch_with_retry("hackernews", hn_stub, limit_hn=5)
    assert calls == [5]
    assert items == [{"id": 1}] and meta == {}

    def plain_stub():
        calls.append("plain")
        return ([], {"feedTitle": "x"})

    items, meta = fd.fetch_with_retry("rundown-ai", plain_stub)
    assert calls == [5, "plain"]
    assert items == [] and meta == {"feedTitle": "x"}


# 每源最小可接受条目(字段对齐各源 fetcher 契约):钉住总览/趋势的字段映射
# 对每个注册源都有分支 —— 两处的 else 分支都是静默跳过(该源悄悄退出候选池/
# 统计,无任何告警),新增源漏配分支只有这条测试当场红。
MINIMAL_ITEMS = {
    "hackernews": {"title": "T", "target_url": "https://x.dev/a",
                   "score": 1, "descendants": 0, "time": 0},
    "github-trending": {"owner": "o", "name": "r", "url": "https://x.dev/g"},
    "stormzhang-ai": {"summary": "中文标题", "url": "https://x.dev/s",
                      "source": "X", "time": ""},
    "huggingface-papers": {"title": "T", "url": "https://x.dev/p",
                           "upvotes": 1, "published": ""},
    "producthunt": {"name": "App", "url": "https://x.dev/p",
                    "votesCount": 1, "commentsCount": 0},
    "rundown-ai": {"title": "T", "url": "https://x.dev/r", "subtitle": ""},
    "aihot-featured": {"title": "T", "url": "https://x.dev/f", "score": 1},
    "openai-anthropic-news": {"title": "T", "url": "https://x.dev/o",
                              "vendor": "OpenAI"},
}


def test_分源配置表覆盖全部源():
    """五张按源 key 索引的配置表,少一个源 = 该源在对应环节静默缺失/崩溃:

      - SYSTEM_PROMPTS / USER_PROMPT_BUILDERS 缺 key → summarize_source 里
        KeyError 穿透线程池,整批 fetch 直接失败(过响);
      - SOURCE_TOP_N 缺 key → 静默回落 15(与校准值不符);
      - SOURCE_MIN_ITEMS 缺 key → 静默回落 0,健康哨兵对该源失效;
      - SOURCE_TITLES 缺 key → prompt 里该源标题兜底为 key 本身(质量静默降级)。
    多一个 key = 拼错,永不被消费,同样当场红。
    """
    keys = set(common.SOURCE_KEYS)
    assert set(asm.SYSTEM_PROMPTS) == keys
    assert set(asm.USER_PROMPT_BUILDERS) == keys
    assert set(asm.SOURCE_TOP_N) == keys
    assert set(fd.SOURCE_MIN_ITEMS) == keys
    assert set(ovs.SOURCE_TITLES) == keys


def test_metric_sources_钉死当前五个有指标源():
    """与 SYSTEM_PROMPT「热度档位」一节的有指标源清单同口径;多/少一个都会
    改变 breaking 硬校验语义,必须是有意识决策。"""
    assert ovs.METRIC_SOURCES == {
        "hackernews", "github-trending", "huggingface-papers",
        "producthunt", "aihot-featured",
    }


def test_每源最小条目能被总览与趋势字段映射接收():
    for src, item in MINIMAL_ITEMS.items():
        snap = {"items": [item], "fetched_at_ms": 1}
        assert ovs._extract_items(src, snap), f"overview._extract_items 无 {src} 分支或产出为空"
        assert tk._item_fields(src, item) is not None, f"trend._item_fields 无 {src} 分支"


def test_源模块适配契约齐备():
    """每个注册源模块必须导出五键 META 与适配函数(raw_heat 仅指标源):
    缺一项 = 某消费方 KeyError/AttributeError(过响)或静默缺省(过轻)。"""
    from sources import SOURCE_MODULES

    required_meta = {"display_title", "empty_ok", "min_items", "top_n", "has_metrics"}
    required_fns = ("item_url", "item_title", "overview_fields", "trend_fields")
    for name, mod in SOURCE_MODULES.items():
        assert required_meta <= set(mod.META), (name, sorted(mod.META))
        for fn in required_fns:
            assert callable(getattr(mod, fn, None)), f"{name}.{fn} 缺失"
        assert callable(getattr(mod, "fetch", None)), f"{name}.fetch 缺失"
        if mod.META["has_metrics"]:
            assert callable(getattr(mod, "raw_heat", None)), f"{name}.raw_heat 缺失(指标源必配)"
        # SOURCE_KEY 必须与注册 key 一致(注册表 key 即由它派生,断言防手滑改表)
        assert mod.SOURCE_KEY == name
