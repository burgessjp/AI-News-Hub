"""tts_broadcast 回归(全桩引擎/ffmpeg/模型自举,零大文件零网络)。

钉住:播报文本契约(仅 digest,与 App 兜底朗读同文本)、GGUF magic+尺寸双闸、
自举与下载的失败降级、合成的重试/预算/产物形态(wav→mp3)、latest_audio
manifest 字段与「继承日不能没音频」,以及 __main__ 兜底的「任何路径不抛非零」。
"""

import json
import os
import subprocess
import sys
import time
import wave
from pathlib import Path

import tts_broadcast as tts


# ===== _build_text =====

def test_build_text_仅取digest并trim():
    assert tts._build_text({"digest": "  综述文本\n"}) == "综述文本"
    assert tts._build_text({"digest": ""}) == ""
    assert tts._build_text({}) == ""
    assert tts._build_text({"digest": None}) == ""


# ===== _gguf_ok:magic + 尺寸双闸 =====

def test_gguf_ok_小文件尺寸下限先拦(tmp_path):
    p = tmp_path / tts.TALKER_GGUF
    p.write_bytes(b"GGUFxxxx")
    assert tts._gguf_ok(p) is False  # 只查「非空」会被残卷骗过,尺寸闸先拦


def test_gguf_ok_magic头校验(monkeypatch, tmp_path):
    monkeypatch.setattr(tts, "MODEL_MIN_BYTES", {tts.TALKER_GGUF: 1, tts.CODEC_GGUF: 1})
    ok = tmp_path / tts.TALKER_GGUF
    ok.write_bytes(b"GGUF-data")
    bad = tmp_path / tts.CODEC_GGUF
    bad.write_bytes(b"JUNK-data")
    assert tts._gguf_ok(ok) is True
    assert tts._gguf_ok(bad) is False
    assert tts._gguf_ok(tmp_path / "absent.gguf") is False


# ===== _ensure_binary / _ensure_models(环境变量指到 tmp,不碰 third_party) =====

def test_ensure_binary_已有可执行直接用不克隆(monkeypatch, tmp_path):
    d = tmp_path / "engine"
    b = d / "build" / "qwen-tts"
    b.parent.mkdir(parents=True)
    b.write_text("#!/bin/sh\n")
    b.chmod(0o755)
    monkeypatch.setenv("QWENTTS_DIR", str(d))

    def _boom(*a, **k):
        raise AssertionError("二进制在位不该走克隆/构建")

    monkeypatch.setattr(tts, "_clone_pinned", _boom)
    assert tts._ensure_binary() == b


def test_ensure_binary_克隆失败与无cmake均告警跳过(monkeypatch, tmp_path, capsys):
    empty = tmp_path / "empty"
    empty.mkdir()
    monkeypatch.setenv("QWENTTS_DIR", str(empty))

    def boom(*a, **k):
        raise RuntimeError("网络不可达")

    monkeypatch.setattr(tts, "_clone_pinned", boom)
    assert tts._ensure_binary() is None  # 克隆失败 → 告警跳过

    with_cmake = tmp_path / "src"
    with_cmake.mkdir()
    (with_cmake / "CMakeLists.txt").write_text("")
    monkeypatch.setenv("QWENTTS_DIR", str(with_cmake))
    monkeypatch.setattr("shutil.which", lambda n: None)  # 无编译器
    assert tts._ensure_binary() is None
    assert "无 cmake" in capsys.readouterr().err


def test_ensure_models_双件在位直取_残卷删了重下(monkeypatch, tmp_path):
    monkeypatch.setattr(tts, "MODEL_MIN_BYTES", {tts.TALKER_GGUF: 1, tts.CODEC_GGUF: 1})
    d = tmp_path / "models"
    d.mkdir()
    (d / tts.TALKER_GGUF).write_bytes(b"GGUF-1")
    (d / tts.CODEC_GGUF).write_bytes(b"GGUF-2")
    monkeypatch.setenv("QWENTTS_MODELS_DIR", str(d))
    assert tts._ensure_models() == (d / tts.TALKER_GGUF, d / tts.CODEC_GGUF)

    (d / tts.TALKER_GGUF).write_bytes(b"JUNK")   # talker 残卷:删了重下成功
    (d / tts.CODEC_GGUF).write_bytes(b"JUNK2")   # codec 残卷:重下失败
    downloaded = []

    def fake_dl(name, dest_dir):
        downloaded.append(name)
        if name == tts.TALKER_GGUF:
            (dest_dir / name).write_bytes(b"GGUF-ok")  # 重下成功
            return True
        return False  # codec 失败

    monkeypatch.setattr(tts, "_download_gguf", fake_dl)
    assert tts._ensure_models() is None
    assert downloaded == [tts.TALKER_GGUF, tts.CODEC_GGUF]
    assert (d / tts.TALKER_GGUF).read_bytes()[:4] == b"GGUF"  # 残卷确被替换


