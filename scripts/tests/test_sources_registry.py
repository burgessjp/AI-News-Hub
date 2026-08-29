"""sources 注册表护栏 —— 两套「源顺序」契约的钉子。

SOURCES(抓取/索引键序,流水线概念)与 common.SOURCE_KEYS(展示序,App 端
DEFAULT_SOURCE_ORDER)刻意不同;任何一头被"顺手对齐"到另一头都会悄悄改变
index.json latest 键序或 App 展示序,必须在有意识决策下进行,所以分别钉死。
SOURCE_KEYS 展示序本体已在 test_common.py 钉过,此处只钉 SOURCES 侧 + 集合相等。
"""

import common
import fetch_data as fd
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
