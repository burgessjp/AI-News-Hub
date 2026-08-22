#!/usr/bin/env bash
#
# AI News Hub 数据流水线(需求 b):抓数据 → AI 总结 → 语音速报 → 推仓库。
#
# 这是 CI 的唯一入口(.github/workflows/fetch-data.yml 只调本脚本),
# 也可在本地手工跑:`bash scripts/pipeline.sh`。
#
# 执行前统一检测 4 个环境变量(需求 b-i),缺任一直接退出码 1:
#   AI_NEWS_HUB_AI_BASE_URL   AI 兼容服务根 URL(如 https://api.deepseek.com)
#   AI_NEWS_HUB_AI_MODEL      模型名(如 deepseek-chat)
#   AI_NEWS_HUB_AI_API_KEY    OpenAI 兼容 key
#   GITCODE_TOKEN             gitcode 个人访问令牌(推数据仓库用)
#
# 失败语义:
#   - 任一环境变量缺失 → exit 1(执行前检测,根本不跑抓取)
#   - 抓取全失败(fetch_data.py exit 1)→ set -e 让脚本停下,不推空数据
#   - 抓取部分成功 → 照常推送(单源失败不影响其余,对齐 fetch_data.py 策略)
#   - 语音速报(tts_broadcast.py)自身保证任何失败只告警不抛非零,不拦推送
set -euo pipefail

# 切到仓库根(脚本可从任意目录调用)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# ===== 需求 b-i:执行前检测 4 个环境变量 =====
REQUIRED_ENVS=(
  AI_NEWS_HUB_AI_BASE_URL
  AI_NEWS_HUB_AI_MODEL
  AI_NEWS_HUB_AI_API_KEY
  GITCODE_TOKEN
)
missing=()
for env in "${REQUIRED_ENVS[@]}"; do
  if [ -z "${!env:-}" ]; then
    missing+=("$env")
  fi
done
if [ ${#missing[@]} -gt 0 ]; then
  echo "[FATAL] 缺少环境变量: ${missing[*]}" >&2
  echo "        请在 CI Secrets / 本地 export 中补齐后再跑。" >&2
  exit 1
fi

echo "=== [1/3] 抓取数据 + AI 总结 ==="
python3 scripts/fetch_data.py --out-dir out

echo "=== [2/3] 语音速报预合成(MOSS-TTS-Nano,失败只告警) ==="
python3 scripts/tts_broadcast.py --out-dir out

echo "=== [3/3] 推送到 gitcode (news-hub-data) ==="
python3 scripts/push_data.py --data-dir out

echo "=== 流水线完成 ==="