# ===== _download_gguf:多源重试与残件清理 =====

def test_download_三连败换镜像后放弃并清残件(monkeypatch, tmp_path):
    target = tmp_path / tts.TALKER_GGUF
    target.write_bytes(b"half")
    calls = []

    def fail(cmd, **k):
        calls.append(cmd)
        return subprocess.CompletedProcess(cmd, returncode=1)

    monkeypatch.setattr(tts, "_run", fail)
    assert tts._download_gguf(tts.TALKER_GGUF, tmp_path) is False
    assert len(calls) == 6  # 默认 2 源 × 3 次
    assert not target.exists()  # 残件最终清掉


def test_download_环境变量指定单源(monkeypatch, tmp_path):
    monkeypatch.setenv("QWENTTS_HF_HOST", "https://mirror.example")
    calls = []

    def fail(cmd, **k):
        calls.append(cmd)
        return subprocess.CompletedProcess(cmd, returncode=1)

    monkeypatch.setattr(tts, "_run", fail)
    assert tts._download_gguf(tts.TALKER_GGUF, tmp_path) is False
    assert len(calls) == 3
    assert all("mirror.example" in cmd[-1] for cmd in calls)


def test_download_成功且校验通过(monkeypatch, tmp_path):
    monkeypatch.setattr(tts, "MODEL_MIN_BYTES", {tts.TALKER_GGUF: 4})

    def ok(cmd, **k):
        Path(cmd[cmd.index("-o") + 1]).write_bytes(b"GGUF!")
        return subprocess.CompletedProcess(cmd, returncode=0)

    monkeypatch.setattr(tts, "_run", ok)
    assert tts._download_gguf(tts.TALKER_GGUF, tmp_path) is True


# ===== _synthesize_merged =====

def _write_wav(path, frames=24000, rate=24000):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"\x00\x01" * frames)


def _ok_run(cmd, **k):
    _write_wav(Path(cmd[cmd.index("-o") + 1]))
    return subprocess.CompletedProcess(cmd, returncode=0)


def test_synth_成功按wav帧数计时_无ffmpeg保wav(monkeypatch, tmp_path):
    monkeypatch.setattr("shutil.which", lambda n: None)
    monkeypatch.setattr(tts, "_run", _ok_run)
    audio_dir = tmp_path / "audio" / "2026-08-29"
    audio_dir.mkdir(parents=True)

    got = tts._synthesize_merged(
        Path("/fake/qwen-tts"), (Path("/m/t.gguf"), Path("/m/c.gguf")),
        "综述文本", "serena", audio_dir, "audio/2026-08-29", None)
    assert got[0] == "audio/2026-08-29/broadcast.wav"
    assert got[1] == 1000  # 24000 帧 / 24000 Hz
    assert got[2] == (audio_dir / "broadcast.wav").stat().st_size


def test_synth_预算耗尽不起进程(monkeypatch, tmp_path):
    def boom(*a, **k):
        raise AssertionError("预算耗尽不该再起引擎进程")

    monkeypatch.setattr(tts, "_run", boom)
    got = tts._synthesize_merged(
        Path("/b"), (Path("/t"), Path("/c")), "x", "v", tmp_path, "a",
        time.monotonic() - 1)
    assert got is None


def test_synth_首败重试一次成(monkeypatch, tmp_path):
    monkeypatch.setattr("shutil.which", lambda n: None)
    calls = {"n": 0}

    def flaky(cmd, **k):
        calls["n"] += 1
        if calls["n"] == 1:
            return subprocess.CompletedProcess(cmd, returncode=1)
        _write_wav(Path(cmd[cmd.index("-o") + 1]), frames=12000)
        return subprocess.CompletedProcess(cmd, returncode=0)

    monkeypatch.setattr(tts, "_run", flaky)
    got = tts._synthesize_merged(
        Path("/b"), (Path("/t"), Path("/c")), "x", "v", tmp_path, "a", None)
    assert got is not None and got[1] == 500  # 0.5s
    assert calls["n"] == 2


