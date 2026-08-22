#!/usr/bin/env python3
"""
把抓取产物推送到 gitcode 数据仓库(需求 b)。

把原本写在 .github/workflows/fetch-data.yml 里的 inline shell
(clone / cp / commit / push)固化成独立脚本,使「抓数据」与「推仓库」彻底解耦:

  - fetch_data.py 只负责抓 + 落盘到 out/
  - push_data.py  只负责把 out/ 提交到 gitcode 的 news-hub-data 分支
  - pipeline.sh   串起两者,并在执行前统一检测 4 个环境变量

执行前必须设置环境变量 GITCODE_TOKEN(需求 b-i),缺失直接退出码 2。

用法:
  # 单独推(产物已在 out/)
  python3 scripts/push_data.py --data-dir out

  # 自定义仓库 / 分支
  python3 scripts/push_data.py --data-dir out \\
      --repo-url https://gitcode.com/peng1818/AI-News-Hub-Data.git \\
      --branch news-hub-data
"""

import argparse
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timedelta

# 北京时间(UTC+8):提交信息里的时间戳与文件名一致(统一从 common 引入)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import BEIJING_TZ as CST

import trend_keywords

ENV_GITCODE_TOKEN = "GITCODE_TOKEN"

DEFAULT_REPO_URL = "https://gitcode.com/peng1818/AI-News-Hub-Data.git"
DEFAULT_BRANCH = "news-hub-data"

# push 重试次数与退避(对齐 fetch/summary 链路的 3 次重试)。
# push 是全链路最后一步,瞬时网络抖动 / 非快进会废掉整轮(下一轮要等次日 15:30),
# 故与抓取/AI 同等对待:失败重试,push 类失败时重 clone 规避脏本地状态。
PUSH_MAX_ATTEMPTS = 3

# 语音速报音频目录(audio/<YYYY-MM-DD>/)保留天数:每日 ≈2.4MB,14 天 ≈34MB 封顶。
# _overlay 只增不删,过期目录须在 overlay 后显式清理(见 _prune_old_audio)。
AUDIO_RETAIN_DAYS = 14


def run(cmd, cwd=None, check=True):
    """跑一条命令,失败抛 CalledProcessError(check=True 时)。

    打印命令前先脱敏:git clone/push 的 URL 可能含注入的 token
    (https://x-access-token:<token>@...),原样打印会泄露 secret。
    用 _redact 把 token 段替换成 *** 再输出。
    """
    safe = [_redact(arg) for arg in cmd]
    print(f"$ {' '.join(safe)}")
    return subprocess.run(cmd, cwd=cwd, check=check, capture_output=True, text=True)


def _redact(arg):
    """把字符串里 https://<user>:<token>@host 的 <token> 段替换成 ***。
    非 URL 原样返回。"""
    if "://" in arg and "@" in arg:
        # scheme://userinfo@rest → scheme://userinfo:***@rest
        at = arg.index("@")
        scheme_end = arg.index("://") + 3
        userinfo = arg[scheme_end:at]
        if ":" in userinfo:
            user = userinfo.split(":", 1)[0]
            return arg[:scheme_end] + user + ":***@" + arg[at + 1:]
    return arg


def push(data_dir, repo_url, branch):
    """克隆目标分支 → 覆盖产物 → 提交 → 推送。无改动时跳过。push 失败重试。"""
    token = os.environ.get(ENV_GITCODE_TOKEN)
    if not token:
        print(f"[FATAL] 缺少环境变量 {ENV_GITCODE_TOKEN},无法推送。"
              f"请先 export {ENV_GITCODE_TOKEN}=<gitcode 个人访问令牌>", file=sys.stderr)
        return 2

    if not os.path.isdir(data_dir):
        print(f"[FATAL] 产物目录不存在: {data_dir}", file=sys.stderr)
        return 2

    # 把 token 注入到 URL(对齐原 workflow:https://x-access-token:<token>@gitcode.com/...)
    authed_url = _inject_token(repo_url, token)

    work_root = os.path.abspath(os.path.join(data_dir, os.pardir))
    repo_dir = os.path.join(work_root, "repo")

    # 首次 clone + overlay + commit;push 失败时重试(含重 clone 规避脏本地状态)。
    # 整体重试最多 PUSH_MAX_ATTEMPTS 次,对齐 fetch/summary 链路的容错强度。
    last_err = None
    for attempt in range(1, PUSH_MAX_ATTEMPTS + 1):
        try:
            return _clone_overlay_commit_push(
                data_dir, repo_dir, authed_url, branch, repo_url,
                is_retry=(attempt > 1),
            )
        except subprocess.CalledProcessError as e:
            last_err = e
            if attempt < PUSH_MAX_ATTEMPTS:
                wait = 2 ** attempt  # 2s, 4s
                print(f"[PUSH] 第 {attempt}/{PUSH_MAX_ATTEMPTS} 次失败,{wait}s 后重试"
                      f"(将重新 clone):\n{e.stderr}", file=sys.stderr)
                time.sleep(wait)
    print(f"[FATAL] 推送 {PUSH_MAX_ATTEMPTS} 次全败:{last_err.stderr if last_err else last_err}",
          file=sys.stderr)
    return 4


