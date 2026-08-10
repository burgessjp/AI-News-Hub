# AGENTS.md

面向 AI 编码代理的项目约定。只写「猜不到 / 易踩坑 / 反默认」的内容，其余请直接读对应文件。

**AI News Hub** —— Android AI 资讯聚合客户端。Kotlin + Jetpack Compose + Material3，单模块（`:app`），包名 / namespace / applicationId 均为 `com.peng.ainewshub`。

## 构建

```bash
./gradlew assembleDebug        # 日常验证手段（项目无单测、无 lint）
./gradlew installDebug         # 安装到设备
./gradlew assembleRelease      # 需签名配置，见「安全红线」
```

**没有单元测试、没有 lint**（`app/src` 下只有 `main`）。改动后至少跑 `assembleDebug` 确认编译通过，再真机手测。工具链版本一律以 `gradle/libs.versions.toml` 为准，不在此重复。

## 编码约定（与默认不同，务必遵守）

- **注释用中文，代码/变量名用英文**（与存量代码一致）。
- **不引入 Retrofit / Gson / Moshi**：网络一律 `OkHttpClient`，JSON 用内置 `org.json`，HTML 抓取用 jsoup。`OkHttpClient` 统一经 `data/HttpClients.kt` 的共享 base 派生（`base` 或 `base.newBuilder()`），不各自 `OkHttpClient.Builder().build()`。
- **不用 Navigation Compose**（见下「导航」），**无 DI 框架**：Repository 在 ViewModel / Composable 内直接构造。
- 字号一律 `AppText.xxx`、透明度一律 `AppAlpha.xxx`、圆角一律 `MaterialTheme.shapes` 或 `CircleShape`、颜色只走 `colorScheme`——不散落 `.sp`/`.alpha`/hex 字面量（源品牌色集中在 `ui/more/SourceBrandColors.kt` 是唯一例外）。列表排名/统计/章节条/骨架屏统一复用 `ui/components/` 现有组件，不新建私有拷贝。
- **UI 文案一律走 string 资源，不写硬编码字面量**：`values/` 为中文全集（默认 locale），`values-en/` 为英文，新 feature 必须同步双语。Composable 用 `stringResource()`；VM / 小组件等非 Composable 场景用 `context.localized().getString()`（见下「国际化」）。术语表与 `common_*`/`error_*`/`time_*`/`date_fmt_*` 共用条集中在 `values/strings_common.xml`（头注释含术语表），复用不新造。
- 协程 + Flow：`StateFlow` 驱动 UI，`collectAsStateWithLifecycle` 订阅；网络在 Repository 内切 `Dispatchers.IO`；并发去重用 `Mutex.withLock`。
- release 开启 R8 + shrinkResources；`com.peng.ainewshub.data.**` 已全部保留（`app/proguard-rules.pro`），新增需反射/序列化保留的类时同步补规则。

## 国际化（i18n）

- `values/` 为中文全集（默认 locale），`values-en/` 为英文；两档语言，资源按 feature 分文件（`strings_common.xml` 为共用条 + 术语表，其余 `strings_<feature>.xml`）。
- **应用内语言切换**：设置页「语言」三选项（跟随系统 / 简体中文 / English），持久化于 `display_prefs` 的 `language` 键（`AppLanguage` 枚举按 name 存取）。机制单点为 `ui/i18n/AppLocale.kt`：两个 Activity `attachBaseContext` 包裹 + 切换后 `recreate()` 生效；「跟随系统」时含 Android 13+ 系统 per-app locale（manifest `android:localeConfig`）。语言选择内存缓存，冷启动仅 attachBaseContext 时阻塞读一次 DataStore。
- VM 错误文案经 `toUiError(getApplication<Application>().localized())` 取词；**已知取舍**：切换语言经 recreate 生效但 ViewModel 存活，切换瞬间已处于 Error/翻译错误态的文案保持旧语言，重试/刷新即更新。
- 桌面小组件无 attachBaseContext，取词用 `AppLocale.wrap(context)` 后的 context。
- **流水线内容（AI 摘要 / 总览 / 各源正文）始终为中文**，不随界面语言变化——英文版仅覆盖 App 界面文案。

## 导航（MainActivity.kt，自实现多栈）

