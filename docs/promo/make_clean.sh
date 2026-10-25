#!/bin/bash
# 把工作区 design/promo2/raw/ 的 screenrecord 原始件(VFR、容器时长虚标)
# 整条转成 CFR 30fps 的 design/promo2/clean/。重录(raw 变更)后必须重跑。
set -u
cd "$(dirname "$0")/../.."          # 仓库根
mkdir -p design/promo2/clean
for s in launch today weekly trends read dark; do
  ffmpeg -v error -y -i design/promo2/raw/seg_$s.mp4 -vf fps=30,format=yuv420p \
    -c:v libx264 -preset fast -crf 16 -an design/promo2/clean/seg_$s.mp4 && echo "clean $s ok"
done