def _clone_overlay_commit_push(data_dir, repo_dir, authed_url, branch, repo_url, *,
                               is_retry=False):
    """
    单次完整推送:clone → overlay → commit → push。任一步失败抛 CalledProcessError
    (由 push 的重试循环捕获)。is_retry=True 时打印重试标记。

    失败后重试会重新 clone:浅克隆很快,且能规避上次 push 失败残留的脏本地状态
    (如非快进冲突导致的本地分叉),比在同一仓库上反复 force push 更稳妥。
    """
    if is_retry:
        print(f"[PUSH] 重新 clone 后重试推送")
    # 清掉上次可能残留的 clone 目录(本地连跑 / 重试时复用 work_root)
    if os.path.isdir(repo_dir):
        shutil.rmtree(repo_dir)

    # 浅克隆目标分支(只要最新一次提交,省带宽)
    run(["git", "clone", "--depth", "1", "--branch", branch, authed_url, repo_dir])

    # 覆盖产物进仓库根:保留仓库既有历史快照,只覆盖 / 新增本次文件
    _overlay(data_dir, repo_dir)

    # 热词趋势:overlay 后仓库 checkout 含全部历史快照,是唯一能免费拿到全部
    # 历史的环节;纯统计不调 AI,失败只告警不阻断推送(文件暂缺,App 走空态)
    trend_keywords.write_trends(repo_dir)

    # 语音速报音频滚动清理:overlay 只增不删,过期日期目录在这里显式删除,
    # 随后的 git add -A 捕获删除并随本次提交推走(工作区稳定 ≈34MB)
    _prune_old_audio(repo_dir)

    # bot 身份(对齐原 workflow:github-actions[bot])
    run(["git", "config", "user.name", "github-actions[bot]"], cwd=repo_dir)
    run(["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"],
        cwd=repo_dir)
    run(["git", "add", "-A"], cwd=repo_dir)

    # 无改动则跳过(对齐原 workflow 的 git diff --cached --quiet 判空)
    diff = subprocess.run(
        ["git", "diff", "--cached", "--quiet"], cwd=repo_dir
    )
    if diff.returncode == 0:
        print("[PUSH] 无改动,跳过提交")
        return 0

    now = datetime.now(CST)
    msg = f"chore(data): hub snapshot {now.strftime('%Y-%m-%d_%H:%M')} CST"
    run(["git", "commit", "-m", msg], cwd=repo_dir)
    run(["git", "push", "origin", branch], cwd=repo_dir)
    print(f"[PUSH] 已推送: {msg}")
    return 0


def _inject_token(repo_url, token):
    """把 https://gitcode.com/... 注入成 https://x-access-token:<token>@gitcode.com/..."""
    if "://" not in repo_url:
        raise ValueError(f"repo-url 必须是 https URL: {repo_url}")
    scheme, rest = repo_url.split("://", 1)
    return f"{scheme}://x-access-token:{token}@{rest}"


def _prune_old_audio(repo_dir):
    """删除 audio/ 下日期目录名早于 AUDIO_RETAIN_DAYS 天的目录(告警不阻断)。

    overlay 只增不删,若不清理,音频会以 ≈2.4MB/天 无限累积;目录名即北京
    时间日期(YYYY-MM-DD,与 fetch 的按日归档同构),按名比较即可,无需读
    文件元数据。非日期命名的目录/文件一律不动;任何异常只告警不抛
    (清理失败顶多多占些仓库空间,不值得废掉整轮推送)。
    """
    try:
        audio_root = os.path.join(repo_dir, "audio")
        if not os.path.isdir(audio_root):
            return
        cutoff = datetime.now(CST).date() - timedelta(days=AUDIO_RETAIN_DAYS)
        removed = 0
        for name in os.listdir(audio_root):
            path = os.path.join(audio_root, name)
            if not os.path.isdir(path):
                continue
            try:
                dir_date = datetime.strptime(name, "%Y-%m-%d").date()
            except ValueError:
                continue
            if dir_date < cutoff:
                shutil.rmtree(path)
                removed += 1
        if removed:
            print(f"[PUSH] 清理 {removed} 个过期音频目录(>{AUDIO_RETAIN_DAYS} 天)")
    except Exception as e:
        print(f"[PUSH][WARN] 音频目录清理失败(不阻断推送):{type(e).__name__}: {e}",
              file=sys.stderr)


def _overlay(src_dir, dst_dir):
    """
    把 src_dir 下所有内容**合并**进 dst_dir(对应原 workflow 的 `cp -r out/. repo/`)。

    关键语义:**只新增/覆盖同名文件,绝不删除 dst 里 src 没有的内容**。
    历史快照目录(如 dst/hackernews/2026-07-15/)必须原样保留 —— CI 每次
    只产出当天数据(src 里只有 2026-07-16),若 rmtree 整个 dst/hackernews/
    再覆盖,会把历史日期目录全部删掉。

    用 dirs_exist_ok=True 让 copytree 递归合并到既有目录(Python 3.8+),
    自动实现「同名覆盖、异名保留」的合并语义。
    """
    for entry in os.listdir(src_dir):
        s = os.path.join(src_dir, entry)
        d = os.path.join(dst_dir, entry)
        if os.path.isdir(s):
            # 合并:目标目录已存在也只覆盖同名文件,保留 dst 里独有的历史目录/文件
            shutil.copytree(s, d, dirs_exist_ok=True)
        else:
            shutil.copy2(s, d)


def main():
    parser = argparse.ArgumentParser(description="把抓取产物推送到 gitcode 数据仓库")
    parser.add_argument("--data-dir", default="out", help="产物根目录(默认 ./out)")
    parser.add_argument("--repo-url", default=DEFAULT_REPO_URL, help="目标仓库 URL")
    parser.add_argument("--branch", default=DEFAULT_BRANCH, help="目标分支(默认 news-hub-data)")
    args = parser.parse_args()
    return push(args.data_dir, args.repo_url, args.branch)


if __name__ == "__main__":
    sys.exit(main())