- 3 个根 tab（总览/摘要/更多）+ 每 tab 独立二级页栈；`Page` 是 sealed interface，经 `toBundle()`/`pageFromBundle()` + 自定义 `Saver` 挂 `rememberSaveable`，进程被杀可恢复。切 tab 保留各自栈。
- ⚠️ **新增二级页必须同步加三处**：`Page` 子类、`toBundle`/`pageFromBundle` 分支、`PageView` 分支。
- ⚠️ **含列表/页码的页，`LazyListState`/`PagerState` 一律在 `AiNewsHubApp` 层持有并下传**——`AnimatedContent` 换页即销毁屏内 `remember`/`rememberSaveable`，屏内自持会丢滚动位置。不要在屏内 `rememberLazyListState()`。摘要 Tab 为「顶部提示行 + 源名 chips 导航 + 内容 Pager」结构（与历史摘要按日期页同构），`summaryPagerState` 同理上提。
- ⚠️ 含 WebView 的页转场 override 为 `FADE`（横向位移会让 AndroidView 撕裂），其余约定见 `ui/anim/Motion.kt`。
- `openUrl` 是打开网页的**唯一入口**（统一记录浏览历史 + push `Page.Web`），全 App 链接都走内置 WebView，不走外部浏览器。
- 外部深链两个入口：`EXTRA_OPEN_SETTINGS`（系统选中译「去设置」）与 `EXTRA_OPEN_URL`/`_TITLE`/`_SOURCE`（桌面小组件点击直达内置 WebView）。`MainActivity` 为 `singleTask`，冷/热启动分别在 `onCreate`/`onNewIntent` 解析，统一经 `openUrl` 消费；旋转重建不重复 push（与 `EXTRA_OPEN_SETTINGS` 同款 `savedInstanceState == null` 守卫）。

## 数据层（data/）

- **取数模式恒定归档**（设置页「数据源」入口已移除）：5 个稳定源（HackerNews / GitHub Trending / stormzhang AI / HuggingFace Papers / The Rundown AI）固定走 ARCHIVE；底层 `SourceMode` 枚举与 LIVE 分支保留待恢复（`SettingsStore` 强制返回 `ARCHIVE`）。
  - **Product Hunt 只归档**（Developer Token 是服务端 secret 不进 APK，两种模式都走归档）。
  - 归档走 `ArchiveHttpClient`（gitcode **REST API raw 端点**，**不要**用 raw 直链——背后是 WAF 会 403）。
  - **归档失败直接显示 Error 态，不回退实时**。
- **摘要 Tab 不在 App 端生成 AI 摘要**，直接读归档快照顶层 `ai_summary_v2` 字段（JSON 数组，每项含 `title`+`desc`+`url`，流水线预生成；`url` 由流水线按 AI 返回的条目编号回填，空串 = 该条只读不可点），兼容回退旧纯文本 `ai_summary`（历史快照），两者都缺失即失败态。v2 条目 `url` 非空时整行可点，经 `openUrl` 直达内置 WebView（摘要 Tab 与历史摘要页同构接入）。
- **总览 Tab 同样不在 App 端调 AI**，直接读 `index.json` 顶层 `latest_overview` 字段（流水线 `scripts/overview_summary.py` 预生成的跨源综合分析，含 `digest` 今日综述 + Top10 items + breaking 标记；`digest` 可能为空串，空串不渲染）。App 端 `OverviewRepository` 只做反序列化，不再有端侧 AI 调用 / 缓存 / ConfigMissing 引导态。字段缺失走 NoData 空态。首屏 digest Hero = BrandGradient 通栏（「今日综述」label + digest 正文 + 数据截至 caption；digest 空串退化为纯文本时效行），Top10 无头条特殊位统一平铺。
- **桌面小组件「今日热点」**（`widget/` 包，Glance）：只读归档 `latest_overview`（与总览同源同语义，与 SourceMode 无关）。缓存为 App 级 SharedPreferences `hot_now_widget`（多小组件实例共享，刻意不用 Glance per-id 状态）。刷新三路：系统 30min `updatePeriodMillis` / 头部按钮 `RefreshHotNowAction` / App 总览刷新成功联动（`HotNowWidgetUpdater.refreshFromApp`，同进程命中 ArchiveHttpClient 2 分钟缓存，零额外网络）。拉取失败保留旧数据不清空（小组件无错误交互入口）。配色直接取 `ui/theme/Color.kt` 设计令牌组 day/night `ColorProvider`（App 迷你版，不用壁纸动态色）；条目只展示排名 + 标题（+突发胶囊），来源/互动指标刻意不上小组件；Glance 1.1.1 不支持 res/font 自定义字体，层级靠字号 + 字重。
- **源标识 / 元数据单点定义**：8 源（HackerNews / GitHub Trending / OpenAI×Anthropic / HuggingFace Papers / Product Hunt / The Rundown AI / AIHot 精选 / stormzhang AI）的 **key 字面量集中于 `data/SourceKeys.kt`**（全 App 唯一真相源，归档 Repository / 摘要 Repository / UI 跳转分发 / 强调色 when 分支一律引用其常量，不写裸字符串，杜绝 key 漂移静默断裂）；**UI 元数据**（icon / 品牌色 / 标题 / 副标题 / URL）集中于 `ui/more/SourceMeta.kt` 的 `sourceMeta(key)`，`DEFAULT_SOURCE_ORDER` 为默认顺序。信息源页 / 摘要 Tab / 关于页三处都从 `sourceMeta(key)` 派生，不再各自硬编码。
  - 用户在「信息源」页长按拖拽自定义顺序（reorderable 库），持久化于 `display_prefs` 的 `source_order` 键；**摘要 Tab 跟随用户顺序**（`SummaryViewModel.sourceKeys` 读 `SettingsStore.sourceOrderFlow`），**关于页固定默认顺序**。
