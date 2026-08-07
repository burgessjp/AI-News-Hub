# 版本记录

本项目遵循 [Semantic Versioning](https://semver.org/)。

## [Unreleased]

首个公开发布版本,将在打 `v1.0.0` tag 时正式发布。功能已在下方列出。

## [1.0.0] - 未发布

首个公开发布版本。

### 核心功能

- **三大根 Tab** — 总览（流水线预生成跨源分析）/ 摘要（流水线预生成要点）/ 更多（设置与信息源聚合入口）
- **8 个信息源** — HackerNews、GitHub Trending、OpenAI × Anthropic、HuggingFace Papers、Product Hunt、The Rundown AI、stormzhang AI、AIHot 精选
- **双模式取数** — 5 个稳定源支持「实时抓取 / 归档快照」切换，归档来自配套数据流水线
- **运行时 AI 能力**（用户自配 key）— 整页翻译、系统选中翻译、按「模型 × 月」用量统计
- **预生成 AI 内容**（流水线，无需 key）— 跨源今日热点 Top10（含 Breaking 标记）、各源中文要点
- **内置 WebView** — 阅读模式（Mozilla Readability）、整页翻译对照、长按图片/链接操作、字号跟随系统
- **桌面小组件「今日热点」** — Glance 直接读归档 `latest_overview`，30 分钟自动刷新
- **信息源自定义排序** — 长按拖拽，持久化于 DataStore，摘要 Tab 跟随用户顺序
- **个性化** — Material You 动态取色（Android 12+）、字体族切换、字号档位、暗色模式

### 数据流水线

- `scripts/pipeline.sh` 统一编排 8 源抓取 + AI 摘要 + 跨源总览 + 推送
- 每日北京时间 15:30 定时执行（Product Hunt 当日批次 15:01 上线后），单源失败跳过且 latest 指针继承
- LinuxDo 套 Cloudflare 强挑战，用 Playwright 真 Chromium 过 CF

### 工程化

- GitHub Actions：PR 构建 / tag 发版 / 数据流水线三路 CI
- 版本目录管理依赖（`gradle/libs.versions.toml`）
- Release 开启 R8 + shrinkResources
