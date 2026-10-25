# AI News Hub 宣传视频 · 创意版工程

创意版宣传片源工程:`ai_news_hub_promo_creative_169.mp4`(1920×1080 · 35.6s · 30fps · H.264+AAC · ~9MB),
设计 spec 见 [SPEC.md](SPEC.md)。核心创意:产品逻辑「8 源聚合 → AI 排序 → 一屏读懂」
做成开场叙事动效(铅字汇聚成报 → 雷达扫描锁定今日真实 Top 3),版面段文字动态滑入 +
翻页转场,结尾雷达 logo 呼应收束。

**目录约定**:本目录(docs/promo)是入库的「源」——脚本、成片、spec;
中间产物(`raw/ clean/ frames/ work/`,~300MB)一律写 `design/promo2/`(gitignore 工作区),
不进 git。v1 报纸风工程与曲源在 `design/promo/`(本地)。

## 内容结构

| 时间 | 画面 |
|---|---|
| 0:00–6.0 | **开场动效**:8 个源名铅字块飞入漂浮 → 汇入版心熔成版面 → 标题印刷 + 雷达弧扫描,锁定今日真实 Top 3 → 闪白 |
| 6.0–9.2 | 冷启动(桌面点图标 → App 启动) |
| 9.2–12.8 | 今天页滚动(今日重点 Top 10 → 分源摘要) |
| 12.8–15.4 | AI 周刊(杂志式版面) |
| 15.4–21.2 | 热词榜 → 趋势词云 |
| 21.2–26.4 | WebView 沉浸阅读(HackerNews 条目原文站)+ 收藏「已藏」反馈 |
| 26.4–30.2 | 系统深色切换瞬间 + 墨色夜读 |
| 30.2–35.6 | **结尾动效卡**:雷达弧逐圈画出 + 红点双闪 + 即刻下载 + GitHub 地址,缓推淡出 |

## 重跑 / 改稿(均在仓库根执行)

```bash
# 0. 重录(模拟器在线、网络正常、debug 包已安装)
bash docs/promo/record.sh            # → design/promo2/raw/;改点击位置先 uiautomator dump 标定
bash docs/promo/make_clean.sh        # raw → clean/(CFR 规整),重录后必跑

# 1. 动效帧(PIL 逐帧,4K 超采样,~1 分钟)
scripts/.venv/bin/python docs/promo/render_motion.py              # 全部
scripts/.venv/bin/python docs/promo/render_motion.py --only intro # 仅开场
# 可选 --only: intro / outro / bg / launch / today / weekly / trends / read / dark

# 2. 合成(→ docs/promo/ai_news_hub_promo_creative_169.mp4)
bash docs/promo/compose.sh
```

- 改版面文案/开场标题 → `render_motion.py` 的 `BG_SEGS` 配置区与顶部 `TOP3`/`SOURCES` 常量;
  **换日重录后 `TOP3` 必须同步为当天今天页真实前三条**,保持片内数据自洽
- 段落时长/裁切/xfade 偏移集中在 `compose.sh`(偏移 = 前段累计 − 0.4,改时长需重算,
  且同步 `render_motion.py` 的 `BG_SEGS` 段时长)
- 配乐:`docs/promo/assets/music_wallpaper.mp3`(自放)缺失时回落 `design/promo/assets/` 曲源

## 配乐署名(发布必读)

**"Wallpaper" — Kevin MacLeod (incompetech.com),CC-BY 4.0**
(<https://creativecommons.org/licenses/by/4.0/>)。发布视频简介需署名,如:
`Music: Wallpaper – Kevin MacLeod, incompetech.com, CC BY 4.0`
