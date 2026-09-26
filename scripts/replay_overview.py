#!/usr/bin/env python3
"""
总览生成质量回放评测(手动运维工具,不进 CI/流水线)。

用数据仓库里的历史快照 + 历史总览归档,真调 AI 重生成某一期的今日总览,输出
新旧对比与质量指标,供 prompt 改动后人工评读回归:

  - 新旧 digest 并排(旧 = 该期归档产物,新 = 本次重放);
  - 与上一期的重复率(carryover 命中数 / 标题对照);
  - digest 模板腔告警(overview_summary.digest_style_warnings);
  - breaking 硬校验视角:每条 breaking 的佐证源与 reason。

用法(在 scripts/ 下,需 AI_NEWS_HUB_AI_* 环境变量;缺配置只打印提示退出 0):
  .venv/bin/python replay_overview.py --repo ../repo --date 2026-09-01 --time 08-00
  .venv/bin/python replay_overview.py --repo ../repo            # 自动取最新一期
  .venv/bin/python replay_overview.py --repo ../repo --no-prev  # 对照:不注入上一期

--repo 指向数据仓检出(结构与推送后一致:<source>/<date>/<HH-MM>-data.json +
overview/<date>/<HH-MM>-data.json)。回放输入按「该槽位当刻各源最新快照」物化
(symlink 进临时目录,与生成时 _load_snapshots 看到的目录形态一致),历史批次
不会串到最新数据;回放全程只读,不动仓库任何文件。
"""

import argparse
import json
import os
import sys
import tempfile
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from common import BEIJING_TZ, SOURCE_KEYS

import overview_summary as ov
from ai_summary import ENV_BASE_URL, ENV_MODEL, ENV_API_KEY


def _latest_slot(repo_dir):
    """取归档里最新的一期 (date, time):扫描 overview/ 下字典序最大。"""
    ov_root = os.path.join(repo_dir, "overview")
    if not os.path.isdir(ov_root):
        return None, None
    slots = []
    for date_name in os.listdir(ov_root):
        date_dir = os.path.join(ov_root, date_name)
        if not os.path.isdir(date_dir):
            continue
        for fname in os.listdir(date_dir):
            if fname.endswith("-data.json"):
                slots.append((date_name, fname[:-len("-data.json")]))
    if not slots:
        return None, None
    slots.sort()
    return slots[-1]


def _previous_slot(repo_dir, date, time_str):
    """严格早于 (date, time) 的最近一期归档(上一期总览)。"""
    ov_root = os.path.join(repo_dir, "overview")
    slots = []
    for date_name in os.listdir(ov_root):
        date_dir = os.path.join(ov_root, date_name)
        if not os.path.isdir(date_dir):
            continue
        for fname in os.listdir(date_dir):
            if fname.endswith("-data.json"):
                slots.append((date_name, fname[:-len("-data.json")]))
    earlier = [s for s in sorted(slots) if s < (date, time_str)]
    return earlier[-1] if earlier else None


def _load_overview(repo_dir, date, time_str):
    path = os.path.join(repo_dir, "overview", date, f"{time_str}-data.json")
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def _materialize_inputs(repo_dir, date, time_str):
    """
    把「回放槽位当刻各源最新快照」symlink 进临时目录,保持 <source>/<date>/ 布局。

    生成时 overview_summary._load_snapshots 看到的是当时各源最新一笔;直接指向
    数据仓会取到全局最新(历史批次串数据)。这里按 (date, HH-MM) ≤ 回放槽位
    过滤后取各源最大一笔,与当刻口径一致。返回临时目录路径(调用方无需清理,
    symlink 悬空无害,系统临时目录自清)。
    """
    tmp = tempfile.mkdtemp(prefix="replay-overview-")
    for source in SOURCE_KEYS:
        src_dir = os.path.join(repo_dir, source)
        if not os.path.isdir(src_dir):
            continue
        cands = []
        for d in os.listdir(src_dir):
            dd = os.path.join(src_dir, d)
            if not os.path.isdir(dd):
                continue
            for f in os.listdir(dd):
                if f.endswith("-data.json"):
                    cands.append((d, f[:-len("-data.json")], os.path.join(dd, f)))
        eligible = [c for c in sorted(cands) if (c[0], c[1]) <= (date, time_str)]
        if not eligible:
            continue
        d, _t, real = eligible[-1]
        dst_dir = os.path.join(tmp, source, d)
        os.makedirs(dst_dir, exist_ok=True)
        os.symlink(os.path.abspath(real), os.path.join(dst_dir, os.path.basename(real)))
    return tmp


