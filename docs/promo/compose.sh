#!/bin/bash
# 创意版合成:frames/*.jpg → 段视频 → 录屏叠动态版面 → xfade 串接 → 配乐。
# 从仓库根执行。源脚本与成片在 docs/promo/(入库);中间产物写 design/promo2/(gitignore)。
# 段落时长 6.0,3.2,4.4,3.0,6.6,5.6,4.2,5.4 / xfade 0.4 → 总长 35.6s。
set -u
cd "$(dirname "$0")/../.."          # 仓库根
DOCS=docs/promo
WORK=design/promo2/work
FRAMES=design/promo2/frames
CLEAN=design/promo2/clean
mkdir -p "$WORK"

# 配乐:docs/promo/assets/ 优先(自行放入 music_wallpaper.mp3),回落 v1 工程曲源
MUSIC="$DOCS/assets/music_wallpaper.mp3"
[ -f "$MUSIC" ] || MUSIC=design/promo/assets/music_wallpaper.mp3

ENC="-c:v libx264 -preset medium -crf 15 -pix_fmt yuv420p"

# 1) 动效帧序列 → 段视频
ffmpeg -v error -y -framerate 30 -i "$FRAMES"/intro_%04d.jpg $ENC -an "$WORK"/intro.mp4
ffmpeg -v error -y -framerate 30 -i "$FRAMES"/outro_%04d.jpg $ENC -an "$WORK"/outro.mp4
for seg in launch today weekly trends read dark; do
  ffmpeg -v error -y -framerate 30 -i "$FRAMES"/bgm_${seg}_%04d.jpg $ENC -an "$WORK"/bgm_$seg.mp4
done

# 2) normalize <seg> <ss> <dur>:录屏裁切叠到动态版面右侧槽位(432x960,圆角遮罩)
normalize() {
  local seg=$1 ss=$2 dur=$3
  echo "norm $seg  ss=$ss dur=$dur"
  ffmpeg -v error -y -ss $ss -i "$CLEAN"/seg_$seg.mp4 \
    -i "$WORK"/bgm_$seg.mp4 \
    -framerate 30 -loop 1 -t $dur -i "$DOCS"/assets/slot_mask_432x960.png \
    -filter_complex \
"[0:v:0]fps=30,scale=432:960:flags=lanczos,setsar=1,tpad=stop_mode=clone:stop_duration=1.5[ph];\
[ph][2:v]alphamerge,format=rgba[phr];\
[1:v]fps=30[bg];[bg][phr]overlay=1234:60,format=yuv420p[v]" \
    -map "[v]" -t $dur $ENC -an "$WORK"/norm_$seg.mp4
}
normalize launch 0.15 3.2
normalize today  0.05 4.4
normalize weekly 2.00 3.0
normalize trends 0.50 6.6
normalize read   0.30 5.6
normalize dark   0.30 4.2

# 3) 串接(开场→冷启动 fadewhite;版面段间 wipeleft 翻页;→结尾 fade)+ 配乐
#    响度 -14 LUFS,0.3s 淡入 / 1.2s 淡出
ffmpeg -v error -y \
  -i "$WORK"/intro.mp4 -i "$WORK"/norm_launch.mp4 -i "$WORK"/norm_today.mp4 -i "$WORK"/norm_weekly.mp4 \
  -i "$WORK"/norm_trends.mp4 -i "$WORK"/norm_read.mp4 -i "$WORK"/norm_dark.mp4 -i "$WORK"/outro.mp4 \
  -i "$MUSIC" \
  -filter_complex \
"[0][1]xfade=transition=fadewhite:duration=0.4:offset=5.6[v1];\
[v1][2]xfade=transition=wipeleft:duration=0.4:offset=8.4[v2];\
[v2][3]xfade=transition=wipeleft:duration=0.4:offset=12.4[v3];\
[v3][4]xfade=transition=wipeleft:duration=0.4:offset=15.0[v4];\
[v4][5]xfade=transition=wipeleft:duration=0.4:offset=21.2[v5];\
[v5][6]xfade=transition=wipeleft:duration=0.4:offset=26.4[v6];\
[v6][7]xfade=transition=fade:duration=0.4:offset=30.2[vout];\
[8:a]atrim=0:36.2,loudnorm=I=-14:TP=-1.5:LRA=11,afade=t=in:st=0:d=0.3,afade=t=out:st=34.4:d=1.2[aout]" \
  -map "[vout]" -map "[aout]" -t 35.6 \
  -c:v libx264 -preset slow -crf 17 -pix_fmt yuv420p \
  -c:a aac -b:a 192k -movflags +faststart \
  "$DOCS"/ai_news_hub_promo_creative_169.mp4

ffprobe -v error -show_entries format=duration,size -of default=nw=1 "$DOCS"/ai_news_hub_promo_creative_169.mp4
