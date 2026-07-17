# AGENTS.md

本文件面向 AI 编码代理，介绍本仓库的结构、构建方式与开发约定。阅读前无需任何项目背景。

> 注：仓库另有 `CLAUDE.md`，内容与本文件大量重叠但可能滞后；以本文件为准。

## 项目概述

**AIHot**（应用名 "AI News Hub"）—— Android AI 资讯聚合客户端。Kotlin + Jetpack Compose + Material3，单模块（`:app`），包名 / namespace / applicationId 均为 `com.example.aihot`。

数据来源分两类：

- **自有后端**：`aihot.virxact.com` 公开 API（动态分页 `/items`、今日热点 `/hot-topics`、AI 日报 `/daily`、归档 `/dailies`）。
- **Hub 浏览区 5 个第三方源**：HackerNews（Firebase API）、GitHub Trending、LinuxDo 热榜、stormzhang AI 资讯、HuggingFace Papers（后三个为 jsoup HTML 抓取）。其中 4 个稳定源（除 LinuxDo）支持「实时抓取 / gitcode 归档」双模式切换（`SourceMode`），归档数据来自配套的数据流水线（见下「数据流水线」）。

功能面：摘要 Tab（各源当日 AI 中文要点）、精选 / 全部动态、AI 日报与归档、搜索、HN 评论树、AI 翻译（OpenAI 兼容服务，用户自配 key）、内置 WebView、浏览历史（Room）。

## 仓库结构

```
app/                     唯一 Android 模块
  src/main/java/com/example/aihot/
    MainActivity.kt      自定义多栈导航 + Page/Screen 路由（不用 Navigation Compose）
    data/                Repository、数据模型、Room、DataStore 配置
    data/source/         SourceMode(LIVE/ARCHIVE)、ArchiveHttpClient、各源归档实现
    ui/                  ViewModel 与 Screen，按功能分包（tabs/summary/items/daily/more/
                         webview/components/theme/anim/translate）
scripts/                 Python 数据流水线 + 图标生成脚本（见「数据流水线」）
.github/workflows/       build.yml / release.yml / fetch-data.yml
docs/news-hub-data-usage.md  数据仓库（gitcode AI-News-Hub-Data）格式与消费方式文档
gradle/libs.versions.toml    版本目录（所有依赖版本集中在此）
```

关键构建文件：根 `build.gradle.kts`（仅插件声明）、`app/build.gradle.kts`（签名/构建类型/依赖）、`settings.gradle.kts`、`gradle.properties`、`app/proguard-rules.pro`。

## 构建与常用命令

```bash
./gradlew assembleDebug        # 编译 debug（日常验证手段）
./gradlew installDebug         # 安装到设备
./gradlew assembleRelease      # 编译 release（需签名配置，见「安全注意事项」）
./gradlew installRelease
```

工具链：AGP 8.7.3、Kotlin 2.0.21（Compose 编译器插件）、KSP 2.0.21-1.0.28（Room）、JDK/JVM target 17、minSdk 24、compileSdk/targetSdk 35、Compose BOM 2024.12.01。版本改动只动 `gradle/libs.versions.toml`。

## 测试与验证

**没有单元测试、没有 lint 配置**（`app/src` 下只有 `main`）。验证方式就是 `assembleDebug` 编译通过 + 真机/模拟器手测。改动后至少跑 `./gradlew assembleDebug` 确认编译通过。

## CI/CD 与发布