def _carryover_report(new_items, prev_items):
    """新 Top10 与上一期 Top10 的同事件对照(carryover 语义与流水线一致)。"""
    if not prev_items:
        return []
    prev_state = ov._carryover_state(prev_items)
    rows = []
    for e in new_items:
        if not e.get("breaking") and ov._is_carryover(
                e.get("url", ""), e.get("title", ""), e.get("source", ""), prev_state):
            rows.append(e.get("title", ""))
    return rows


def main():
    parser = argparse.ArgumentParser(description="总览生成质量回放评测(只读,不改数据仓)")
    parser.add_argument("--repo", default="../repo", help="数据仓检出目录(含各源快照与 overview/ 归档)")
    parser.add_argument("--date", help="回放批次日期(yyyy-MM-dd);缺省取归档最新一期")
    parser.add_argument("--time", help="回放批次时刻(HH-MM);与 --date 配对使用")
    parser.add_argument("--no-prev", action="store_true",
                        help="对照模式:不注入上一期(模拟旧版行为,其余仍走新 prompt)")
    args = parser.parse_args()

    from ai_summary import config_ready
    if not config_ready():
        missing = [k for k in (ENV_BASE_URL, ENV_MODEL, ENV_API_KEY) if not os.getenv(k)]
        print(f"[REPLAY] 缺少环境变量 {missing},跳过(回放是人工评测工具,不视为失败)")
        return 0

    date, time_str = args.date, args.time
    if not (date and time_str):
        date, time_str = _latest_slot(args.repo)
        if not date:
            print("[REPLAY] 数据仓里没有 overview/ 归档,无可回放")
            return 0
        print(f"[REPLAY] 未指定批次,自动取最新归档:{date} {time_str}")

    # 快照按回放槽位物化(见 _materialize_inputs 注释),归档直接读原文件
    inputs_dir = _materialize_inputs(args.repo, date, time_str)
    old = _load_overview(args.repo, date, time_str)
    # 上一期归档始终读(评测对照要用);--no-prev 只是「不注入生成」的对照开关
    prev_slot = _previous_slot(args.repo, date, time_str)
    prev_overview = _load_overview(args.repo, *prev_slot) if prev_slot else None
    if prev_slot:
        mode = "仅评测对照、不注入生成" if args.no_prev else "注入生成"
        print(f"[REPLAY] 上一期:{prev_slot[0]} {prev_slot[1]}({mode})")
    else:
        print("[REPLAY] 没有更早的归档,当首期回放")
    inject_prev = None if args.no_prev else prev_overview

    print(f"[REPLAY] 重放 {date} {time_str}(旧产物 generatedAt={old.get('generatedAt')})…")
    now = datetime.now(tz=BEIJING_TZ)
    new = ov.generate_overview(inputs_dir, now, inject_prev)
    if not new:
        print("[REPLAY] 重放失败(generate_overview 返回 None,详见上方错误输出)")
        return 1

    print("\n===== 旧 digest =====")
    print(old.get("digest", ""))
    print("\n===== 新 digest =====")
    print(new.get("digest", ""))

    print("\n===== digest 模板腔告警 =====")
    hits = ov.digest_style_warnings(new.get("digest", ""))
    print("命中:" + "、".join(hits) if hits else "无")
    if new.get("digest"):
        print(f"新 digest 长度:{len(new['digest'])} 字")

    print("\n===== 与上一期的同事件承接(carryover)=====")
    if prev_overview:
        rows = _carryover_report(new.get("items", []), prev_overview.get("items", []))
        print(f"{len(rows)}/10 条与上一期同事件(应为少数,且多为 breaking 或有进展):")
        for t in rows:
            print(f"  - {t}")
    else:
        print("(首期,无上一期归档)")

    print("\n===== 新 Top10 =====")
    for i, e in enumerate(new.get("items", []), 1):
        mark = "[头条]" if e.get("breaking") else "     "
        print(f"{i:>2}. {mark} {e.get('title')}")
        if e.get("breaking"):
            print(f"      reason: {e.get('breakingReason')}")
        elif e.get("comment"):
            print(f"      {e.get('comment')}")
        print(f"      —— {e.get('source')} · {e.get('metrics')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
