# AI News Hub 创意版宣传片 SPEC

在 v1 报纸风宣传片(`design/promo/`,本地工作区)基础上升级的 16:9 创意版:把产品逻辑
「8 源聚合 → AI 排序 → 一屏读懂」直接做成开场叙事动效,中段版面注入时间维度,
结尾雷达 logo 呼应收束。

- 成片:`docs/promo/ai_news_hub_promo_creative_169.mp4`(1920×1080 · 30fps · H.264+AAC · 35.6s)
- 用途:B 站 / GitHub README
- 配乐:Wallpaper — Kevin MacLeod(CC-BY 4.0,发布需署名)

## 1. 节奏总表(8 段 · 7 个 xfade 0.4s · 总长 35.6s)

| # | 段 | 时长 | 素材来源 | 转场(出) |
|---|---|---|---|---|
| 1 | 开场动效 | 6.0s | PIL 逐帧 `intro_%04d.jpg`(180 帧) | fadewhite(闪白) |
| 2 | 冷启动 | 3.2s | 录屏 `seg_launch.mp4` 裁 0.15–3.35 | wipeleft |
| 3 | 今天页 | 4.4s | `seg_today.mp4` 裁 0.05–4.45 | wipeleft |
| 4 | AI 周刊 | 3.0s | `seg_weekly.mp4` 裁 2.00–5.00 | wipeleft |
| 5 | 热词/词云 | 6.6s | `seg_trends.mp4` 裁 0.50–7.10 | wipeleft |
| 6 | 沉浸阅读 | 5.6s | `seg_read.mp4` 裁 0.30–5.90 | wipeleft |
| 7 | 墨色夜读 | 4.2s | `seg_dark.mp4` 裁 0.30–4.50 | fade |
| 8 | 结尾动效 | 5.4s | PIL 逐帧 `outro_%04d.jpg`(162 帧) | — |

版面段文案定义在 `render_motion.py` 的 BG_SEGS 配置区(与 v1 `render_assets_169.py` 一致)。

## 2. 开场动效分镜(6.0s,创意核心)

全部元素延续报纸三色(纸白 #FAF7F2 / 墨 #1C1917 / 报纸红 #B93B1D / 纸金 #9C6B2F)。

| 时间 | 画面 |
|---|---|
| 0.0–0.6 | 纸白底淡入,双细页框线条按笔画方向「画出」 |
| 0.2–2.0 | 8 个数据源名以墨色铅字块(淡灰圆角底、微旋转)从画布外 8 方向 easeOutCubic 飞入,散布版心后 sin 微幅漂浮 |
| 1.8–3.0 | 中央报纸红规线从中点向两侧展开;铅字块受牵引向版心聚拢(easeInOutCubic)缩小淡出,熔进版面 |
| 2.6–3.6 | 版面文字逐行「印刷」:眉题 AI NEWS HUB → 宋体大标题「全网 AI 热点 / 一屏尽览」逐字上移淡入 → 红规线左→右画出 |
| 3.4–5.2 | 右侧图标位:纸金雷达弧逐圈画出(3 圈同心弧,呼应 ic_launcher),报纸红扫描线顺时针扫 360° |
| 4.1–5.2 | 扫描过程中依次「锁定」3 条热点:红点编号 + 锁定环收缩 + 标题从右侧滑入;**标题取 today 段画面真实 Top 3**(与片中数据自洽) |
| 5.4–6.0 | 全画面缓推 1.00→1.03 + 0.2s 闪白收束 → 转冷启动 |

## 3. 中段升级(相对 v1)

1. **版面背景动态化**:每段版面改为逐帧渲染——段首 ~0.6s 左侧文字(眉题/大标题/
   副题/品牌行)从左 40px 滑入 + 淡入,逐行 stagger 2 帧,之后静止;右侧手机槽
   (432×960 + 投影细框)不参与动画。版面帧数与录屏段裁切时长严格一致。
2. **翻页转场**:版面段之间 xfade wipeleft;开场→冷启动 fadewhite;深色→结尾 fade。
3. 录屏画面处理链:`fps=30 → scale 432:960 lanczos → 圆角 alphamerge(slot_mask) → overlay 1234,60`。

## 4. 结尾动效分镜(5.4s)

| 时间 | 画面 |
|---|---|
| 0–1.0 | 版面淡入:AI News Hub 宋体大标题 + 红规线画出 + 眉题「你的 AI 资讯早刊」 |
| 0.6–2.2 | 右侧:雷达弧逐圈画出 + 中心红点两连闪(与开场呼应) |
| 2.0–3.0 | 「即刻下载」红底胶囊按钮上移淡入 |
| 2.8–4.2 | 底部两行:「8 源聚合 · AI 摘要 · 中英双语」+ `GitHub Releases · github.com/burgessjp/AI-News-Hub` |
| 4.6–5.4 | 全段缓推 1.0→1.025 + 淡向纸色(与 BGM 淡出同步收) |

## 5. 音频

- 取曲首 36.2s:`loudnorm=I=-14` 归一(实测 −14.1 LUFS),0.3s 淡入、1.2s 淡出,`-t 35.6` 对齐视频。
- 卡点策略:Wallpaper ≈113 BPM;段落边界尽量落整拍,试听微调(±0.2s),不做帧级自动对拍。

## 6. 工程结构(源入库,产物在工作区)

```
docs/promo/                        # 入库:源脚本 + 成片 + 本 spec
├── ai_news_hub_promo_creative_169.mp4
├── render_motion.py               # PIL 逐帧动效渲染(常量内联,自包含)
├── compose.sh                     # ffmpeg 合成(成片输出回 docs/promo/)
├── record.sh / make_clean.sh      # 录屏 / CFR 规整
├── assets/slot_mask_432x960.png   # 槽位圆角遮罩
├── SPEC.md / README.md            # 本文件 / 工程说明

design/promo2/                     # gitignore 工作区:raw/ clean/ frames/ work/
design/promo/                      # v1 工程(本地):曲源 music_wallpaper.mp3 等
```

- 渲染:3840×2160 超采样绘制 → LANCZOS 缩至 1920×1080。帧量 ≈1134,约 1 分钟。
  缓动 easeOutCubic / easeInOutCubic;点击坐标标定用 `uiautomator dump`。
- 合成:帧序列成段 → normalize(录屏叠版面)→ xfade 串接 →
  `libx264 crf 17 preset slow, yuv420p, aac 192k, +faststart`。
- 依赖:scripts/.venv(PIL)、ffmpeg、模拟器录屏(debug 包)。零外部新依赖。

## 7. 验收标准

- [x] 1920×1080 · 30fps · H.264+AAC · 35.6s · faststart,B 站/GitHub 可直接播放
- [x] 开场锁定 3 条标题与 today 段画面 Top 3 一致(Brotli RCE / Steal My Bitcoin / 1Password for Agents)
- [x] 每段抽帧 QC:无错帧、无文字裁切、动画无跳变
- [x] BGM 响度 −14.1 LUFS,首尾无爆音
- [x] 全片无「网络不可用 / 网络异常」提示(2026-10-25 重录素材)
- [x] read 段为 HackerNews 条目原文站(stealmybitcoin.com),非 AIHot 站内页

## 8. 已知取舍

- dark 段 uimode 切换触发重建,偶发 gitcode 数据仓库超时 → 板块弹错误占位;处置:单独重录
- Dock 中 App 图标为系统主题图标样式,与品牌图标不完全一致
- launch 段前 ~0.7s 为桌面起手画面
- BGM 卡点手动试听对齐,非自动对拍
