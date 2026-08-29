"""流水线测试共享夹具。

惯例(对齐 docs/agents/testing.md 的 Python 侧小节):
 - sys.path 插入 scripts/ 目录,与各脚本自身的 path-insert 惯例一致,直接 import 被测模块;
 - frozen_now:钉死 fetch_data.now_cst 为 2026-08-29 11:01 北京时间(与 fixtures/ 的
   真实批次同刻),断言「快照目录日期 / fetched_at / index.updated_at」才有确定值;
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


@pytest.fixture
def frozen_now(monkeypatch):
    """钉死 fetch_data 内的当前北京时间(注意 patch 的是 fetch_data 命名空间的绑定)。"""
    monkeypatch.setattr(fetch_data, "now_cst", lambda: FROZEN_NOW)
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
