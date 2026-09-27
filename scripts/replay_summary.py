#!/usr/bin/env python3
"""分源摘要质量回放评测(手动运维工具,不进 CI/流水线)。

用数据仓库里的历史快照,真调 AI 重生成某源某批次的 ai_summary_v2,输出新旧
卡片对照与 titleEcho 核验观测,供 prompt 改动后人工评读回归(与
replay_overview.py 同范式):

  - 旧 = 快照内嵌的 ai_summary_v2(当期产物);新 = 以同一份 items 重跑
    summarize_source(回放评的是「生成质量」,继承语义不在此评);
  - titleEcho 覆盖率/错绑数由 summarize_source 的核验日志随行输出;
  - 指纹对照:该快照与更早一笔的 top-N 指纹是否一致(一致则正式流水线会走
    「摘要继承」不重跑 AI——帮助判断「这一期为什么新旧摘要长得一样」)。

用法(在 scripts/ 下,需 AI_NEWS_HUB_AI_* 环境变量;缺配置只打印提示退出 0):
  .venv/bin/python replay_summary.py --repo ../repo --source hackernews
  .venv/bin/python replay_summary.py --repo ../repo --source rundown-ai --date 2026-09-01 --time 08-00

--repo 指向数据仓检出;--date/--time 缺省取该源最新一笔快照。全程只读,
不动仓库任何文件。
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ai_summary
from ai_summary import ENV_BASE_URL, ENV_MODEL, ENV_API_KEY
from common import SOURCE_KEYS


def _snapshots_of(repo_dir, source):
    """该源全部快照槽位 (date, time, path),按 (date, time) 升序。"""
    src_root = os.path.join(repo_dir, source)
    if not os.path.isdir(src_root):
        return []
    out = []
    for d in os.listdir(src_root):
        dd = os.path.join(src_root, d)
        if not os.path.isdir(dd):
            continue
        for f in os.listdir(dd):
            if f.endswith("-data.json"):
                out.append((d, f[:-len("-data.json")], os.path.join(dd, f)))
    return sorted(out)


def _print_cards(title, cards):
    print(f"\n===== {title} =====")
    if not cards:
        print("(无)")
        return
    for c in cards:
        print(f"- {c.get('title', '')}")
        print(f"  {c.get('desc', '')}")
        print(f"  —— {c.get('url', '')}")


def main():
    parser = argparse.ArgumentParser(description="分源摘要质量回放评测(只读,不改数据仓)")
    parser.add_argument("--repo", default="../repo", help="数据仓检出目录(含各源快照)")
    parser.add_argument("--source", required=True, choices=SOURCE_KEYS, help="要回放的源 key")
    parser.add_argument("--date", help="回放批次日期(yyyy-MM-dd);缺省取该源最新一笔")
    parser.add_argument("--time", help="回放批次时刻(HH-MM);与 --date 配对使用")
    args = parser.parse_args()

    from ai_summary import config_ready
    if not config_ready():
        missing = [k for k in (ENV_BASE_URL, ENV_MODEL, ENV_API_KEY) if not os.getenv(k)]
        print(f"[REPLAY] 缺少环境变量 {missing},跳过(回放是人工评测工具,不视为失败)")
        return 0

    snaps = _snapshots_of(args.repo, args.source)
    if not snaps:
        print(f"[REPLAY] 数据仓里没有 {args.source} 快照,无可回放")
        return 0
    if args.date and args.time:
        eligible = [s for s in snaps if (s[0], s[1]) <= (args.date, args.time)]
        if not eligible:
            print(f"[REPLAY] {args.date} {args.time} 之前没有 {args.source} 快照")
            return 0
    else:
        eligible = snaps
        print(f"[REPLAY] 未指定批次,自动取最新一笔:{eligible[-1][0]} {eligible[-1][1]}")

    date, time_str, path = eligible[-1]
    with open(path, "r", encoding="utf-8") as f:
        snap = json.load(f)
    items = snap.get("items") or []
    old_cards = snap.get("ai_summary_v2") or []
    print(f"[REPLAY] 重放 {args.source} {date} {time_str}"
          f"(items {len(items)} 条,旧摘要 {len(old_cards)} 条)…")

    # 指纹对照:与更早一笔一致时,正式流水线走「摘要继承」不重跑 —— 新旧摘要
    # 长得一样不等于回放无意义,但解读时要区分「继承」与「重跑仍相似」
    earlier = [s for s in snaps if (s[0], s[1]) < (date, time_str)]
    if earlier:
        with open(earlier[-1][2], "r", encoding="utf-8") as f:
            prev_items = json.load(f).get("items") or []
        same = (ai_summary.summary_fingerprint(args.source, items)
                == ai_summary.summary_fingerprint(args.source, prev_items))
        note = "一致(正式流水线会继承,不重跑 AI)" if same else "不一致(会重新生成)"
        print(f"[REPLAY] 与上一笔({earlier[-1][0]} {earlier[-1][1]})的 top-N 指纹:{note}")

    _print_cards("旧摘要(快照产物)", old_cards)
    new_cards = ai_summary.summarize_source(args.source, items)
    _print_cards("新摘要(本次重放)", new_cards or [])
    if new_cards is None:
        print("[REPLAY] 重放失败(summarize_source 返回 None,详见上方错误输出)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
