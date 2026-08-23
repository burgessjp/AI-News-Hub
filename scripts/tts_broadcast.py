#!/usr/bin/env python3
"""语音速报:把今日总览的综述(latest_overview.digest)预合成为单段播报
音频并回写 index.json。仅朗读综述,不含 Top10 条目明细。

流水线位置:fetch_data.py 之后、push_data.py 之前(pipeline.sh 步骤 2/3)。
App 端「语音速报」优先播放这里的预生成音频(Qwen3-TTS 神经语音,自然度
远超系统 TTS);音频清单缺失 / 批次滞后时 App 自动回落系统 TTS,互不影响。

产物:
  - out/audio/<YYYY-MM-DD>/broadcast.mp3  单段综述播报(仅 digest;单声道
    24kHz 48kbps,ffmpeg 缺失时保留 wav);同日多批次天然覆盖不累积;
  - out/index.json 顶层追加 latest_audio(即时字段,有界):
    { generatedAt(对齐 latest_overview.generatedAt,App 据此判定新鲜度),
      voice, model, file, title, durationMs, bytes }

失败语义(对齐 trend_keywords.write_trends「失败只告警不阻断推送」):
  引擎/模型缺失且自举失败 / overview 缺失 / 综述合成重试 1 次后仍失败 /
  阶段墙钟预算耗尽 / 整阶段异常 —— 一律告警 + exit 0;index.json 不写
  latest_audio → App 回落系统 TTS,下个批次自愈。任何路径都不抛非零
  (pipeline.sh set -e 下不拦推送);阶段预算(STAGE_BUDGET_S)从墙钟上
  钉死本阶段耗时上限,是「不拦推送」不靠运气成立的兜底。

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
  AI_NEWS_HUB_TTS_BUDGET_S   整阶段墙钟预算秒数(默认 1200,含自举与合成,
                             超时告警跳过;0 = 不限,仅本地调试)
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
import time
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
# GGUF 完整性校验:文件头 magic + 字节数下限(实测 talker 968,588,544 /
# codec 291,150,624,留 ~10% 余量)。只查「非空」会被缓存恢复出的残卷骗过 ——
# 坏文件加载天天失败,而缓存 key 绑 pin 不变、坏文件每天原样恢复,永不自愈
GGUF_MAGIC = b"GGUF"
MODEL_MIN_BYTES = {
    TALKER_GGUF: 850 * 1024 * 1024,
    CODEC_GGUF: 260 * 1024 * 1024,
}

# CustomVoice 预置音色(小写);--lang 取 GGUF 元数据里的语言名
DEFAULT_VOICE = "serena"
TTS_LANG = "Chinese"
# 固定采样种子:同日多批次重跑时同文本产物一致,manifest 字节数稳定
TTS_SEED = "1793"
# 单次合成上限(秒):CI 4 核 CPU 上 RTF≈1~3,最坏条目 ~2 分钟,2.5 倍余量
SYNTH_TIMEOUT_S = 300
# 整阶段墙钟预算(秒):自举(构建/下载)+ 全部合成共用一个 deadline,超时
# 告警跳过。TTS 在 push 之前执行,预算保证任何 hang/慢批次都吃不穿 workflow
# 总时限、「不拦推送」不靠运气成立(单次合成 timeout 取 min(上限,剩余预算))。
# AI_NEWS_HUB_TTS_BUDGET_S 可覆盖,0 = 不限(仅本地调试用)
STAGE_BUDGET_S = 1200

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


def _gguf_ok(path):
    """GGUF 在位校验:magic 头 + 字节数下限(空文件/残卷不算在位)。"""
    try:
        if path.stat().st_size < MODEL_MIN_BYTES[path.name]:
            return False
        with path.open("rb") as f:
            return f.read(4) == GGUF_MAGIC
    except OSError:
        return False


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
            if dl.returncode == 0:
                if _gguf_ok(target):
                    return True
                # curl 报成功但校验不过(截断/损坏):删掉整份重来,残卷续传只会继续错
                print(f"[TTS][WARN] {name} 下载成功但完整性校验不过,删除重试",
                      file=sys.stderr)
                target.unlink(missing_ok=True)
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
        if _gguf_ok(path):
            continue
        if path.is_file():
            # 非空残卷(缓存恢复损坏/历史假成功下载):删掉强制重下,否则天天失败不自愈
            print(f"[TTS][WARN] {name} 已存在但完整性校验不过,删除重下", file=sys.stderr)
            path.unlink(missing_ok=True)
        if not _download_gguf(name, models_dir):
            return None
    return talker, codec


def _build_text(overview):
    """取播报文本:仅跨源综述 digest(trim),不含 Top10 条目明细。

    文本必须与 App 端兜底系统 TTS 朗读的文本一致(OverviewScreen 总览速报
    入口同样取 digest.trim()),两侧必须同步改动,否则预生成音频与兜底朗读
    的内容会不同。
    """
    return str(overview.get("digest") or "").strip()


def _synthesize_merged(binary, models, text, voice, audio_dir, rel_prefix, deadline):
    """把综述文本合成为单段音频,返回 (file, durationMs, bytes)。

    综述百字级,单次合成远低于引擎 2048 帧(≈164s)上限,无需分段拼接;
    失败重试 1 次(瞬时抖动不损失全天音频),两次失败/预算耗尽整批放弃。
    整体编码 MP3(单声道 24kHz 48kbps,引擎原生 24kHz 不升采样,≈0.5MB/
    天);ffmpeg 缺失(本地调试机常见)时保留 wav(App 可直接播,CI runner
    预装 ffmpeg)。

    deadline 是整阶段墙钟预算的时刻(time.monotonic 口径,None = 不限):
    起进程前查剩余,不足即告警放弃;单次合成 timeout 取 min(单次上限,
    剩余)—— 阶段墙钟被硬性钉住,任何 hang/慢批次都拖不垮其后的推送步骤。
    """
    talker, codec = models
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        print("[TTS][WARN] 本机无 ffmpeg,产物保留 wav(App 可直接播,CI runner 预装 ffmpeg)",
              file=sys.stderr)

    wav_path = audio_dir / "broadcast.wav"
    done = False
    for attempt in (1, 2):
        rem = None if deadline is None else deadline - time.monotonic()
        if rem is not None and rem <= 0:
            print("[TTS][WARN] 阶段墙钟预算耗尽(合成未起),本批不产音频", file=sys.stderr)
            return None
        timeout = SYNTH_TIMEOUT_S if rem is None else min(SYNTH_TIMEOUT_S, rem)
        try:
            # 文本经 stdin 传入(避免命令行转义/长度问题);固定 --seed 保证
            # 同日重跑同文本产物一致
            synth = _run(
                [str(binary), "--model", str(talker), "--codec", str(codec),
                 "--speaker", voice, "--lang", TTS_LANG, "--seed", TTS_SEED,
                 "-o", str(wav_path)],
                input=text.encode("utf-8"), timeout=timeout,
            )
            if synth.returncode != 0 or not wav_path.is_file():
                raise RuntimeError(
                    f"exit={synth.returncode}: "
                    f"{(synth.stderr or b'').decode(errors='replace')[-300:]}")
            done = True
            break
        except Exception as e:
            wav_path.unlink(missing_ok=True)  # 半成品不留,重试整份重来
            if attempt == 1:
                print(f"[TTS][WARN] 综述合成失败,重试 1 次:{type(e).__name__}: {e}",
                      file=sys.stderr)
    if not done:
        print("[TTS][WARN] 综述重试仍失败,本批不产音频", file=sys.stderr)
        return None

    # 时长直接读 wav 帧数(精确且不依赖 ffprobe)
    with wave.open(str(wav_path), "rb") as w:
        duration_s = w.getnframes() / float(w.getframerate())

    final_path = wav_path
    if ffmpeg:
        mp3_path = audio_dir / "broadcast.mp3"
        enc = subprocess.run(
            [ffmpeg, "-y", "-loglevel", "error", "-i", str(wav_path),
             "-ac", "1", "-ar", "24000", "-b:a", "48k", str(mp3_path)],
            capture_output=True, text=True,
        )
        if enc.returncode != 0:
            print(f"[TTS][WARN] MP3 编码失败,保留 wav:{enc.stderr.strip()}", file=sys.stderr)
        else:
            wav_path.unlink(missing_ok=True)
            final_path = mp3_path

    if not final_path.exists() or final_path.stat().st_size == 0:
        print("[TTS][WARN] 单段音频产物缺失", file=sys.stderr)
        return None
    return f"{rel_prefix}/{final_path.name}", int(duration_s * 1000), final_path.stat().st_size


def main():
    parser = argparse.ArgumentParser(description="语音速报:总览预合成播报音频(Qwen3-TTS)")
    parser.add_argument("--out-dir", default="out", help="产物根目录(默认 ./out,与 fetch_data 一致)")
    args = parser.parse_args()

    if os.environ.get("AI_NEWS_HUB_TTS_DISABLE", "").strip() == "1":
        print("[TTS] AI_NEWS_HUB_TTS_DISABLE=1,跳过语音速报")
        return 0

    # 阶段墙钟预算:覆盖自举(构建/下载)+ 合成的全阶段(非法值交给顶层
    # 异常兜底,告警跳过本批)。预算保证 TTS 永远吃不穿 workflow 总时限。
    budget_raw = os.environ.get("AI_NEWS_HUB_TTS_BUDGET_S", "").strip()
    budget = float(budget_raw) if budget_raw else float(STAGE_BUDGET_S)
    deadline = time.monotonic() + budget if budget > 0 else None

    index_path = Path(args.out_dir) / "index.json"
    if not index_path.is_file():
        print(f"[TTS][WARN] {index_path} 不存在(fetch 未产出?),跳过语音速报", file=sys.stderr)
        return 0
    index = json.loads(index_path.read_text(encoding="utf-8"))
    overview = index.get("latest_overview")
    # 仅「字段不存在」才视为无总览。总览生成失败但继承了上次的批次字段仍在
    # (generatedAt 为旧值)——照常生成,音频 generatedAt 对齐旧值,App 侧与
    # digest 比对相等,行为正确(继承日不能没音频)。综述文本是否可播由下方
    # _build_text 结果兜底(items 不再是语音的前置条件)。
    if not isinstance(overview, dict):
        print(f"[TTS][WARN] index.json 无 latest_overview,跳过语音速报", file=sys.stderr)
        return 0

    text = _build_text(overview)
    if not text:
        print("[TTS][WARN] 总览综述(digest)为空,无可播报,跳过", file=sys.stderr)
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

    print(f"[TTS] 合成综述单段({len(text)} 字,voice={voice},输出 {audio_dir})…")
    merged = _synthesize_merged(binary, models, text, voice, audio_dir,
                                 f"audio/{date_dir}", deadline)
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
    print(f"[TTS] 完成:综述单段 {duration_ms / 1000:.0f}s,"
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
