"""流水线测试共享夹具。

惯例(对齐 docs/agents/testing.md 的 Python 侧小节):
 - sys.path 插入 scripts/ 目录,与各脚本自身的 path-insert 惯例一致,直接 import 被测模块;
 - frozen_now:钉死当前北京时间为 2026-08-29 11:01(与 fixtures/ 的真实批次同刻),
   patch 面 = fetch_data + sources 包内各模块的 now_cst 绑定(包迭代,新增源自动覆盖),
   断言「快照目录日期 / fetched_at / index.updated_at」才有确定值;
 - no_retry_backoff:把 common.retry(经 fetch_data 命名空间绑定的引用)替换为直调版,
   跳过 2s/4s 指数退避的真实 sleep —— 测试里重试路径不该等墙钟。
"""

import sys
from datetime import datetime
from pathlib import Path

import pytest

SCRIPTS_DIR = Path(__file__).resolve().parent.parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import fetch_data  # noqa: E402  (需先完成 sys.path 注入)
from common import BEIJING_TZ  # noqa: E402

FIXTURES_DIR = Path(__file__).resolve().parent / "fixtures"

FROZEN_NOW = datetime(2026, 8, 29, 11, 1, tzinfo=BEIJING_TZ)


def _iter_sources_modules():
    """遍历 sources 包全部子模块(抓取器按源拆包后,新增源模块自动进入 frozen_now 的 patch 面)。"""
    import importlib
    import pkgutil

    import sources

    return [
        importlib.import_module(f"sources.{info.name}")
        for info in pkgutil.iter_modules(sources.__path__)
    ]


@pytest.fixture
def frozen_now(monkeypatch):
    """钉死当前北京时间。

    注意 patch 的是各模块命名空间里 `from common import now_cst` 的绑定
    (import 时拷贝,patch common.now_cst 不生效):fetch_data(main 的时间戳)
    与 sources 包内绑定了 now_cst 的模块(vendor 源的 2 天窗口)一并冻结。"""
    for mod in [fetch_data, *_iter_sources_modules()]:
        if hasattr(mod, "now_cst"):
            monkeypatch.setattr(mod, "now_cst", lambda: FROZEN_NOW)
    return FROZEN_NOW


@pytest.fixture
def no_retry_backoff(monkeypatch):
    """retry 直调版:fn 抛什么立即抛什么,不重试不 sleep(重试骨架另有 test_common 钉)。"""

    def instant(fn, *, attempts=3, backoff_base=2, log_tag="RETRY", on_exhausted=None):
        return fn()

    monkeypatch.setattr(fetch_data, "retry", instant)


def load_fixture(relpath: str):
    """读 fixtures/ 下的 JSON(真实批次裁剪件,规约见 docs/agents/testing.md)。"""
    import json

    with open(FIXTURES_DIR / relpath, encoding="utf-8") as f:
        return json.load(f)