- `.github/workflows/build.yml`：push / PR 到 main 时跑 `./gradlew assembleDebug` 并上传 debug APK 构件。
- `.github/workflows/release.yml`：打 `v*` tag 触发。用 secrets 还原 keystore 与 `keystore.properties` → `assembleRelease` → 创建 GitHub Release 并附 APK。所需 secrets：`RELEASE_KEYSTORE_BASE64` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`。
- `.github/workflows/fetch-data.yml`：数据流水线（见下节），每天北京时间 06:00 / 14:00 定时 + 手动触发。

## 数据流水线（scripts/）

App「Hub」浏览区归档数据的生产端：抓 5 个第三方源 → AI 总结 → 推送到 gitcode 数据仓库 `peng1818/AI-News-Hub-Data` 的 `news-hub-data` 分支。数据格式详见 `docs/news-hub-data-usage.md`。

- `pipeline.sh` —— 唯一编排入口（CI 与本地都调它）：执行前检测 4 个环境变量（缺任一直接 exit 1）：`AI_NEWS_HUB_AI_BASE_URL` / `AI_NEWS_HUB_AI_MODEL` / `AI_NEWS_HUB_AI_API_KEY` / `GITCODE_TOKEN`。
- `fetch_data.py` —— 抓取 5 源落盘 `out/`。单源独立重试 3 次（2s/4s），失败源跳过且 `index.json` latest 指针从上一次继承（客户端永远拿到有效数据）；≥1 源成功退出码即为 0。日期/路径统一北京时间（CI 设 `TZ=Asia/Shanghai`）。
- `ai_summary.py` —— 给 4 个稳定源（linuxdo 除外）生成简体中文要点写入快照顶层 `ai_summary` 字段（OpenAI 兼容调用，temperature 0.5）；失败仅 warn，不阻断落盘。
- `push_data.py` —— 把 `out/` 提交推送到数据仓库（token 注入 URL，需 `GITCODE_TOKEN`）。
- `gen_icon.py` / `gen_icon_svg.py` + `icon.svg` —— 启动图标生成（PIL/NumPy；SVG 版依赖 macOS `qlmanage`）。

本地运行：

```bash
pip install -r scripts/requirements.txt   # requests / beautifulsoup4 / playwright
python -m playwright install --with-deps chromium   # 仅 LinuxDo 源过 Cloudflare 需要
export AI_NEWS_HUB_AI_BASE_URL=... AI_NEWS_HUB_AI_MODEL=... AI_NEWS_HUB_AI_API_KEY=... GITCODE_TOKEN=...
bash scripts/pipeline.sh
# 本地干跑（跳过 AI 总结与旧 index 继承）：
python3 scripts/fetch_data.py --out-dir out --no-summary --no-previous-index
```

`out/`、`repo/`（推送前浅克隆目录）、`__pycache__/` 均已 gitignore。已知风险：LinuxDo 套 Cloudflare 强挑战，CI 数据中心 IP 常被拦，单源失败属预期行为。

## App 架构

### 导航（MainActivity.kt，不用 Navigation Compose）

- 3 个根 tab（`AppTab`）：摘要 `Summary` / AIHot 精选 `Featured` / 更多 `More`；`currentTab` + `pageStacks: Map<AppTab, List<Page>>` 每 tab 独立二级页栈，切 tab 保留各自栈。
- `Page` 是 private sealed interface（Detail/Web/All/Daily/Search/Settings/About/HackerNews/HackerNewsComments/GitHubTrending/LinuxDo/StormzhangAiNews/HuggingFacePapers/BrowseHistory/DailyArchive/DailyDate），经 `toBundle()`/`pageFromBundle()` + 自定义 `Saver` 挂到 `rememberSaveable`，进程被杀可恢复。**新增二级页必须同步加：Page 子类、toBundle/pageFromBundle 分支、PageView 分支。**
- 转场集中在 `ui/anim/Motion.kt` 的 `pageTransition()`：默认 PUSH（横向推入），含 WebView 的页 override 为 FADE（AndroidView 位移会撕裂）。
- 浮动药丸底栏：`Box` 叠层而非 `Scaffold(bottomBar)`；内容 edge-to-edge，底栏 overlay 在 BottomCenter，二级页 `AnimatedVisibility` 滑出；列表用 `BottomBarReservedHeight` 预留底部空间。
- `openUrl` 是打开网页的唯一入口：在此统一记录浏览历史（Room），再 push `Page.Web`。

### 数据层（data/）

- 网络一律 `OkHttpClient`（connect 15s / read 20s / 浏览器 UA），**不引入 Retrofit**；JSON 用内置 `org.json`，**不引入 Gson/Moshi**；HTML 抓取用 jsoup（GitHub Trending / stormzhang / HuggingFace）。
- 无 DI 框架：Repository 在 ViewModel / Composable 内直接构造。
- 双模式取数（`data/source/`）：4 个稳定源各有 `XxxSource` 接口 + 实时 Repository + `XxxArchiveRepository`；ViewModel 按 `SourceMode`（DataStore `display_prefs` 的 `source_mode`，默认 LIVE）选择实现。归档走 `ArchiveHttpClient`（gitcode 官方 REST API raw 端点，不用 raw 直链——背后是 WAF 会 403；index.json 有 2 分钟内存缓存 + Mutex 并发去重）。归档模式失败直接显示 Error 态，**不回退实时**。LinuxDo 不参与切换，始终实时。
- `SummaryRepository`：AI 摘要 Tab 的摘要**不在 App 端运行时生成**，直接读归档快照顶层 `ai_summary` 字段（由数据流水线预生成）；`ai_summary` 缺失即失败态。
- `AiChatClient`：OpenAI 兼容 chat 调用统一出口（`${baseUrl}/chat/completions`，baseUrl 含版本段），App 内所有端侧 AI 功能都经此访问「设置 → AI 服务」里的用户配置。
- `TranslationRepository`：运行时经 `AiChatClient` 调用户自配的 AI 服务（温度 0.3），SHA256 缓存到 `cacheDir` 文件，Mutex 按 key 防并发重复；成功后把 token 用量写入 `AiUsageStore`。`TranslateSelectionActivity` 响应 `ACTION_PROCESS_TEXT` 在系统选中菜单注册「译」。
- `NewsRepository`：自有后端 `/items`（cursor 分页）、`/hot-topics`、`/daily`、`/dailies`。
- 数据模型集中在 `NewsItem.kt` / `HackerNews.kt` / 各源单文件（`TrendingRepo.kt`、`LinuxDoTopic.kt`、`StormzhangAiNews.kt`、`HuggingFacePaper.kt`）；`NewsItem`、`HackerNewsStory` 用 `@Parcelize`。

### 持久化

- `SettingsStore`（DataStore `display_prefs`）：主题模式 / 字体 / 数据源模式。
- `AiConfigStore`（DataStore `ai_prefs`）：全局 AI 服务配置——服务商预设（DeepSeek/智谱 GLM/自定义，`AiProvider` 内置 baseUrl、模型列表与估算刊例价）+ apiKey/model + 自定义模型单价 + 翻译开关；首启从旧 `translation_prefs` 一次性迁移（baseUrl 自动补 `/v1`）。`AiUsageStore` 与其共用 `ai_prefs`：`usage_json` 按「模型 × 月」聚合 token 用量，设置页「用量与费用」区块按刊例价估算费用。
- Room（`AppDatabase`，`aihot.db`，version 1，`fallbackToDestructiveMigration`）：仅浏览历史（`BrowseHistoryEntity/Dao/Repository`）。
- HN 列表缓存与翻译缓存为 `cacheDir` 下的 JSON 文件。

### 主题（ui/theme/）

两层设计，遵循 "Synthetic Intelligence News" 设计系统（品牌双色：Future Blue `#003EC7` + Intelligence Purple `#6B38D4`，蓝→紫渐变只用于 AI 特性）：

