#!/usr/bin/env python3
"""语音速报:把今日总览(latest_overview)预合成为单段播报音频并回写 index.json。

流水线位置:fetch_data.py 之后、push_data.py 之前(pipeline.sh 步骤 2/3)。
App 端「语音速报」优先播放这里的预生成音频(Qwen3-TTS 神经语音,自然度
远超系统 TTS);音频清单缺失 / 批次滞后时 App 自动回落系统 TTS,互不影响。

产物:
  - out/audio/<YYYY-MM-DD>/broadcast.mp3  单段全量播报(综述 + Top10 连读,
    段间 0.6s 静音;单声道 24kHz 48kbps,ffmpeg 缺失时保留 wav);同日多
    批次天然覆盖不累积;
  - out/index.json 顶层追加 latest_audio(即时字段,有界):
    { generatedAt(对齐 latest_overview.generatedAt,App 据此判定新鲜度),
      voice, model, file, title, durationMs, bytes }

失败语义(对齐 trend_keywords.write_trends「失败只告警不阻断推送」):
  引擎/模型缺失且自举失败 / overview 缺失 / 任一条目合成失败 / 整阶段异常
  —— 一律告警 + exit 0;index.json 不写 latest_audio → App 回落系统 TTS,
  下个批次自愈。任何路径都不抛非零(pipeline.sh set -e 下不拦推送)。
  单段音频要求全条成功才产出(缺任何一条都整批不写,避免用户听着缺条目
  却无从察觉;与此前分段方案「条数不符整批回落」的用户效果一致)。

引擎(Qwen3-TTS,qwentts.cpp,纯 C++ 无 Python ML 依赖):
  - 模型  Qwen3-TTS-12Hz-0.6B-CustomVoice(Apache-2.0,预置音色),GGUF
    两件套 ≈1.2GB:talker(924MB Q8_0)+ tokenizer/codec(278MB Q8_0),
    24kHz 单声道输出,文本规范化内建(数字/符号原生处理);
  - 工具  ServeurpersoCom/qwentts.cpp(MIT,GGML C++17),CPU/CUDA/Metal
    通用;固定 --seed 保证同日重跑产物稳定;
  - 资产自举(仓库零大二进制):third_party/tts-engine/ 下按需克隆代码
    (pin SHA)+ cmake 构建 + curl 下载 GGUF;CI 以 actions/cache 缓存整个
    目录(key 绑同一 SHA)。文件级判在位(二进制/GGUF 各自查),缓存部分
    恢复也能补齐缺失部分。

用法:
  python3 scripts/tts_broadcast.py --out-dir out

环境变量:
  AI_NEWS_HUB_TTS_DISABLE=1  整体跳过
  AI_NEWS_HUB_TTS_VOICE      音色,默认 serena(备选 vivian/uncle_fu/dylan
                             北京话/eric 四川话/ryan/aiden/ono_anna/sohee)
  QWENTTS_DIR                已有 qwentts.cpp 检出路径(含 build/qwen-tts)
  QWENTTS_MODELS_DIR         已有 GGUF 目录
  QWENTTS_REF                代码库 pin(SHA),默认下方 DEFAULT_QWENTTS_REF
  QWENTTS_HF_HOST            GGUF 下载源(默认 huggingface.co,失败自动换
                             hf-mirror.com 镜像;CI 境外直连,本地可指镜像)
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import wave
from pathlib import Path

# 北京时间:音频目录日期与流水线其余产物统一(从 common 引入)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import now_cst

# qwentts.cpp 代码库 pin:升级时改这里 + .github/workflows/fetch-data.yml 的
# QWENTTS_REF(缓存 key 绑它,两处不同步会导致缓存与代码版本错配)。
DEFAULT_QWENTTS_REF = "a8a7716b530e49fed537c57711247c12fbbb903c"
QWENTTS_REPO_URL = "https://github.com/ServeurpersoCom/qwentts.cpp.git"

# GGUF 两件套(Serveurperso/Qwen3-TTS-GGUF,Q8_0;talker 924MB + codec 278MB)
TALKER_GGUF = "qwen-talker-0.6b-customvoice-Q8_0.gguf"
CODEC_GGUF = "qwen-tokenizer-12hz-Q8_0.gguf"
GGUF_HOSTS = [
    "https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main",
    "https://hf-mirror.com/Serveurperso/Qwen3-TTS-GGUF/resolve/main",
]

# CustomVoice 预置音色(小写);--lang 取 GGUF 元数据里的语言名
DEFAULT_VOICE = "serena"
TTS_LANG = "Chinese"
# 固定采样种子:同日多批次重跑时同文本产物一致,manifest 字节数稳定
TTS_SEED = "1793"
# 单条合成上限(秒):CI 4 核 CPU 上 RTF≈1~3,百字条目最坏 ~2 分钟,长综述留足余量
SYNTH_TIMEOUT_S = 900
# 拼接单段音频时的段间静音时长(秒):条目连读的自然停顿
SEGMENT_GAP_S = 0.6

ENGINE_ROOT = Path(__file__).resolve().parent.parent / "third_party" / "tts-engine"


def _run(cmd, **kwargs):
    """subprocess.run 包装:统一 text=False、check 由调用方判定。"""
    return subprocess.run(cmd, capture_output=True, **kwargs)


def _clone_pinned(url, ref, dest):
    """按 pin SHA 浅拉代码库到 dest(容忍半初始化目录重试,remote 冲突兜底)。"""
    dest.mkdir(parents=True, exist_ok=True)
    _run(["git", "init", "-q", str(dest)], check=True)
    add = _run(["git", "-C", str(dest), "remote", "add", "origin", url])
    if add.returncode != 0:
        _run(["git", "-C", str(dest), "remote", "set-url", "origin", url], check=True)
    _run(["git", "-C", str(dest), "fetch", "--depth", "1", "origin", ref], check=True)
    _run(["git", "-C", str(dest), "checkout", "--quiet", "FETCH_HEAD"], check=True)


def _ensure_binary():
    """定位/构建 qwen-tts 二进制,返回其 Path;无法就绪返回 None。

    QWENTTS_DIR 指定路径优先(需已含 build/qwen-tts);否则用
    third_party/tts-engine/qwentts.cpp,缺代码/缺二进制时按 pin 克隆并
    cmake 构建(构建依赖 cmake + C++ 编译器,缺失即告警跳过,CI 与本地
    均已预装)。文件级判在位:缓存部分恢复(如有)也能各补各的。
    """
    env_dir = os.environ.get("QWENTTS_DIR", "").strip()
    repo = Path(env_dir).expanduser().resolve() if env_dir else ENGINE_ROOT / "qwentts.cpp"
    binary = repo / "build" / "qwen-tts"
    if binary.is_file() and os.access(binary, os.X_OK):
        return binary

    if not (repo / "CMakeLists.txt").is_file():
        ref = os.environ.get("QWENTTS_REF", "").strip() or DEFAULT_QWENTTS_REF
        print(f"[TTS] 浅拉 qwentts.cpp @ {ref} → {repo}")
        try:
            _clone_pinned(QWENTTS_REPO_URL, ref, repo)
            # ggml 是子模块(ServeurpersoCom/ggml fork),必须一并检出才能构建
            _run(["git", "-C", str(repo), "submodule", "update", "--init", "ggml"], check=True)
        except Exception as e:
            print(f"[TTS][WARN] qwentts.cpp 克隆失败:{type(e).__name__}: {e},跳过语音速报",
                  file=sys.stderr)
            return None

    cmake = shutil.which("cmake")
    if not cmake:
        print("[TTS][WARN] 本机无 cmake,无法构建 qwen-tts,跳过语音速报", file=sys.stderr)
        return None
    jobs = str(max(1, (os.cpu_count() or 4)))
    print(f"[TTS] 构建 qwen-tts(cmake -j{jobs})…")
    try:
        cfg = _run([cmake, "-S", str(repo), "-B", str(repo / "build"),
                    "-DCMAKE_BUILD_TYPE=Release"])
        if cfg.returncode != 0:
            raise RuntimeError(cfg.stderr.decode(errors="replace")[-500:])
        build = _run([cmake, "--build", str(repo / "build"),
                      "--config", "Release", "-j", jobs])
        if build.returncode != 0:
            raise RuntimeError(build.stderr.decode(errors="replace")[-500:])
    except Exception as e:
        print(f"[TTS][WARN] qwen-tts 构建失败:{e},跳过语音速报", file=sys.stderr)
        return None
    if not binary.is_file():
        print(f"[TTS][WARN] 构建成功但未找到 {binary},跳过语音速报", file=sys.stderr)
        return None
    return binary


def _download_gguf(name, dest_dir):
    """下载单个 GGUF(断点续传,主源失败换 hf-mirror 镜像),成功返回 True。"""
    target = dest_dir / name
    for host in ([os.environ.get("QWENTTS_HF_HOST", "").strip()] if
                 os.environ.get("QWENTTS_HF_HOST", "").strip() else GGUF_HOSTS):
        url = f"{host.rstrip('/')}/{name}"
        for attempt in range(3):
            print(f"[TTS] 下载 {name} ← {host}(第 {attempt + 1} 次)…")
            dl = _run(["curl", "-sL", "--retry", "5", "--retry-delay", "3", "-C", "-",
                       "--max-time", "3600", "-o", str(target), url])
            if dl.returncode == 0 and target.is_file() and target.stat().st_size > 0:
                return True
        print(f"[TTS][WARN] {url} 下载失败,尝试下一源", file=sys.stderr)
    target.unlink(missing_ok=True)
    return False


def _ensure_models():
    """定位/下载 GGUF 两件套,返回 (talker, codec);无法就绪返回 None。"""
    env_dir = os.environ.get("QWENTTS_MODELS_DIR", "").strip()
    models_dir = Path(env_dir).expanduser().resolve() if env_dir else ENGINE_ROOT / "models"
    models_dir.mkdir(parents=True, exist_ok=True)
    talker, codec = models_dir / TALKER_GGUF, models_dir / CODEC_GGUF
    for name, path in ((TALKER_GGUF, talker), (CODEC_GGUF, codec)):
        if path.is_file() and path.stat().st_size > 0:
            continue
        if not _download_gguf(name, models_dir):
            return None
    return talker, codec


def _build_playlist(overview):
    """按 App 端 buildOverviewPlaylist(ui/overview/OverviewScreen.kt)同规则拼播报条目。

    综述条 = digest.trim()(digest 为空串时没有综述条);其余各条 = title,
    comment 非空才拼 "。" + comment(原样拼接,不去尾号)。两侧规则必须一致,
    否则预生成音频与 App 兜底系统 TTS 的朗读内容会不同。返回 [(title, text)]。
    """
    playlist = []
    lead = str(overview.get("digest") or "").strip()
    if lead:
        playlist.append(("今日速报", lead))
    for item in overview.get("items") or []:
        if not isinstance(item, dict):
            continue
        title = str(item.get("title") or "").strip()
        if not title:
            continue
        comment = str(item.get("comment") or "")
        text = title + ("。" + comment if comment.strip() else "")
        playlist.append((title, text))
    return playlist


def _write_silence(path, seconds):
    """生成静音 wav(24kHz 单声道 S16,与引擎输出同规格)。"""
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(24000)
        w.writeframes(b"\x00\x00" * int(24000 * seconds))


def _concat_wavs(paths, out_path):
    """按序拼接 wav 到单个文件,返回总时长(秒)。

    各段参数须一致(引擎输出恒 24kHz/mono/S16,静音段同规格手工生成);
    不一致即抛错,由调用方按整阶段失败处理。
    """
    frames = 0
    with wave.open(str(out_path), "wb") as out:
        for i, p in enumerate(paths):
            with wave.open(str(p), "rb") as r:
                if i == 0:
                    out.setparams(r.getparams())
                elif (r.getnchannels(), r.getsampwidth(), r.getframerate()) != (
                    out.getnchannels(), out.getsampwidth(), out.getframerate()
                ):
                    raise ValueError(f"wav 参数不一致,无法拼接: {p}")
                data = r.readframes(r.getnframes())
                out.writeframes(data)
                frames += r.getnframes()
    return frames / 24000.0


def _synthesize_merged(binary, models, playlist, voice, audio_dir, rel_prefix):
    """合成全部条目并拼接为单段全量音频,返回 (file, durationMs, bytes)。

    逐条独立合成(引擎单次上限 2048 帧 ≈164s,全量连读会超;逐条也让超时
    控制有界)→ 按播放顺序拼接、段间插 SEGMENT_GAP_S 静音 → 整体编码 MP3
    (单声道 24kHz 48kbps,引擎原生 24kHz 不升采样,全量 ≈1MB/天)。任一
    条目失败返回 None(单段缺条用户无从察觉,全条成功才产出,App 整批回落
    系统 TTS);ffmpeg 缺失(本地调试机常见)时保留 wav(App 可直接播,
    CI runner 预装 ffmpeg)。
    """
    talker, codec = models
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        print("[TTS][WARN] 本机无 ffmpeg,产物保留 wav(App 可直接播,CI runner 预装 ffmpeg)",
              file=sys.stderr)

    seg_paths = []
    try:
        for index, (title, text) in enumerate(playlist):
            wav_path = audio_dir / f"seg-{index:02d}.wav"
            try:
                # 文本经 stdin 传入(避免命令行转义/长度问题);固定 --seed 保证
                # 同日重跑同文本产物一致。超时/失败视作整批失败。
                synth = _run(
                    [str(binary), "--model", str(talker), "--codec", str(codec),
                     "--speaker", voice, "--lang", TTS_LANG, "--seed", TTS_SEED,
                     "-o", str(wav_path)],
                    input=text.encode("utf-8"), timeout=SYNTH_TIMEOUT_S,
                )
                if synth.returncode != 0 or not wav_path.is_file():
                    raise RuntimeError(
                        f"exit={synth.returncode}: "
                        f"{(synth.stderr or b'').decode(errors='replace')[-300:]}")
            except Exception as e:
                print(f"[TTS][WARN] 第 {index} 条({title[:24]})合成失败,本批不产音频:"
                      f"{type(e).__name__}: {e}", file=sys.stderr)
                return None
            seg_paths.append(wav_path)
            if index < len(playlist) - 1:
                gap_path = audio_dir / f"gap-{index:02d}.wav"
                _write_silence(gap_path, SEGMENT_GAP_S)
                seg_paths.append(gap_path)

        # 时长直接从拼接后 wav 的帧数算(精确且不依赖 ffprobe)
        merged_wav = audio_dir / "broadcast.wav"
        duration_s = _concat_wavs(seg_paths, merged_wav)

        final_path = merged_wav
        if ffmpeg:
            mp3_path = audio_dir / "broadcast.mp3"
            enc = subprocess.run(
                [ffmpeg, "-y", "-loglevel", "error", "-i", str(merged_wav),
                 "-ac", "1", "-ar", "24000", "-b:a", "48k", str(mp3_path)],
                capture_output=True, text=True,
            )
            if enc.returncode != 0:
                print(f"[TTS][WARN] MP3 编码失败,保留 wav:{enc.stderr.strip()}", file=sys.stderr)
            else:
                merged_wav.unlink(missing_ok=True)
                final_path = mp3_path

        if not final_path.exists() or final_path.stat().st_size == 0:
            print("[TTS][WARN] 单段音频产物缺失", file=sys.stderr)
            return None
        return f"{rel_prefix}/{final_path.name}", int(duration_s * 1000), final_path.stat().st_size
    finally:
        # 分段/静音临时文件不落盘推送(overlay 会推 out/ 全量)
        for pattern in ("seg-*.wav", "gap-*.wav"):
            for p in audio_dir.glob(pattern):
                p.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description="语音速报:总览预合成播报音频(Qwen3-TTS)")
    parser.add_argument("--out-dir", default="out", help="产物根目录(默认 ./out,与 fetch_data 一致)")
    args = parser.parse_args()

    if os.environ.get("AI_NEWS_HUB_TTS_DISABLE", "").strip() == "1":
        print("[TTS] AI_NEWS_HUB_TTS_DISABLE=1,跳过语音速报")
        return 0

    index_path = Path(args.out_dir) / "index.json"
    if not index_path.is_file():
        print(f"[TTS][WARN] {index_path} 不存在(fetch 未产出?),跳过语音速报", file=sys.stderr)
        return 0
    index = json.loads(index_path.read_text(encoding="utf-8"))
    overview = index.get("latest_overview")
    # 仅「字段不存在 / 无 items」才跳过。总览生成失败但继承了上次的批次字段仍在
    # (generatedAt 为旧值)——照常生成,音频 generatedAt 对齐旧值,App 侧与
    # digest 比对相等,行为正确(继承日不能没音频)。
    if not isinstance(overview, dict) or not overview.get("items"):
        print("[TTS][WARN] index.json 无 latest_overview,跳过语音速报", file=sys.stderr)
        return 0

    playlist = _build_playlist(overview)
    if not playlist:
        print("[TTS][WARN] 总览无可播报内容(digest 空且无条目),跳过", file=sys.stderr)
        return 0
    generated_at = int(overview.get("generatedAt") or 0)

    voice = os.environ.get("AI_NEWS_HUB_TTS_VOICE", "").strip() or DEFAULT_VOICE
    date_dir = now_cst().strftime("%Y-%m-%d")
    audio_dir = Path(args.out_dir) / "audio" / date_dir
    # 清掉同日旧产物再重建:同日重跑时条目数可能变化,残留文件会被 overlay
    # 推上去但清单不引用,重建保证「目录内容 == 清单」严格一致
    shutil.rmtree(audio_dir, ignore_errors=True)
    audio_dir.mkdir(parents=True, exist_ok=True)

    binary = _ensure_binary()
    if binary is None:
        return 0
    models = _ensure_models()
    if models is None:
        print("[TTS][WARN] GGUF 模型未能就绪,跳过语音速报", file=sys.stderr)
        return 0

    print(f"[TTS] 合成 {len(playlist)} 条并拼接单段(voice={voice},输出 {audio_dir})…")
    merged = _synthesize_merged(binary, models, playlist, voice, audio_dir, f"audio/{date_dir}")
    if merged is None:
        print("[TTS][WARN] 音频未能产出,本批不写 latest_audio(App 回落系统 TTS)",
              file=sys.stderr)
        return 0
    audio_file, duration_ms, size_bytes = merged

    # 读-改-写 index.json:只在成功后追加 latest_audio,格式与 fetch_data.write_index
    # 一致(ensure_ascii=False + indent=2)
    index["latest_audio"] = {
        "generatedAt": generated_at,
        "voice": voice,
        "model": "qwen3-tts-0.6b-customvoice",
        "file": audio_file,
        "title": "今日速报",
        "durationMs": duration_ms,
        "bytes": size_bytes,
    }
    index_path.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[TTS] 完成:单段全量 {len(playlist)} 条内容,{duration_ms / 1000:.0f}s,"
          f"{size_bytes / 1024 / 1024:.2f}MB,latest_audio 已写入 index.json")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except Exception as e:  # 整阶段失败只告警,绝不阻断推送
        print(f"[TTS][WARN] 语音速报整阶段失败(不阻断推送):{type(e).__name__}: {e}",
              file=sys.stderr)
        sys.exit(0)
