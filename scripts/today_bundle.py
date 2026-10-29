#!/usr/bin/env python3
"""
今日聚合包(today.json)—— 「今天」页冷启动单请求的数据载体。

push 阶段(write_weekly 之后同位置挂载)从仓库 checkout 组装:latest_overview
(index.json)+ 各源 latest 快照的 ai_summary_v2 + 最新一期周刊(weekly.json
原文)→ 根级 today.json。App 端「今天」页命中即 1 次请求拿全首屏(Hero /
Top10 / 分源摘要 / 关注语料 / 周刊入口),未命中(404 = 流水线旧版本 /
组装失败未写)自动回退既有 index + 快照路径,新旧两态并存灰度安全。

组装规则:
  - overview:index.latest_overview **原样内嵌**(App 端 parseDigest 零改动);
    缺失 → 键缺席,App 总览段走旧路径,分源段仍可用
  - sources:index.latest 各源 → 快照 ai_summary_v2 全量条目(「查看全部」
    弹层同源)+ fetched_at_ms(App 已查看指纹与断供判定都依赖);
    快照缺失 / 无 ai_summary_v2(v1 纯文本旧批次)→ 该源键缺席,App 单源回退
  - weekly:weekly.json 存在则原文内嵌(App 周刊入口与最新期直达);
    缺失 → 键缺席,App 周刊走旧路径
  - 顶层 generatedAt = 组装时刻(毫秒);dataFetchedAt = 各输入快照
    fetched_at_ms 与 latest_overview.dataFetchedAt 的最大值;schema = 1

失败语义:组装中任何异常只告警不抛(同 write_trends / write_weekly 挂载
语义,不阻断推送);today.json「成功才写」—— 组装失败保持上一版。
体量:8 源摘要 + Top10 + 周刊 ≈ 100~200KB,批次覆盖写,无继承语义。
"""

import json
import os
import sys
import time

BUNDLE_NAME = "today.json"
SCHEMA = 1


def write_today_bundle(repo_dir):
    """组装并写根级 today.json。成功返回 True;任何失败告警返回 False(不抛)。"""
    try:
        return _write(repo_dir)
    except Exception as e:  # noqa: BLE001 —— 挂载点约定:只告警不阻断推送
        print(f"[TODAY] 聚合包组装失败(不阻断推送,保持上一版):"
              f"{type(e).__name__}: {e}", file=sys.stderr)
        return False


def _write(repo_dir):
    index = _load_json(repo_dir, "index.json")
    if index is None:
        print("[TODAY] index.json 缺失或损坏,跳过 today.json", file=sys.stderr)
        return False

    overview = index.get("latest_overview") or None
    sources, fetched_max = _collect_sources(repo_dir, index.get("latest") or {})
    if overview is None and not sources:
        print("[TODAY] 无 overview 也无任何源快照,不写 today.json", file=sys.stderr)
        return False

    bundle = {
        "schema": SCHEMA,
        "generatedAt": int(time.time() * 1000),
        "dataFetchedAt": max(
            [fetched_max] + ([overview.get("dataFetchedAt", 0)] if overview else [])
        ),
    }
    if overview is not None:
        bundle["overview"] = overview
    if sources:
        bundle["sources"] = sources
    weekly = _load_json(repo_dir, "weekly.json")
    if weekly is not None:
        bundle["weekly"] = weekly

    _atomic_write(repo_dir, bundle)
    n_src = len(sources)
    print(f"[TODAY] today.json 已组装:{n_src} 源,overview "
          f"{'✓' if overview is not None else '✗'},weekly "
          f"{'✓' if weekly is not None else '✗'}")
    return True


def _collect_sources(repo_dir, latest):
    """逐源读 latest 快照的 ai_summary_v2 + fetched_at_ms;缺一即省略该源。"""
    sources = {}
    fetched_max = 0
    for key, relpath in latest.items():
        snapshot = _load_json(repo_dir, os.path.join(key, relpath))
        if snapshot is None:
            print(f"[TODAY] 源 {key} 快照读取失败,键缺席(App 单源回退)",
                  file=sys.stderr)
            continue
        summary = snapshot.get("ai_summary_v2")
        if not summary:
            print(f"[TODAY] 源 {key} 无 ai_summary_v2(v1 纯文本批次),键缺席",
                  file=sys.stderr)
            continue
        fetched = int(snapshot.get("fetched_at_ms", 0))
        fetched_max = max(fetched_max, fetched)
        sources[key] = {"fetchedAtMs": fetched, "summary": summary}
    return sources, fetched_max


def _load_json(repo_dir, relpath):
    """读仓库内 JSON 文件;缺失/损坏/非对象返回 None。"""
    path = os.path.join(repo_dir, relpath)
    if not os.path.isfile(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else None
    except (OSError, ValueError):
        return None


def _atomic_write(repo_dir, bundle):
    """tmp 写 + os.replace:中断不会留半截文件(半截会被 App 当损坏回退)。"""
    path = os.path.join(repo_dir, BUNDLE_NAME)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(bundle, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)