- 端侧 AI（翻译 / 系统选中译）统一经 `AiChatClient` 访问「设置 → AI 服务」里的用户配置。
- 数据模型：`NewsItem` / `HackerNewsStory` 用 `@Parcelize`。

## 持久化

- DataStore：`display_prefs`（主题 / 动态取色 / 字体族 / 字号档位 / 源模式 / 应用内语言 `language` / 搜索历史 / 信息源顺序 `source_order`）、`ai_prefs`（全局 AI 服务配置 + 按「模型 × 月」聚合的 token 用量）。
- Room（`ainewshub.db`，version 1，`fallbackToDestructiveMigration`）：仅浏览历史。
- HN 列表缓存、翻译缓存为 `cacheDir` 下 JSON 文件。
- 桌面小组件缓存为 SharedPreferences `hot_now_widget`（今日热点列表 JSON + 时间戳，KB 级，`CacheManager` 不涉及）。

## 数据流水线（scripts/）

`pipeline.sh` 是唯一编排入口（CI 与本地都调它）。缺这 4 个环境变量之一直接 `exit 1`：

```
AI_NEWS_HUB_AI_BASE_URL / AI_NEWS_HUB_AI_MODEL / AI_NEWS_HUB_AI_API_KEY / GITCODE_TOKEN
```

- 抓 8 源（7 个第三方站点 + `aihot.virxact.com` 精选 `/items?mode=selected&take=20`）→ 各源 AI 摘要（`ai_summary.py`，写入快照顶层 `ai_summary_v2`，每项含 `title`+`desc`+`url`，url 由 AI 返回的条目编号回填）+ 跨源总览（`overview_summary.py`，写入 `index.json` 顶层 `latest_overview`，含 `digest` 综述 + items）→ 推送到 gitcode 数据仓库 `peng1818/AI-News-Hub-Data` 的 `news-hub-data` 分支。
- 单源失败跳过且 `index.json` latest 指针从上一次继承（客户端永远拿到有效数据）；总览 AI 生成失败时 `latest_overview` 同样继承上一次。≥1 源成功退出码即为 0。日期统一北京时间。
- 数据格式详见 `docs/news-hub-data-usage.md`；各脚本行为见脚本头注释。

## CI/CD

`.github/workflows/`：`build.yml`（PR 跑 `assembleDebug`）/ `release.yml`（`v*` tag 发版，从 secrets 还原 keystore，versionName/versionCode 从 tag 注入）/ `fetch-data.yml`（每日定时跑数据流水线）。

## 提交规范

- **Conventional Commits 风格**：`type(scope): subject`，type 用 `feat`/`fix`/`refactor`/`docs`/`chore` 等，scope 可选（如 `feat(build):`、`refactor(settings):`）。
- **commit message 一律用英文**（subject + body 都用英文）。
- 仅为 AI 代写时的强约束：人类可读的 message 必须英文，**但代码注释、PR 描述、文档仍按各自约定**（注释中文、文档双语）。

## 安全红线

- **签名密钥绝不入库**：`*.jks`、`*.keystore`、`keystore.properties` 均已 gitignore；本地 release 需自行放 `keystore.properties` + `app/release.jks`，CI 从 secrets 还原。
- **`GITCODE_TOKEN` 与流水线 AI 配置仅经环境变量注入**，代码里不得硬编码任何 key。
- `network_security_config.xml` 全域名禁明文流量、仅系统 CA——不要为调试放开 cleartext。
- AI / 翻译 key 由用户自填、存 App 私有目录（DataStore），不进 APK、不进日志、不上报。

## 维护

改动导航机制、数据源模式、流水线行为时，同步更新本文件与相关代码文档注释。
