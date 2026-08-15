#!/usr/bin/env python3
"""
一次性运维脚本:从数据仓库 git 历史回填热词趋势(trends)按日归档。

背景:trends.json 每轮整体覆盖(更早榜单内联在 index.json 顶层 `latest_trends`
字段),git 工作区里没有按日期归档的趋势文件。但数据仓库的 git 历史中,
每次提交都带着当期榜单 —— 本脚本遍历这些历史版本,把每份趋势(以 generatedAt
去重,未变化批次在后续提交里天然合并)落成 trends/<YYYY-MM-DD>/<HH-MM>-data.json,
日期/时刻由 generatedAt 换算北京时间,再重建根级 trends_history.json 独立索引
(每天取当日最后一次、保留 90 天,与流水线 _write_trends_archive 同款规则)。

此后日常流水线(trend_keywords.write_trends)自动接力维护,本脚本无需再跑。

用法:
  export GITCODE_TOKEN=...
  python3 scripts/backfill_trends.py --dry-run   # 只克隆+打印将写的清单,不写入不推送
  python3 scripts/backfill_trends.py             # 真跑:写归档 + 重建索引 + 推送
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from common import BEIJING_TZ as CST, now_cst
from fetch_data import _scan_history
from push_data import (
    DEFAULT_BRANCH,
    DEFAULT_REPO_URL,
    ENV_GITCODE_TOKEN,
    _inject_token,
    run,
)
from trend_keywords import TRENDS_RETENTION_DAYS


def _valid_trends(data):
    """历史版本有效性:dict + 整型 generatedAt + 非空 keywords/days(落盘寻址的最低要求)。"""
    return (isinstance(data, dict)
            and isinstance(data.get("generatedAt"), int)
            and isinstance(data.get("keywords"), list) and data["keywords"]
            and isinstance(data.get("days"), list) and data["days"])


def collect_trends_from_history(repo_dir):
    """
    遍历改过 trends.json 或 index.json 的所有提交(时间正序),提取每期榜单。

    拆分前(2026-08-15 之前)榜单内联在 index.json 顶层 latest_trends 字段,
    拆分后是根级 trends.json —— 同一提交优先取独立文件,缺失回退内联字段。
    返回 {generatedAt(毫秒): trends dict}。同一份榜单会随「生成失败不覆盖」
    在后续多个提交里重复出现,以 generatedAt(生成时刻,随对象携带)去重。
    """
    log = run(
        ["git", "log", "--reverse", "--format=%H", "--", "trends.json", "index.json"],
        cwd=repo_dir,
    )
    commits = log.stdout.split()
    print(f"[SCAN] trends.json / index.json 历史版本共 {len(commits)} 个提交")
    trends = {}
    for commit in commits:
        data = None
        r = run(["git", "show", f"{commit}:trends.json"], cwd=repo_dir, check=False)
        if r.returncode == 0:
            try:
                data = json.loads(r.stdout)
            except Exception as e:  # 单个历史版本损坏不致命,跳过继续
                print(f"[WARN] {commit[:8]} 的 trends.json 解析失败,跳过:"
                      f"{type(e).__name__}: {e}", file=sys.stderr)
                continue
        else:
            # 拆分前:回退 index.json 顶层 latest_trends 内联字段
            r = run(["git", "show", f"{commit}:index.json"], cwd=repo_dir, check=False)
            if r.returncode != 0:
                continue
            try:
                inline = json.loads(r.stdout).get("latest_trends")
            except Exception:
                continue
            data = inline if isinstance(inline, dict) else None
        if data is None or not _valid_trends(data):
            continue  # 趋势功能上线前的早期版本 / 无效版本
        trends[data["generatedAt"]] = data
    print(f"[SCAN] 提取到 {len(trends)} 期去重后的历史榜单")
    return trends


def write_trends_files(repo_dir, trends, dry_run):
    """
    把历史榜单按 generatedAt(北京时间)落盘 trends/<date>/<HH-MM>-data.json。

    早于保留窗口(TRENDS_RETENTION_DAYS 天)的丢弃:trends_history 索引只保留
    90 天指针,窗口外的文件不会被任何消费方寻址,落了也是死重。
    返回待写/已写清单(倒序便于核对)。
    """
    # 窗口下界 = 今天往前推 TRENDS_RETENTION_DAYS-1 天(与索引截断口径一致)
    cutoff = (now_cst() - timedelta(days=TRENDS_RETENTION_DAYS - 1)).strftime("%Y-%m-%d")
    written = []
    for generated_at in sorted(trends, reverse=True):
        dt = datetime.fromtimestamp(generated_at / 1000, tz=CST)
        date_str = dt.strftime("%Y-%m-%d")
        if date_str < cutoff:
            continue
        time_str = dt.strftime("%H-%M")
        board = trends[generated_at]
        dir_path = os.path.join(repo_dir, "trends", date_str)
        file_path = os.path.join(dir_path, f"{time_str}-data.json")
        written.append((date_str, time_str, len(board["keywords"]),
                        board["keywords"][0].get("display", "")))
        if not dry_run:
            os.makedirs(dir_path, exist_ok=True)
            with open(file_path, "w", encoding="utf-8") as f:
                json.dump(board, f, ensure_ascii=False, indent=2)
    return written


def rebuild_trends_history(repo_dir):
    """
    扫描 repo/trends/ 重建趋势历史索引,写根级 trends_history.json 独立文件:
    与流水线 _write_trends_archive 同款规则 —— 每天取当日最后一次、按日期倒序
    保留最近 TRENDS_RETENTION_DAYS 天(含流水线已实跑落盘的当日归档)。
    """
    merged = _scan_history(repo_dir, "trends")
    keep = sorted(merged, reverse=True)[:TRENDS_RETENTION_DAYS]
    trends_history = {d: merged[d] for d in keep}
    with open(os.path.join(repo_dir, "trends_history.json"), "w", encoding="utf-8") as f:
        json.dump(trends_history, f, ensure_ascii=False, indent=2)
    return trends_history


def main():
    parser = argparse.ArgumentParser(
        description="从 git 历史回填趋势归档 + 重建 trends_history 索引")
    parser.add_argument("--dry-run", action="store_true",
                        help="只克隆 + 打印将写的清单,不写入不推送")
    args = parser.parse_args()

    token = os.environ.get(ENV_GITCODE_TOKEN)
    if not token and not args.dry_run:
        print(f"[FATAL] 缺少环境变量 {ENV_GITCODE_TOKEN},无法推送。"
              f"请先 export {ENV_GITCODE_TOKEN}=<gitcode 个人访问令牌>", file=sys.stderr)
        return 2

    # 必须完整克隆(不带 --depth):本脚本依赖 git 历史里的旧版 trends 数据。
    # 现有 repo/ 若是 push_data 留下的浅克隆,历史只有一个提交,复用会得到错误结果,
    # 故一律删掉重克隆(dry-run 无 token 时匿名克隆,公开仓库只读)。
    work_root = os.path.abspath(os.path.join(os.path.dirname(__file__), os.path.pardir))
    repo_dir = os.path.join(work_root, "repo")
    if os.path.isdir(repo_dir):
        shutil.rmtree(repo_dir)
    url = _inject_token(DEFAULT_REPO_URL, token) if token else DEFAULT_REPO_URL
    print(f"[CLONE] 完整克隆 {DEFAULT_REPO_URL}(分支 {DEFAULT_BRANCH})...")
    run(["git", "clone", "--branch", DEFAULT_BRANCH, url, repo_dir])

    # 1) 历史榜单回填落盘
    trends = collect_trends_from_history(repo_dir)
    written = write_trends_files(repo_dir, trends, dry_run=args.dry_run)
    verb = "将写入" if args.dry_run else "已写入"
    print(f"[TRENDS] {verb} {len(written)} 个趋势归档文件:")
    for date_str, time_str, count, top in written:
        print(f"  - trends/{date_str}/{time_str}-data.json  {count} 词  Top1 {top}")

    if args.dry_run:
        print("[DRY] 不写入不推送(以上仅为清单核对)")
        return 0

    # 2) 重建 trends_history 独立索引
    trends_history = rebuild_trends_history(repo_dir)
    print(f"[INDEX] trends_history 已重建:{len(trends_history)} 天,"
          f"{min(trends_history)} ~ {max(trends_history)}")

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
    msg = (f"chore(data): backfill trends history from git commits "
           f"({now.strftime('%Y-%m-%d_%H:%M')} CST)")
    run(["git", "commit", "-m", msg], cwd=repo_dir)
    run(["git", "push", "origin", DEFAULT_BRANCH], cwd=repo_dir)
    print(f"[PUSH] 已推送: {msg}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
