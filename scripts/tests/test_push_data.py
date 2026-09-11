"""push_data 回归(单测 + 本地 bare 仓 e2e,零网络零真实远端)。

钉住:日志脱敏(token 绝不进输出)、URL 注入形态、overlay 的「只增改不删」
合并语义(历史快照目录必须原样保留)、音频 14 天滚动清理、守卫退出码 2、
重试矩阵(2s/4s 退避、全败 4),以及完整推送链路(clone→overlay→趋势挂钩→
清理→commit→push / 无改动跳过)。e2e 用本地 bare 仓当远端,_inject_token
桩成恒等(明文 token 行为由单测钉死,测试里根本不产生含 token 的 URL)。
"""

import json
import subprocess
import time

import pytest

import push_data as pd
import trend_keywords


# ===== 日志脱敏(安全关键:token 绝不出现在输出里) =====

def test_redact_URL内token段替换为星号():
    url = "https://x-access-token:SECRET@github.com/a/b.git"
    assert pd._redact(url) == "https://x-access-token:***@github.com/a/b.git"
    assert "SECRET" not in pd._redact(url)


def test_redact_裸URL与普通参数原样():
    assert pd._redact("https://gitcode.com/a/b.git") == "https://gitcode.com/a/b.git"
    assert pd._redact("--depth") == "--depth"
    assert pd._redact("https://user@host/x.git") == "https://user@host/x.git"  # userinfo 无冒号


def test_run_执行前打印已脱敏(capsys):
    pd.run(["echo", "https://x-access-token:TOPSECRET@h/x.git"])
    out = capsys.readouterr().out
    assert "TOPSECRET" not in out and "***" in out


# ===== token 注入 =====

def test_inject_token_注入x_access_token形():
    got = pd._inject_token("https://gitcode.com/a.git", "TOK")
    assert got == "https://x-access-token:TOK@gitcode.com/a.git"


def test_inject_token_非https形态拒绝():
    with pytest.raises(ValueError):
        pd._inject_token("git@host:path.git", "TOK")


# ===== _overlay:只增改不删(历史快照的生命线) =====

def test_overlay_同名覆盖异名保留(tmp_path):
    src, dst = tmp_path / "src", tmp_path / "dst"
    (src / "hackernews" / "2026-08-29").mkdir(parents=True)
    (src / "index.json").write_text('{"new": 1}')
    (src / "hackernews" / "2026-08-29" / "11-01-data.json").write_text("today")
    (dst / "hackernews" / "2026-07-15").mkdir(parents=True)
    (dst / "hackernews" / "2026-07-15" / "22-00-data.json").write_text("history")
    (dst / "hackernews" / "2026-08-29").mkdir(parents=True)
    (dst / "hackernews" / "2026-08-29" / "08-00-data.json").write_text("stale-batch")
    (dst / "index.json").write_text('{"old": 1}')
    (dst / "trends.json").write_text("dst-only")

    pd._overlay(str(src), str(dst))
    assert json.loads((dst / "index.json").read_text()) == {"new": 1}     # 同名覆盖
    assert (dst / "hackernews" / "2026-08-29" / "11-01-data.json").read_text() == "today"
    assert (dst / "hackernews" / "2026-08-29" / "08-00-data.json").exists()  # 同日异名保留
    assert (dst / "hackernews" / "2026-07-15" / "22-00-data.json").exists()  # 历史日期不删
    assert (dst / "trends.json").read_text() == "dst-only"               # dst 独有文件保留


# ===== _prune_old_audio:14 天滚动清理 =====

def test_prune_只删过期日期目录(frozen_push_datetime, tmp_path, capsys):
    audio = tmp_path / "repo" / "audio"
    for name in ("2026-08-10", "2026-08-20", "2026-08-29", "not-a-date"):
        (audio / name).mkdir(parents=True)
    (audio / "stray.txt").write_text("x")

    pd._prune_old_audio(str(tmp_path / "repo"))
    assert not (audio / "2026-08-10").exists()  # 19 天前(早于 08-15 cutoff)
    assert (audio / "2026-08-20").exists()      # 9 天前:保留
    assert (audio / "2026-08-29").exists()      # 当日:保留
    assert (audio / "not-a-date").exists()      # 非日期命名不动
    assert (audio / "stray.txt").exists()       # 非目录不动
    assert "清理 1 个过期音频目录" in capsys.readouterr().out


def test_prune_无audio目录静默过(tmp_path):
    pd._prune_old_audio(str(tmp_path))


# ===== push 守卫与重试矩阵(内层桩掉,不碰 git) =====

def test_push_缺token与缺产物目录都返回2(monkeypatch, tmp_path):
    monkeypatch.delenv(pd.ENV_GITCODE_TOKEN, raising=False)
    assert pd.push(str(tmp_path), "https://x.git", "b") == 2

    monkeypatch.setenv(pd.ENV_GITCODE_TOKEN, "tok")
    assert pd.push(str(tmp_path / "nope"), "https://x.git", "b") == 2


def test_push_重试三次2s4s退避后成功(monkeypatch, tmp_path):
    monkeypatch.setenv(pd.ENV_GITCODE_TOKEN, "tok")
    out = tmp_path / "out"
    out.mkdir()
    (out / "index.json").write_text("{}")
    sleeps, calls = [], []

    def flaky(*a, **k):
        calls.append(1)
        if len(calls) < 3:
            raise subprocess.CalledProcessError(1, ["git"], stderr="boom")
        return 0

    monkeypatch.setattr(pd, "_clone_overlay_commit_push", flaky)
    monkeypatch.setattr(time, "sleep", lambda s: sleeps.append(s))
    assert pd.push(str(out), "https://x.git", "b") == 0
    assert sleeps == [2, 4] and len(calls) == 3


