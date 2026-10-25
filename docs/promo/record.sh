#!/bin/bash
# 宣传视频重录(2026-10-25 网络已恢复;源脚本在 docs/promo,工作区 design/promo2):
#   - 全段无「网络不可用,正在展示缓存数据」提示条
#   - read 段改为打开 HackerNews 条目(今日重点 #2 Steal My Bitcoin,外网 WebView),
#     不再用 AIHot 精选(#1);外网页面加载慢,点击后等待加长
# 其余操作时序与 v1(design/promo/record.sh)一致。从仓库根执行。产物 design/promo2/raw/。
set -u
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
S="-s emulator-5554"
PKG=com.peng.ainewshub.debug
OUT=design/promo2/raw
mkdir -p "$OUT"

# 固定坐标(2026-10-25 标定,1080x2400;#2 坐标来自 uiautomator dump)
TAP_WEEKLY="input tap 69 198"        # 今天页顶栏「周刊」
TAP_HOT="input tap 880 198"          # 今天页顶栏「热词」
TAP_CLOUD="input tap 980 568"        # 热词榜页「词云」入口
TAP_HN_ITEM="input tap 540 1306"     # 今日重点 #2(Show HN: Steal My Bitcoin · HackerNews)
TAP_FAV="input tap 903 216"          # WebView 顶栏「收藏」
TAP_ICON="input tap 910 1970"        # 主屏 App 图标
SWIPE="input swipe 540 1700 540 700 700"
SWIPE_SMALL="input swipe 540 1500 540 1000 500"

start_app() {  # 冷启动到今天页顶部
  $ADB $S shell am force-stop $PKG
  sleep 0.8
  $ADB $S shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 3.2
}
rec_on()  { $ADB $S shell "screenrecord --bit-rate 16000000 /sdcard/$1 >/dev/null 2>&1 </dev/null &"; }
rec_off() { $ADB $S shell pkill -INT screenrecord; sleep 2; $ADB $S pull "/sdcard/$1" "$OUT/$1" >/dev/null; }
home()    { $ADB $S shell input keyevent HOME; }

echo "== S1 今天页滚动 =="
start_app
rec_on seg_today.mp4
sleep 0.7;  $ADB $S shell $SWIPE
sleep 0.6;  $ADB $S shell $SWIPE
sleep 0.6;  $ADB $S shell $SWIPE
sleep 1.2
rec_off seg_today.mp4

echo "== S2 AI 周刊 =="
start_app
rec_on seg_weekly.mp4
sleep 0.5;  $ADB $S shell $TAP_WEEKLY
sleep 2.2;  $ADB $S shell $SWIPE
sleep 0.5;  $ADB $S shell $SWIPE
sleep 1.3
rec_off seg_weekly.mp4

echo "== S3 热词榜 + 词云 =="
start_app
rec_on seg_trends.mp4
sleep 0.5;  $ADB $S shell $TAP_HOT
sleep 2.0;  $ADB $S shell $SWIPE_SMALL
sleep 0.9;  $ADB $S shell $TAP_CLOUD
sleep 2.2;  $ADB $S shell input tap 540 1050   # 点词触发挥词重排动画
sleep 2.0
rec_off seg_trends.mp4

echo "== S4 阅读收藏(HackerNews 条目) =="
start_app
rec_on seg_read.mp4
sleep 0.5;  $ADB $S shell $TAP_HN_ITEM
sleep 4.0;  $ADB $S shell $SWIPE          # 外网页面加载较慢,多等
sleep 0.7;  $ADB $S shell $TAP_FAV
sleep 1.8
rec_off seg_read.mp4

echo "== S5 深色模式 =="
# 教训(承 v1):uimode 切换触发 activity 重建,须等数据稳定再切、切换后等 3s 再滚动
$ADB $S shell svc power stayon true
start_app
sleep 2
rec_on seg_dark.mp4
sleep 1.6;  $ADB $S shell cmd uimode night yes
sleep 3.0;  $ADB $S shell input swipe 540 1700 540 700 900
sleep 1.4
rec_off seg_dark.mp4
$ADB $S shell cmd uimode night no
sleep 1

echo "== S0 冷启动(主屏点图标) =="
$ADB $S shell am force-stop $PKG
home; sleep 1.5
rec_on seg_launch.mp4
sleep 1.2;  $ADB $S shell $TAP_ICON
sleep 4.2
rec_off seg_launch.mp4

echo "== 校验 =="
for f in "$OUT"/seg_*.mp4; do
  printf "%s  " "$(basename "$f")"
  ffprobe -v error -show_entries format=duration -of csv=p=0 "$f"
done
