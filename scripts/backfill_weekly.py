#!/usr/bin/env python3
"""
一次性运维脚本:回填周刊(weekly)历史期数(默认最近 4 个已完结周)。

背景:weekly_digest.write_weekly 只在每周一首批 push 时生成「上周刊」,上线
首日往前没有历史。本脚本重克隆数据仓(工作区即含全部 overview 归档,无需挖
git 历史),对每个目标周正序调 write_weekly —— 幂等闸天然跳过已生成的期
(含流水线已实跑产出的最新期),AI 失败该期跳过继续,报告末尾汇总缺期。

此后日常流水线(weekly_digest.write_weekly)自动接力维护,本脚本无需再跑;
不进 CI(docs/agents/pipeline.md 的 backfill 段落同款声明)。

用法:
  export AI_NEWS_HUB_AI_* ...   # AI 三变量(同流水线)
  export GITCODE_TOKEN=...
  python3 scripts/backfill_weekly.py --dry-run   # 只克隆 + 打印各周 coverage 清单,不调 AI 不写盘
  python3 scripts/backfill_weekly.py             # 真跑:逐期生成 + 提交 + 推送
  python3 scripts/backfill_weekly.py --weeks 6   # 回填更多周(须不早于 WEEKLY_EPOCH)
"""

import argparse
import os
import shutil
import subprocess
import sys
from datetime import datetime, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from common import BEIJING_TZ as CST, now_cst
from push_data import (
    DEFAULT_BRANCH,
    DEFAULT_REPO_URL,
    ENV_GITCODE_TOKEN,
    _inject_token,
    run,
)
from weekly_digest import (
    WEEKLY_EPOCH,
    _issue_no,
    _last_week_range,
    _load_week_input,
    _load_weekly_history,
    _to_date,
    config_ready,
    write_weekly,
)

DEFAULT_WEEKS = 4


def _target_weeks(count):
    """最近 count 个已完结周的周一(正序,最旧在前)。"""
    last_monday, _ = _last_week_range(now_cst())
    return [last_monday - timedelta(weeks=i) for i in range(count - 1, -1, -1)]


def main():
    parser = argparse.ArgumentParser(
        description="回填周刊历史期数(最近 N 个已完结周)")
    parser.add_argument("--dry-run", action="store_true",
                        help="只克隆 + 打印各周 coverage 与将生成清单,不调 AI 不写盘")
    parser.add_argument("--weeks", type=int, default=DEFAULT_WEEKS,
                        help=f"回填周数(默认 {DEFAULT_WEEKS},从上一个完结周起倒数)")
    args = parser.parse_args()

    if args.weeks < 1:
        print("[FATAL] --weeks 至少为 1", file=sys.stderr)
        return 2

    # 重克隆:丢弃 push_data / 本地实验留下的工作区状态(含未提交的周刊文件,
    # 反正会重新生成);数据仓工作区已含全部 overview 归档,浅克隆即可
    work_root = os.path.abspath(os.path.join(os.path.dirname(__file__), os.path.pardir))
    repo_dir = os.path.join(work_root, "repo")
    if os.path.isdir(repo_dir):
        shutil.rmtree(repo_dir)
    token = os.environ.get(ENV_GITCODE_TOKEN)
    url = _inject_token(DEFAULT_REPO_URL, token) if token else DEFAULT_REPO_URL
    print(f"[CLONE] 浅克隆 {DEFAULT_REPO_URL}(分支 {DEFAULT_BRANCH})...")
    run(["git", "clone", "--depth", "1", "--branch", DEFAULT_BRANCH, url, repo_dir])

    weeks = _target_weeks(args.weeks)
    epoch = _to_date(WEEKLY_EPOCH)
    out_of_epoch = [w for w in weeks if w < epoch]
    if out_of_epoch:
        print(f"[FATAL] 目标周早于周刊纪元 {WEEKLY_EPOCH}:"
              f"{[w.isoformat() for w in out_of_epoch]}", file=sys.stderr)
        return 2

    if args.dry_run:
        for w in weeks:
            week_input = _load_week_input(repo_dir, w, w + timedelta(days=6))
            if week_input is None:
                print(f"  第 {_issue_no(w.isoformat())} 期 {w} ~ "
                      f"{(w + timedelta(days=6)).isoformat()}:coverage 不足,将跳过")
            else:
                print(f"  第 {_issue_no(w.isoformat())} 期 {week_input['monday']} ~ "
                      f"{week_input['sunday']}:coverage {week_input['coverage']} 天,"
                      f"候选池 {len(week_input['pool'])} 条")
        print("[DRY] 不调 AI 不写盘不推送(以上仅为清单核对)")
        return 0

    if not config_ready():
        print("[FATAL] AI 未配置(AI_NEWS_HUB_AI_* 环境变量缺失)", file=sys.stderr)
        return 2
    if not token:
        print(f"[FATAL] 缺少环境变量 {ENV_GITCODE_TOKEN},无法推送。"
              f"请先 export {ENV_GITCODE_TOKEN}=<gitcode 个人访问令牌>", file=sys.stderr)
        return 2

    # 正序逐期生成(最后写的是最新期 → 根级 weekly.json 指向最新);
    # 幂等闸跳过已生成期,AI 失败该期跳过继续
    existing = _load_weekly_history(repo_dir)
    ok, skipped, failed = [], [], []
    for w in weeks:
        week_str = w.isoformat()
        done = write_weekly(repo_dir, week=week_str)
        if done and week_str in existing:
            skipped.append(week_str)   # 幂等跳过(已生成)
        elif done:
            ok.append(week_str)
        else:
            failed.append(week_str)
    print(f"[BACKFILL] 生成 {len(ok)} 期({ok}),幂等跳过 {len(skipped)} 期({skipped}),"
          f"失败 {len(failed)} 期({failed})")
    if failed:
        print("[BACKFILL] 缺期见上;coverage 不足的周归档已定型不会自愈,人工决策是否放宽重跑",
              file=sys.stderr)

    # bot 身份与提交风格对齐 push_data.py / backfill_trends.py
    run(["git", "config", "user.name", "github-actions[bot]"], cwd=repo_dir)
    run(["git", "config", "user.email",
         "41898282+github-actions[bot]@users.noreply.github.com"], cwd=repo_dir)
    run(["git", "add", "-A"], cwd=repo_dir)
    diff = subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=repo_dir)
    if diff.returncode == 0:
        print("[PUSH] 无改动,跳过提交")
        return 0
    now = datetime.now(CST)
    msg = (f"chore(data): backfill weekly digest for {len(ok) + len(skipped)} weeks "
           f"({now.strftime('%Y-%m-%d_%H:%M')} CST)")
    run(["git", "commit", "-m", msg], cwd=repo_dir)
    run(["git", "push", "origin", DEFAULT_BRANCH], cwd=repo_dir)
    print(f"[PUSH] 已推送: {msg}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