def test_push_三次全败返回4(monkeypatch, tmp_path):
    monkeypatch.setenv(pd.ENV_GITCODE_TOKEN, "tok")
    out = tmp_path / "out"
    out.mkdir()
    sleeps, calls = [], []

    def boom(*a, **k):
        calls.append(1)
        raise subprocess.CalledProcessError(128, ["git"], stderr="fatal")

    monkeypatch.setattr(pd, "_clone_overlay_commit_push", boom)
    monkeypatch.setattr(time, "sleep", lambda s: sleeps.append(s))
    assert pd.push(str(out), "https://x.git", "b") == 4
    assert sleeps == [2, 4] and len(calls) == pd.PUSH_MAX_ATTEMPTS


# ===== e2e:本地 bare 仓上的完整推送链路 =====

def _git(*args, cwd=None):
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True)


def _e2e_setup(monkeypatch, tmp_path, trend_calls):
    """建本地 bare 远端(含历史文件与新旧音频)+ out 产物;注入 e2e 桩。"""
    monkeypatch.setenv(pd.ENV_GITCODE_TOKEN, "e2e-token")
    # 本地 plain-path 远端没有 https 可注入,恒等直通;token 注入/脱敏已由单测钉死
    monkeypatch.setattr(pd, "_inject_token", lambda repo_url, token: repo_url)
    monkeypatch.setattr(trend_keywords, "write_trends",
                        lambda repo_dir: trend_calls.append(repo_dir) or True)

    remote = tmp_path / "remote.git"
    _git("init", "--bare", str(remote))
    seed = tmp_path / "seed"
    _git("clone", str(remote), str(seed))
    _git("checkout", "-b", "work", cwd=str(seed))
    (seed / "README.md").write_text("history", encoding="utf-8")
    for d, f in (("2026-08-10", "old.mp3"), ("2026-08-20", "fresh.mp3")):
        (seed / "audio" / d).mkdir(parents=True, exist_ok=True)
        (seed / "audio" / d / f).write_text("x")
    _git("add", "-A", cwd=str(seed))
    _git("-c", "user.name=seed", "-c", "user.email=s@x", "commit", "-m", "seed", cwd=str(seed))
    _git("push", "origin", "HEAD:news-hub-data", cwd=str(seed))

    out = tmp_path / "out"
    (out / "hackernews" / "2026-08-29").mkdir(parents=True)
    (out / "index.json").write_text('{"latest": {}}', encoding="utf-8")
    (out / "hackernews" / "2026-08-29" / "11-01-data.json").write_text('{"items": []}')
    return remote, out


def _remote_tree(remote):
    got = subprocess.run(
        ["git", "-C", str(remote), "ls-tree", "-r", "--name-only", "news-hub-data"],
        capture_output=True, text=True, check=True).stdout.splitlines()
    return got


def _remote_count(remote):
    return int(subprocess.run(
        ["git", "-C", str(remote), "rev-list", "--count", "news-hub-data"],
        capture_output=True, text=True, check=True).stdout.strip())


def test_push_e2e_完整链路推送并清理过期音频(frozen_push_datetime, monkeypatch, tmp_path):
    trend_calls = []
    remote, out = _e2e_setup(monkeypatch, tmp_path, trend_calls)

    assert pd.push(str(out), str(remote), "news-hub-data") == 0

    tree = _remote_tree(remote)
    assert "README.md" in tree                                       # 远端既有内容保留
    assert "index.json" in tree and "hackernews/2026-08-29/11-01-data.json" in tree
    assert "audio/2026-08-20/fresh.mp3" in tree                      # 9 天:保留
    assert "audio/2026-08-10/old.mp3" not in tree                    # 19 天:随本批清走
    assert len(trend_calls) == 1 and trend_calls[0].endswith("repo")  # 趋势挂钩在 overlay 后的 checkout 上

    msg = subprocess.run(
        ["git", "-C", str(remote), "log", "-1", "--format=%s", "news-hub-data"],
        capture_output=True, text=True, check=True).stdout.strip()
    assert msg == "chore(data): hub snapshot 2026-08-29_11:01 CST"   # 冻结时钟的提交时间戳

    def cfg(key):
        return subprocess.run(["git", "-C", str(tmp_path / "repo"), "config", key],
                              capture_output=True, text=True, check=True).stdout.strip()

    assert cfg("user.name") == "github-actions[bot]"                 # bot 身份固化
    assert cfg("http.version") == "HTTP/1.1"                         # gitcode 514 断连对策


def test_push_e2e_无改动跳过且不新增提交(frozen_push_datetime, monkeypatch, tmp_path, capsys):
    trend_calls = []
    remote, out = _e2e_setup(monkeypatch, tmp_path, trend_calls)
    assert pd.push(str(out), str(remote), "news-hub-data") == 0
    before = _remote_count(remote)

    assert pd.push(str(out), str(remote), "news-hub-data") == 0
    assert _remote_count(remote) == before     # 同产物重推:无新提交
    assert "无改动,跳过提交" in capsys.readouterr().out
