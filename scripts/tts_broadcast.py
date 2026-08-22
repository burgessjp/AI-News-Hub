#!/usr/bin/env python3
"""语音速报:把今日总览(latest_overview)预合成为播报音频并回写 index.json 清单。

流水线位置:fetch_data.py 之后、push_data.py 之前(pipeline.sh 步骤 2/3)。
App 端「语音速报」优先播放这里的预生成音频(MOSS-TTS-Nano 神经语音,自然度
远超系统 TTS);音频清单缺失 / 批次滞后时 App 自动回落系统 TTS,互不影响。

产物:
  - out/audio/<YYYY-MM-DD>/entry-NN.mp3  单声道 48kHz 64kbps(ffmpeg 缺失时保留
    wav);固定文件名,同日多批次天然覆盖不累积;
  - out/index.json 顶层追加 latest_audio(即时字段,≈1.5KB 有界):
    { generatedAt(对齐 latest_overview.generatedAt,App 据此判定新鲜度),
      voice, model, entries: [{file, title, durationMs, bytes}] }

失败语义(对齐 trend_keywords.write_trends「失败只告警不阻断推送」):
  依赖缺失(本地未装 TTS 依赖)/ overview 缺失 / 单条合成失败 / 整阶段异常
  —— 一律告警 + exit 0;index.json 不写 latest_audio → App 回落系统 TTS,
  下个批次自愈。任何路径都不抛非零(pipeline.sh set -e 下不拦推送)。

资产自举(仓库零大二进制,全部动态获取):
  - MOSS_TTS_NANO_DIR      直接指向已有 MOSS-TTS-Nano 克隆(本地复用已下载模型)
  - 否则用 third_party/MOSS-TTS-Nano:缺代码标记文件 onnx_tts_runtime.py 时
    git 浅拉固定 SHA(MOSS_TTS_NANO_REF 可覆盖)。CI 以 actions/cache 缓存整个
    目录(key 绑同一 SHA)—— 不能只缓存 models/ 子目录,cache 恢复会让 clone
    目标目录非空,代码永远补不上,TTS 自缓存命中之日起静默失效;
  - 模型两件套(728MB)由 OnnxTtsRuntime 首次初始化经 huggingface_hub 自动
    下载至 <MOSS_REPO>/models(仅首次;HF_ENDPOINT=https://hf-mirror.com 可镜像)。

用法:
  python3 scripts/tts_broadcast.py --out-dir out

环境变量:
  AI_NEWS_HUB_TTS_DISABLE=1  整体跳过
  AI_NEWS_HUB_TTS_VOICE      音色,默认 Yuewen(备选 Junhao/Zhiming/Weiguo/Xiaoyu/Lingyu)
  MOSS_TTS_NANO_DIR          已有 MOSS-TTS-Nano 克隆路径
  MOSS_TTS_NANO_REF          代码库 pin(SHA),默认下方 DEFAULT_MOSS_REF
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

# MOSS-TTS-Nano 代码库 pin:升级时改这里 + .github/workflows/fetch-data.yml 的
# MOSS_TTS_NANO_REF(缓存 key 绑它,两处不同步会导致缓存与代码版本错配)。
DEFAULT_MOSS_REF = "cc7bdf19c7639c0870dab22045a33b442760f6be"
MOSS_REPO_URL = "https://github.com/OpenMOSS/MOSS-TTS-Nano.git"

# 代码标记文件:存在即视为克隆完整(clone 条件判它而非目录是否存在,兼容
# 「models/ 已被 actions/cache 恢复但代码缺失」的缓存场景)。
CODE_MARKER = "onnx_tts_runtime.py"

DEFAULT_VOICE = "Yuewen"


def _ensure_moss_repo():
    """定位/自举 MOSS-TTS-Nano 代码库,返回仓库 Path;无法就绪返回 None。

    优先级:MOSS_TTS_NANO_DIR 指定路径 > third_party/MOSS-TTS-Nano(缺代码
    标记时按 pin SHA 浅拉)。任何失败由调用方的整阶段兜底捕获(告警 exit 0)。
    """
    env_dir = os.environ.get("MOSS_TTS_NANO_DIR", "").strip()
    if env_dir:
        repo = Path(env_dir).expanduser().resolve()
        if not (repo / CODE_MARKER).is_file():
            print(f"[TTS][WARN] MOSS_TTS_NANO_DIR 缺代码标记 {CODE_MARKER}:{repo},跳过语音速报",
                  file=sys.stderr)
            return None
        return repo

    repo = Path(__file__).resolve().parent.parent / "third_party" / "MOSS-TTS-Nano"
    if (repo / CODE_MARKER).is_file():
        return repo

    ref = os.environ.get("MOSS_TTS_NANO_REF", "").strip() or DEFAULT_MOSS_REF
    print(f"[TTS] 浅拉 MOSS-TTS-Nano @ {ref} → {repo}")
    repo.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    # remote add 在目录半初始化的重试场景会因 origin 已存在而失败,set-url 兜底
    add = subprocess.run(["git", "-C", str(repo), "remote", "add", "origin", MOSS_REPO_URL],
                         capture_output=True, text=True)
    if add.returncode != 0:
        subprocess.run(["git", "-C", str(repo), "remote", "set-url", "origin", MOSS_REPO_URL],
                       check=True)
    subprocess.run(["git", "-C", str(repo), "fetch", "--depth", "1", "origin", ref], check=True)
    subprocess.run(["git", "-C", str(repo), "checkout", "--quiet", "FETCH_HEAD"], check=True)
    return repo


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


def _duration_ms(path, ffprobe):
    """读音频时长(毫秒):优先 ffprobe,wav 用标准库 wave 兜底;失败返回 0。"""
    try:
        if ffprobe:
            out = subprocess.run(
                [ffprobe, "-v", "error", "-show_entries", "format=duration",
                 "-of", "csv=p=0", str(path)],
                capture_output=True, text=True,
            )
            if out.returncode == 0 and out.stdout.strip():
                return int(float(out.stdout.strip()) * 1000)
        if path.suffix == ".wav":
            with wave.open(str(path), "rb") as wav_file:
                return int(wav_file.getnframes() / float(wav_file.getframerate()) * 1000)
    except Exception:
        pass
    return 0


def _synthesize_all(runtime, playlist, voice, audio_dir, rel_prefix):
    """逐条合成 + 编码,返回 latest_audio.entries;单条失败跳过该条(告警)。

    MP3 规格:单声道 48kHz 64kbps(≈0.48MB/分钟,11 条全量 ≈2.4MB/天)。
    ffmpeg 缺失(本地调试机常见)时保留 wav,时长用 wave 模块兜底读取。
    """
    ffmpeg = shutil.which("ffmpeg")
    ffprobe = shutil.which("ffprobe")
    if not ffmpeg:
        print("[TTS][WARN] 本机无 ffmpeg,产物保留 wav(App 可直接播,CI runner 预装 ffmpeg)",
              file=sys.stderr)

    entries = []
    for index, (title, text) in enumerate(playlist):
        wav_path = audio_dir / f"entry-{index:02d}.wav"
        try:
            # 长文本自动按 75 token 分块 + 块间插静音(synthesize 内建),无需外层切分
            runtime.synthesize(
                text=text, voice=voice, output_audio_path=str(wav_path),
                streaming=False, sample_mode="fixed",
            )
        except Exception as e:
            print(f"[TTS][WARN] 第 {index} 条合成失败,跳过:{type(e).__name__}: {e}",
                  file=sys.stderr)
            wav_path.unlink(missing_ok=True)
            continue

        final_path = wav_path
        if ffmpeg:
            mp3_path = audio_dir / f"entry-{index:02d}.mp3"
            enc = subprocess.run(
                [ffmpeg, "-y", "-loglevel", "error", "-i", str(wav_path),
                 "-ac", "1", "-ar", "48000", "-b:a", "64k", str(mp3_path)],
                capture_output=True, text=True,
            )
            if enc.returncode != 0:
                print(f"[TTS][WARN] 第 {index} 条 MP3 编码失败,保留 wav:{enc.stderr.strip()}",
                      file=sys.stderr)
            else:
                wav_path.unlink(missing_ok=True)
                final_path = mp3_path

        if not final_path.exists() or final_path.stat().st_size == 0:
            print(f"[TTS][WARN] 第 {index} 条产物缺失,跳过", file=sys.stderr)
            continue
        entries.append({
            "file": f"{rel_prefix}/{final_path.name}",
            "title": title,
            "durationMs": _duration_ms(final_path, ffprobe),
            "bytes": final_path.stat().st_size,
        })
    return entries


def main():
    parser = argparse.ArgumentParser(description="语音速报:总览预合成播报音频(MOSS-TTS-Nano)")
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

    repo = _ensure_moss_repo()
    if repo is None:
        return 0
    sys.path.insert(0, str(repo))
    try:
        from onnx_tts_runtime import OnnxTtsRuntime
    except ImportError as e:
        print(f"[TTS][WARN] TTS 依赖未安装({e}),跳过语音速报;"
              f"CI 装 scripts/requirements-tts.txt + torch CPU / WeTextProcessing"
              f"(见该文件头注释)", file=sys.stderr)
        return 0

    print(f"[TTS] 合成 {len(playlist)} 条(voice={voice},输出 {audio_dir})…")
    # model_dir 走默认(<repo>/models),缺失自动从 HuggingFace 下载(仅首次)
    runtime = OnnxTtsRuntime(thread_count=4)

    entries = _synthesize_all(runtime, playlist, voice, audio_dir, f"audio/{date_dir}")
    if not entries:
        print("[TTS][WARN] 全部条目合成失败,本批不写 latest_audio(App 回落系统 TTS)",
              file=sys.stderr)
        return 0

    # 读-改-写 index.json:只在成功后追加 latest_audio,格式与 fetch_data.write_index
    # 一致(ensure_ascii=False + indent=2)
    index["latest_audio"] = {
        "generatedAt": generated_at,
        "voice": voice,
        "model": "moss-tts-nano",
        "entries": entries,
    }
    index_path.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")
    total_mb = sum(e["bytes"] for e in entries) / 1024 / 1024
    print(f"[TTS] 完成:{len(entries)}/{len(playlist)} 条,{total_mb:.2f}MB,"
          f"latest_audio 已写入 index.json")
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