1. MD3 规范层（`Color.kt`/`Type.kt`/`Shape.kt`/`Theme.kt`）：Light/Dark 双色板，固定品牌色不跟随 dynamic color。
2. 语义层：`AppText.xxx`（9 个字号档）、`AppAlpha.xxx`（透明度），组件统一引用。

字体：Inter（SIL OFL 1.1，本地 4 字重 `res/font/inter_*.ttf`）；设置页可切 System/Serif/Monospace，经 `AIHotTheme(fontFamily=)` + `Typography.withFontFamily()` 全量替换。动画规范集中在 `ui/anim/Motion.kt`：只用 tween + MD3 emphasized 缓动，不用 spring/scale。

## 编码约定

- **注释用中文，代码/变量名用英文**（与存量代码一致）。
- 字号一律 `AppText.xxx`、透明度一律 `AppAlpha.xxx`，不散落 `.sp` / `.alpha` 字面量。
- 协程 + Flow：`StateFlow` 驱动 UI，`collectAsStateWithLifecycle` 订阅；网络在 Repository 内切 `Dispatchers.IO`；并发去重用 `Mutex.withLock` 套路。
- release 开启 R8 + shrinkResources；`com.example.aihot.data.**` 全部保留（`app/proguard-rules.pro`），新增需反射/序列化保留的类时同步补规则。
- 改动导航、数据源模式、流水线行为时，同步更新本文件与相关文档注释。

## 安全注意事项

- **签名密钥绝不入库**：`*.jks`、`*.keystore`、`keystore.properties` 均已 gitignore；本地 release 构建需在仓库根放 `keystore.properties`（storeFile/storePassword/keyAlias/keyPassword，storeFile 按工程根相对路径解析）和 `app/aihot-release.jks`。CI 从 secrets 还原。
- 翻译 API key 由用户自填、存 App 私有目录（DataStore），不进 APK、不进日志；不要把它打印或上报。
- `GITCODE_TOKEN` 仅经环境变量注入，用于流水线推送；不要写入任何入库文件。
- 网络策略：`network_security_config.xml` 全域名禁明文流量，仅系统 CA——不要为调试放开 cleartext。
- 数据流水线的 AI 配置走 CI secrets / 本地 export，代码里不得硬编码任何 key。
