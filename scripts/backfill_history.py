#!/usr/bin/env python3
"""
维护根级 history.json 历史索引(按日期寻址,已拆出 index.json 为独立文件)。

两个用途:
  1) 回填/重建(2026-07-19 已执行过一次,当时写入 index.json 内联字段;拆分后
     改写根级 history.json):「历史摘要」功能上线前,数据仓库已按
     <source>/<YYYY-MM-DD>/<HH-MM>-data.json 落盘历史快照(push_data.py 只增不删),
     但索引未建。扫描浅克隆里的全部日期目录,按流水线同款规则(每天取当日最后一次
     快照、不早于 HISTORY_START_DATE、每源保留最近 HISTORY_RETENTION_DAYS 天)
     生成 history.json 并推送;index.json 残留的旧内联 history 字段一并移除。
  2) 清理起始日之前的目录(--prune,2026-07-19 已执行):删除仓库里早于
     HISTORY_START_DATE 的日期目录,与重建后的索引同一 commit 推送。
     push_data.py 的 _overlay 只增不删,清理只能在本脚本里显式做。

日常无需执行 —— 流水线每次运行的 build_history 会自动与旧索引合并;
仅当索引因故整体丢失(重跑回填)或需要清理旧目录(--prune)时使用。

用法:
  export GITCODE_TOKEN=...
  python3 scripts/backfill_history.py            # 克隆 → 重建 history.json → 提交推送
  python3 scripts/backfill_history.py --prune    # 同上,且先删除起始日之前的日期目录
  python3 scripts/backfill_history.py --dry-run [--prune]  # 只扫描打印,不写入不推送
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fetch_data import SOURCES, _scan_history, HISTORY_RETENTION_DAYS, HISTORY_START_DATE
from common import BEIJING_TZ as CST
from push_data import (
    DEFAULT_BRANCH,
    DEFAULT_REPO_URL,
    ENV_GITCODE_TOKEN,
    _inject_token,
    run,
)


def build_history(repo_dir):
    """按 write_index 同款规则生成 history:每天取当日最后快照,
    过滤起始日之前,按日期倒序保留 31 天。"""
    history = {}
    for name in SOURCES:
        scanned = _scan_history(repo_dir, name)
        keep = [d for d in sorted(scanned, reverse=True)
                if d >= HISTORY_START_DATE][:HISTORY_RETENTION_DAYS]
        history[name] = {d: scanned[d] for d in keep}
    return history


def prune_old_snapshots(repo_dir, dry_run):
    """删除 <source>/<date> 中 date 早于 HISTORY_START_DATE 的目录
    (dry_run 时只列出不删),返回删除/待删清单。"""
    removed = []
    for name in SOURCES:
        src_root = os.path.join(repo_dir, name)
        if not os.path.isdir(src_root):
            continue
        for date_dir in sorted(os.listdir(src_root)):
            if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date_dir):
                continue
            if date_dir >= HISTORY_START_DATE:
                continue
            full = os.path.join(src_root, date_dir)
            if not os.path.isdir(full):
                continue
            removed.append(f"{name}/{date_dir}")
            if not dry_run:
                shutil.rmtree(full)
    return removed


def main():
    parser = argparse.ArgumentParser(description="维护 index.json 的 history 历史索引")
    parser.add_argument("--prune", action="store_true",
                        help=f"先删除仓库里早于 {HISTORY_START_DATE} 的日期目录,再重建 history")
    parser.add_argument("--dry-run", action="store_true",
                        help="只扫描打印(复用现有 repo/ 或匿名克隆),不写入不推送")
    args = parser.parse_args()

    token = os.environ.get(ENV_GITCODE_TOKEN)
    if not token and not args.dry_run:
        print(f"[FATAL] 缺少环境变量 {ENV_GITCODE_TOKEN},无法推送。"
              f"请先 export {ENV_GITCODE_TOKEN}=<gitcode 个人访问令牌>", file=sys.stderr)
        return 2

    # 浅克隆目标分支(与 push_data 同约定:repo/ 为推送前浅克隆目录,gitignored)
    work_root = os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir))
    repo_dir = os.path.join(work_root, "repo")
    if args.dry_run and os.path.isdir(repo_dir):
        print(f"[DRY] 复用现有克隆 {repo_dir}")
    else:
        if os.path.isdir(repo_dir):
            shutil.rmtree(repo_dir)
        # dry-run 无 token 时退化为匿名克隆(公开仓库只读)
        url = _inject_token(DEFAULT_REPO_URL, token) if token else DEFAULT_REPO_URL
        run(["git", "clone", "--depth", "1", "--branch", DEFAULT_BRANCH, url, repo_dir])

    # --prune:删除起始日之前的日期目录(dry-run 只列出不删)
    pruned = []
    if args.prune:
        pruned = prune_old_snapshots(repo_dir, dry_run=args.dry_run)
        verb = "将删除" if args.dry_run else "已删除"
        print(f"[PRUNE] {verb} {len(pruned)} 个起始日({HISTORY_START_DATE})之前的日期目录:")
        for p in pruned:
            print(f"  - {p}")

    history = build_history(repo_dir)
    print("[SCAN] 各源可索引的历史天数:")
    for name, dates in history.items():
        if dates:
            ds = sorted(dates)
            print(f"  {name:<20} {len(dates):>2} 天  {ds[0]} ~ {ds[-1]}")
        else:
            print(f"  {name:<20} 无历史快照")
    if args.dry_run:
        print("[DRY] 不写入不推送")
        return 0

    # 写独立 history.json(历史索引已拆出 index.json);index.json 里若残留
    # 旧格式内联 history 字段,一并移除;时间戳同步刷新
    history_path = os.path.join(repo_dir, "history.json")
    with open(history_path, "w", encoding="utf-8") as f:
        json.dump(history, f, ensure_ascii=False, indent=2)
    index_path = os.path.join(repo_dir, "index.json")
    with open(index_path, "r", encoding="utf-8") as f:
        index = json.load(f)
    now = datetime.now(CST)
    index.pop("history", None)
    index["updated_at"] = now.strftime("%Y-%m-%dT%H:%M:%S%z")
    index["updated_at_ms"] = int(now.timestamp() * 1000)
    with open(index_path, "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=2)

    # bot 身份与提交风格对齐 push_data.py
    run(["git", "config", "user.name", "github-actions[bot]"], cwd=repo_dir)
    run(["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"],
        cwd=repo_dir)
    # prune 过用 add -A 连目录删除一起暂存;否则只动 index.json / history.json
    add_cmd = (["git", "add", "-A"] if pruned
               else ["git", "add", "index.json", "history.json"])
    run(add_cmd, cwd=repo_dir)
    diff = subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=repo_dir)
    if diff.returncode == 0:
        print("[PUSH] 无改动,跳过提交")
        return 0
    action = "prune+backfill" if pruned else "backfill"
    msg = f"chore(data): {action} history index ({now.strftime('%Y-%m-%d_%H:%M')} CST)"
    run(["git", "commit", "-m", msg], cwd=repo_dir)
    run(["git", "push", "origin", DEFAULT_BRANCH], cwd=repo_dir)
    print(f"[PUSH] 已推送: {msg}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
