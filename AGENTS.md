# AGENTS.md

面向 AI 编码代理的项目约定。只写「猜不到 / 易踩坑 / 反默认」的内容，其余请直接读对应文件。

**AIHot**（应用名 "AI News Hub"）—— Android AI 资讯聚合客户端。Kotlin + Jetpack Compose + Material3，单模块（`:app`），包名 / namespace / applicationId 均为 `com.example.aihot`。

## 构建

```bash
./gradlew assembleDebug        # 日常验证手段（项目无单测、无 lint）
./gradlew installDebug         # 安装到设备
./gradlew assembleRelease      # 需签名配置，见「安全红线」
```

**没有单元测试、没有 lint**（`app/src` 下只有 `main`）。改动后至少跑 `assembleDebug` 确认编译通过，再真机手测。工具链版本一律以 `gradle/libs.versions.toml` 为准，不在此重复。

## 编码约定（与默认不同，务必遵守）

- **注释用中文，代码/变量名用英文**（与存量代码一致）。
- **不引入 Retrofit / Gson / Moshi**：网络一律 `OkHttpClient`，JSON 用内置 `org.json`，HTML 抓取用 jsoup。
- **不用 Navigation Compose**（见下「导航」），**无 DI 框架**：Repository 在 ViewModel / Composable 内直接构造。
- 字号一律 `AppText.xxx`、透明度一律 `AppAlpha.xxx`、圆角一律 `MaterialTheme.shapes` 或 `CircleShape`、颜色只走 `colorScheme`——不散落 `.sp`/`.alpha`/hex 字面量（源品牌色集中在 `ui/more/SourceBrandColors.kt` 是唯一例外）。列表排名/统计/章节条/骨架屏统一复用 `ui/components/` 现有组件，不新建私有拷贝。
- 协程 + Flow：`StateFlow` 驱动 UI，`collectAsStateWithLifecycle` 订阅；网络在 Repository 内切 `Dispatchers.IO`；并发去重用 `Mutex.withLock`。
- release 开启 R8 + shrinkResources；`com.example.aihot.data.**` 已全部保留（`app/proguard-rules.pro`），新增需反射/序列化保留的类时同步补规则。

## 导航（MainActivity.kt，自实现多栈）

- 3 个根 tab（总览/摘要/更多）+ 每 tab 独立二级页栈；`Page` 是 sealed interface，经 `toBundle()`/`pageFromBundle()` + 自定义 `Saver` 挂 `rememberSaveable`，进程被杀可恢复。切 tab 保留各自栈。
- ⚠️ **新增二级页必须同步加三处**：`Page` 子类、`toBundle`/`pageFromBundle` 分支、`PageView` 分支。
- ⚠️ **含列表/页码的页，`LazyListState`/`PagerState` 一律在 `AIHotApp` 层持有并下传**——`AnimatedContent` 换页即销毁屏内 `remember`/`rememberSaveable`，屏内自持会丢滚动位置。不要在屏内 `rememberLazyListState()`。
- ⚠️ 含 WebView 的页转场 override 为 `FADE`（横向位移会让 AndroidView 撕裂），其余约定见 `ui/anim/Motion.kt`。
- `openUrl` 是打开网页的**唯一入口**（统一记录浏览历史 + push `Page.Web`），全 App 链接都走内置 WebView，不走外部浏览器。

## 数据层（data/）

- **双模式取数 `SourceMode`**（DataStore `display_prefs` 的 `source_mode`，默认 LIVE）：5 个稳定源（HackerNews / GitHub Trending / stormzhang AI / HuggingFace Papers / The Rundown AI）可切 LIVE / ARCHIVE。
  - **Product Hunt 只归档**（Developer Token 是服务端 secret 不进 APK，两种模式都走归档），**LinuxDo 只实时**。
  - 归档走 `ArchiveHttpClient`（gitcode **REST API raw 端点**，**不要**用 raw 直链——背后是 WAF 会 403）。
  - **归档失败直接显示 Error 态，不回退实时**。
- **摘要 Tab 不在 App 端生成 AI 摘要**，直接读归档快照顶层 `ai_summary` 字段（流水线预生成），缺失即失败态。
- 端侧 AI（总览综合分析 / 翻译 / 系统选中译）统一经 `AiChatClient` 访问「设置 → AI 服务」里的用户配置。
- 数据模型：`NewsItem` / `HackerNewsStory` 用 `@Parcelize`。

## 持久化

- DataStore：`display_prefs`（主题 / 动态取色 / 字体族 / 字号档位 / 源模式 / 搜索历史）、`ai_prefs`（全局 AI 服务配置 + 按「模型 × 月」聚合的 token 用量）。
- Room（`aihot.db`，version 1，`fallbackToDestructiveMigration`）：仅浏览历史。
- HN 列表缓存、翻译缓存为 `cacheDir` 下 JSON 文件。

## 数据流水线（scripts/）

`pipeline.sh` 是唯一编排入口（CI 与本地都调它）。缺这 4 个环境变量之一直接 `exit 1`：

```
AI_NEWS_HUB_AI_BASE_URL / AI_NEWS_HUB_AI_MODEL / AI_NEWS_HUB_AI_API_KEY / GITCODE_TOKEN
```

- 抓 8 源（7 个第三方站点 + `aihot.virxact.com` 精选 `/items?mode=selected&take=20`）→ AI 总结 → 推送到 gitcode 数据仓库 `peng1818/AI-News-Hub-Data` 的 `news-hub-data` 分支。
- 单源失败跳过且 `index.json` latest 指针从上一次继承（客户端永远拿到有效数据），≥1 源成功退出码即为 0。日期统一北京时间。
- LinuxDo 套 Cloudflare 强挑战，CI 单源失败属预期行为。
- 数据格式详见 `docs/news-hub-data-usage.md`；各脚本行为见脚本头注释。

## CI/CD

`.github/workflows/`：`build.yml`（PR 跑 `assembleDebug`）/ `release.yml`（`v*` tag 发版，从 secrets 还原 keystore）/ `fetch-data.yml`（每日定时跑数据流水线）。

## 安全红线

- **签名密钥绝不入库**：`*.jks`、`*.keystore`、`keystore.properties` 均已 gitignore；本地 release 需自行放 `keystore.properties` + `app/aihot-release.jks`，CI 从 secrets 还原。
- **`GITCODE_TOKEN` 与流水线 AI 配置仅经环境变量注入**，代码里不得硬编码任何 key。
- `network_security_config.xml` 全域名禁明文流量、仅系统 CA——不要为调试放开 cleartext。
- AI / 翻译 key 由用户自填、存 App 私有目录（DataStore），不进 APK、不进日志、不上报。

## 维护

改动导航机制、数据源模式、流水线行为时，同步更新本文件与相关代码文档注释。
