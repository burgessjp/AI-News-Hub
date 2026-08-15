#!/usr/bin/env python3
"""
一次性运维脚本:从数据仓库 git 历史回填总览(overview)按日归档,并清理已下线源目录。

背景:latest_overview 历史上只内嵌在 index.json 顶层、每轮整体覆盖,git 工作区里
没有按日期归档的总览文件。但数据仓库的 git 历史 中,
每次提交的 index.json 都带着当日的 latest_overview —— 本脚本遍历这些历史版本,
把每份总览(以 generatedAt 去重,继承产生的重复值天然合并)落成
overview/<YYYY-MM-DD>/<HH-MM>-data.json,日期/时刻由 generatedAt 换算北京时间。

同时做的事(同一 commit 推送):
  1. 回填历史总览文件(早于 HISTORY_START_DATE 的丢弃,与 history 索引口径一致)
  2. 重建 index.json 的 overview_history(复用 fetch_data._scan_history 同款规则)
  3. 删除已下线源 linuxdo/ 的遗留目录(push_data._overlay 只增不删,需显式删)

此后日常流水线(fetch_data.write_overview_snapshot / write_index)自动接力维护,
本脚本无需再跑。

用法:
  export GITCODE_TOKEN=...
  python3 scripts/backfill_overview.py --dry-run   # 只克隆+打印将写/删的清单,不推送
  python3 scripts/backfill_overview.py             # 真跑:写文件 + 删 linuxdo + 推送
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fetch_data import (
    OVERVIEW_RETENTION_DAYS,
    _scan_history,
    HISTORY_START_DATE,
)
from common import BEIJING_TZ as CST
from push_data import (
    DEFAULT_BRANCH,
    DEFAULT_REPO_URL,
    ENV_GITCODE_TOKEN,
    _inject_token,
    run,
)

# 已下线源(SOURCE_KEYS 无此 key)的遗留目录:overlay 只增不删,回填时顺手清掉
RETIRED_SOURCE_DIRS = ["linuxdo"]


def collect_overviews_from_history(repo_dir):
    """
    遍历改过 index.json 的所有提交(时间正序),提取每版的 latest_overview。

    返回 {generatedAt(毫秒) : overview dict}。同一份总览会随「生成失败继承」
    在后续多个提交里重复出现 —— 以 generatedAt(生成时刻,随对象携带)去重,
    天然合并为一份数据;generatedAt 相同即内容相同,后见覆盖先见无损。
    """
    log = run(
        ["git", "log", "--reverse", "--format=%H", "--", "index.json"],
        cwd=repo_dir,
    )
    commits = log.stdout.split()
    print(f"[SCAN] index.json 历史版本 {len(commits)} 个提交")
    overviews = {}
    for i, commit in enumerate(commits, 1):
        try:
            text = run(["git", "show", f"{commit}:index.json"], cwd=repo_dir).stdout
            data = json.loads(text)
        except Exception as e:  # 单个历史版本损坏不致命,跳过继续
            print(f"[WARN] {commit[:8]} 版本解析失败,跳过:{type(e).__name__}: {e}",
                  file=sys.stderr)
            continue
        overview = data.get("latest_overview")
        if not isinstance(overview, dict):
            continue  # 总览功能上线前的早期版本
        generated_at = overview.get("generatedAt")
        if not isinstance(generated_at, int) or not isinstance(overview.get("items"), list):
            continue
        overviews[generated_at] = overview
    print(f"[SCAN] 提取到 {len(overviews)} 份去重后的历史总览")
    return overviews


def write_overview_files(repo_dir, overviews, dry_run):
    """
    把历史总览按 generatedAt(北京时间)落盘 overview/<date>/<HH-MM>-data.json。

    早于 HISTORY_START_DATE 的丢弃(与 history 索引口径一致,旧日期的快照源
    覆盖不全,对应总览参考价值有限)。返回待写/已写文件清单(倒序便于核对)。
    """
    written = []
    for generated_at in sorted(overviews, reverse=True):
        dt = datetime.fromtimestamp(generated_at / 1000, tz=CST)
        date_str = dt.strftime("%Y-%m-%d")
        if date_str < HISTORY_START_DATE:
            continue
        time_str = dt.strftime("%H-%M")
        overview = overviews[generated_at]
        dir_path = os.path.join(repo_dir, "overview", date_str)
        file_path = os.path.join(dir_path, f"{time_str}-data.json")
        written.append((date_str, time_str, overview.get("digest", "")))
        if not dry_run:
            os.makedirs(dir_path, exist_ok=True)
            with open(file_path, "w", encoding="utf-8") as f:
                json.dump(overview, f, ensure_ascii=False, indent=2)
    return written


def remove_retired_dirs(repo_dir, dry_run):
    """删除已下线源的遗留目录(dry-run 只列出不删),返回待删/已删清单。"""
    removed = []
    for name in RETIRED_SOURCE_DIRS:
        full = os.path.join(repo_dir, name)
        if not os.path.isdir(full):
            continue
        count = sum(len(files) for _, _, files in os.walk(full))
        removed.append(f"{name}/ ({count} 个文件)")
        if not dry_run:
            shutil.rmtree(full)
    return removed


def rebuild_overview_history(repo_dir):
    """
    扫描 repo/overview/ 重建 index.json 的 overview_history:
    与 fetch_data.write_index 同款规则 —— 每天取当日最后一次、不早于
    HISTORY_START_DATE、按日期倒序保留最近 OVERVIEW_RETENTION_DAYS 天。
    读-改-写(其余字段原样保留),时间戳同步刷新。
    """
    merged = _scan_history(repo_dir, "overview")
    keep = [d for d in sorted(merged, reverse=True)
            if d >= HISTORY_START_DATE][:OVERVIEW_RETENTION_DAYS]
    overview_history = {d: merged[d] for d in keep}

    index_path = os.path.join(repo_dir, "index.json")
    with open(index_path, "r", encoding="utf-8") as f:
        index = json.load(f)
    now = datetime.now(CST)
    index["updated_at"] = now.strftime("%Y-%m-%dT%H:%M:%S%z")
    index["updated_at_ms"] = int(now.timestamp() * 1000)
    index["overview_history"] = overview_history
    with open(index_path, "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=2)
    return overview_history


def main():
    parser = argparse.ArgumentParser(
        description="从 git 历史回填总览归档 + 重建 overview_history + 清理已下线源目录")
    parser.add_argument("--dry-run", action="store_true",
                        help="只克隆 + 打印将写/删的清单,不写入不推送")
    args = parser.parse_args()

    token = os.environ.get(ENV_GITCODE_TOKEN)
    if not token and not args.dry_run:
        print(f"[FATAL] 缺少环境变量 {ENV_GITCODE_TOKEN},无法推送。"
              f"请先 export {ENV_GITCODE_TOKEN}=<gitcode 个人访问令牌>", file=sys.stderr)
        return 2

    # 必须完整克隆(不带 --depth):本脚本依赖 git 历史里的旧版 index.json。
    # 现有 repo/ 若是 push_data 留下的浅克隆,历史只有一个提交,复用会得到错误结果,
    # 故一律删掉重克隆(dry-run 无 token 时匿名克隆,公开仓库只读)。
    work_root = os.path.abspath(os.path.join(os.path.dirname(__file__), os.path.pardir))
    repo_dir = os.path.join(work_root, "repo")
    if os.path.isdir(repo_dir):
        shutil.rmtree(repo_dir)
    url = _inject_token(DEFAULT_REPO_URL, token) if token else DEFAULT_REPO_URL
    print(f"[CLONE] 完整克隆 {DEFAULT_REPO_URL}(分支 {DEFAULT_BRANCH})...")
    run(["git", "clone", "--branch", DEFAULT_BRANCH, url, repo_dir])

    # 1) 历史总览回填落盘
    overviews = collect_overviews_from_history(repo_dir)
    written = write_overview_files(repo_dir, overviews, dry_run=args.dry_run)
    verb = "将写入" if args.dry_run else "已写入"
    print(f"[OVERVIEW] {verb} {len(written)} 个总览归档文件:")
    for date_str, time_str, digest in written:
        print(f"  - overview/{date_str}/{time_str}-data.json  {digest[:40]}")

    # 2) 清理已下线源遗留目录
    removed = remove_retired_dirs(repo_dir, dry_run=args.dry_run)
    verb = "将删除" if args.dry_run else "已删除"
    print(f"[PRUNE] {verb} {len(removed)} 个已下线源目录: {removed}")

    if args.dry_run:
        print("[DRY] 不写入不推送(以上仅为清单核对)")
        return 0

    # 3) 重建 overview_history 并合入 index.json(读-改-写,其余字段不动)
    overview_history = rebuild_overview_history(repo_dir)
    print(f"[INDEX] overview_history 已重建:{len(overview_history)} 天,"
          f"{min(overview_history)} ~ {max(overview_history)}")

    # bot 身份与提交风格对齐 push_data.py
    run(["git", "config", "user.name", "github-actions[bot]"], cwd=repo_dir)
    run(["git", "config", "user.email",
         "41898282+github-actions[bot]@users.noreply.github.com"], cwd=repo_dir)
    run(["git", "add", "-A"], cwd=repo_dir)
    diff = subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=repo_dir)
    if diff.returncode == 0:
        print("[PUSH] 无改动,跳过提交")
        return 0
    now = datetime.now(CST)
    msg = (f"chore(data): backfill overview history and remove retired linuxdo dir "
           f"({now.strftime('%Y-%m-%d_%H:%M')} CST)")
    run(["git", "commit", "-m", msg], cwd=repo_dir)
    run(["git", "push", "origin", DEFAULT_BRANCH], cwd=repo_dir)
    print(f"[PUSH] 已推送: {msg}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