def test_synth_两败或产物缺失均放弃且不留半成品(monkeypatch, tmp_path):
    monkeypatch.setattr("shutil.which", lambda n: None)

    def fail(cmd, **k):
        return subprocess.CompletedProcess(cmd, returncode=1)

    monkeypatch.setattr(tts, "_run", fail)
    assert tts._synthesize_merged(
        Path("/b"), (Path("/t"), Path("/c")), "x", "v", tmp_path, "a", None) is None

    calls = []

    def nofile(cmd, **k):  # rc=0 但没落 wav:视同失败参与重试
        calls.append(1)
        return subprocess.CompletedProcess(cmd, returncode=0)

    monkeypatch.setattr(tts, "_run", nofile)
    assert tts._synthesize_merged(
        Path("/b"), (Path("/t"), Path("/c")), "x", "v", tmp_path, "a", None) is None
    assert len(calls) == 2
    assert not (tmp_path / "broadcast.wav").exists()  # 半成品不残留


def test_synth_ffmpeg转mp3_编码失败保wav(monkeypatch, tmp_path):
    monkeypatch.setattr("shutil.which", lambda n: "/usr/bin/ffmpeg" if n == "ffmpeg" else None)
    monkeypatch.setattr(tts, "_run", _ok_run)
    audio_dir = tmp_path

    def ffmpeg_ok(cmd, **k):
        Path(cmd[-1]).write_bytes(b"ID3-mp3-data")
        return subprocess.CompletedProcess(cmd, returncode=0)

    monkeypatch.setattr(tts.subprocess, "run", ffmpeg_ok)
    got = tts._synthesize_merged(
        Path("/b"), (Path("/t"), Path("/c")), "x", "v", audio_dir, "audio/d", None)
    assert got[0] == "audio/d/broadcast.mp3"
    assert not (audio_dir / "broadcast.wav").exists()  # 编码成功删 wav

    def ffmpeg_fail(cmd, **k):
        return subprocess.CompletedProcess(cmd, returncode=1, stderr="boom")

    monkeypatch.setattr(tts.subprocess, "run", ffmpeg_fail)
    got = tts._synthesize_merged(
        Path("/b"), (Path("/t"), Path("/c")), "x", "v", audio_dir, "audio/d", None)
    assert got[0] == "audio/d/broadcast.wav"  # 编码失败保留 wav(App 可直接播)


# ===== main():argv 注入 =====

def _seed_index(out_dir, overview):
    idx = {"latest_overview": overview} if overview is not None else {"latest": {}}
    (out_dir / "index.json").write_text(json.dumps(idx, ensure_ascii=False), encoding="utf-8")


def test_main_DISABLE开关整体跳过(monkeypatch, tmp_path):
    monkeypatch.setenv("AI_NEWS_HUB_TTS_DISABLE", "1")
    monkeypatch.setattr(sys, "argv", ["tts_broadcast.py", "--out-dir", str(tmp_path)])
    assert tts.main() == 0
    assert not (tmp_path / "index.json").exists()


def test_main_索引缺失无总览空综述均跳过不写(monkeypatch, tmp_path):
    monkeypatch.setattr(sys, "argv", ["tts_broadcast.py", "--out-dir", str(tmp_path)])
    assert tts.main() == 0  # index.json 不存在

    _seed_index(tmp_path, overview=None)  # 无 latest_overview(字段不存在才算缺失)
    assert tts.main() == 0
    assert "latest_audio" not in json.loads((tmp_path / "index.json").read_text(encoding="utf-8"))

    _seed_index(tmp_path, overview={"generatedAt": 1, "digest": "   "})  # 空综述
    assert tts.main() == 0
    assert "latest_audio" not in json.loads((tmp_path / "index.json").read_text(encoding="utf-8"))


def test_main_成功写latest_audio全字段并清同日旧产物(frozen_now, monkeypatch, tmp_path):
    _seed_index(tmp_path, overview={"generatedAt": 1724907660000, "digest": " 综述正文 "})
    monkeypatch.setattr(tts, "_ensure_binary", lambda: Path("/fake/qwen-tts"))
    monkeypatch.setattr(tts, "_ensure_models", lambda: (Path("/t"), Path("/c")))
    seen = {}

    def fake_synth(binary, models, text, voice, audio_dir, rel_prefix, deadline):
        seen.update(text=text, voice=voice, rel=rel_prefix)
        return ("audio/2026-08-29/broadcast.mp3", 4321, 99999)

    monkeypatch.setattr(tts, "_synthesize_merged", fake_synth)
    stale = tmp_path / "audio" / "2026-08-29" / "stale.mp3"
    stale.parent.mkdir(parents=True)
    stale.write_text("old")
    monkeypatch.setattr(sys, "argv", ["tts_broadcast.py", "--out-dir", str(tmp_path)])

    assert tts.main() == 0
    idx = json.loads((tmp_path / "index.json").read_text(encoding="utf-8"))
    assert idx["latest_audio"] == {
        "generatedAt": 1724907660000,  # 对齐 overview 旧值:继承日不能没音频
        "voice": "serena",
        "model": "qwen3-tts-0.6b-customvoice",
        "file": "audio/2026-08-29/broadcast.mp3",
        "title": "今日速报",
        "durationMs": 4321,
        "bytes": 99999,
    }
    assert seen["text"] == "综述正文"      # 与 App 兜底系统 TTS 同文本(两侧须同步)
    assert seen["rel"] == "audio/2026-08-29"
    assert not stale.exists()              # 同日旧产物清重建,目录内容 == 清单


def test_main_voice环境变量覆盖默认(frozen_now, monkeypatch, tmp_path):
    _seed_index(tmp_path, overview={"generatedAt": 1, "digest": "综述"})
    monkeypatch.setenv("AI_NEWS_HUB_TTS_VOICE", "vivian")
    monkeypatch.setattr(tts, "_ensure_binary", lambda: Path("/b"))
    monkeypatch.setattr(tts, "_ensure_models", lambda: (Path("/t"), Path("/c")))
    monkeypatch.setattr(tts, "_synthesize_merged",
                        lambda *a: ("audio/2026-08-29/broadcast.mp3", 1, 1))
    monkeypatch.setattr(sys, "argv", ["tts_broadcast.py", "--out-dir", str(tmp_path)])

    assert tts.main() == 0
    assert json.loads((tmp_path / "index.json").read_text(encoding="utf-8"))["latest_audio"]["voice"] == "vivian"


def test_main_合成失败不写latest_audio(monkeypatch, tmp_path):
    _seed_index(tmp_path, overview={"generatedAt": 1, "digest": "综述"})
    monkeypatch.setattr(tts, "_ensure_binary", lambda: Path("/b"))
    monkeypatch.setattr(tts, "_ensure_models", lambda: (Path("/t"), Path("/c")))
    monkeypatch.setattr(tts, "_synthesize_merged", lambda *a: None)
    monkeypatch.setattr(sys, "argv", ["tts_broadcast.py", "--out-dir", str(tmp_path)])

    assert tts.main() == 0  # 失败只告警:App 回落系统 TTS
    assert "latest_audio" not in json.loads((tmp_path / "index.json").read_text(encoding="utf-8"))


# ===== __main__ 兜底:任何路径不抛非零(子进程跑真文件) =====

def _run_script(out_dir):
    env = {k: v for k, v in os.environ.items() if not k.startswith("AI_NEWS_HUB_TTS")}
    return subprocess.run(
        [sys.executable, str(Path(tts.__file__).resolve()), "--out-dir", str(out_dir)],
        capture_output=True, text=True, env=env, timeout=120)


def test_script_空产物目录退出0(tmp_path):
    r = _run_script(tmp_path)
    assert r.returncode == 0
    assert "不存在" in r.stdout + r.stderr


def test_script_坏JSON索引整阶段兜底退出0(tmp_path):
    (tmp_path / "index.json").write_text("not-json", encoding="utf-8")
    r = _run_script(tmp_path)
    assert r.returncode == 0  # __main__ 包装:异常只告警,绝不拦推送
    assert "语音速报整阶段失败" in r.stderr
