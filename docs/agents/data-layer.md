# 数据层（data/）

> 面向 AI 编码代理的领域约定，从根 `AGENTS.md` 拆出。动数据源、Repository、小组件、通知前先读本文。

## 取数模式

- **取数模式恒定归档**（设置页「数据源」入口已移除）：5 个稳定源（HackerNews / GitHub Trending / stormzhang AI / HuggingFace Papers / The Rundown AI）固定走 ARCHIVE；底层 `SourceMode` 枚举与 LIVE 分支保留待恢复（`SettingsStore` 强制返回 `ARCHIVE`）。
- **Product Hunt 只归档**（Developer Token 是服务端 secret 不进 APK，两种模式都走归档）。
- 归档走 `ArchiveHttpClient`（gitcode **REST API raw 端点**，**不要**用 raw 直链——背后是 WAF 会 403）。
- **归档断网兜底**：index / 快照 / 根级文件网络成功后 write-through 落盘（`ArchiveDiskCache`，`cacheDir/archives/`，64MB 上限最旧淘汰 + 读取 7 天时效，`App.onCreate` init 保证小组件等无 Activity 入口也能落盘）；**仅传输层失败（IOException：连不上/DNS/读超时）读盘兜底**，命中且未过期则置 `ArchiveHttpClient.offlineMode` 并返回旧数据（Repository/VM 签名零改动），UI 层每次离线事件弹一次性 Snackbar；**HTTP 层错误（4xx/5xx/空响应）不兜底**，直接走 Error 态——服务端故障不得伪装成「离线」拿旧数据顶上。`fetchLatestOverview(networkOnly = true)` 为网络探测语义（跳过内存缓存与盘兜底、失败即抛），仅供每日通知 Worker 与冷启动弹窗用；未命中才走错误态。
- **归档失败（盘上也无兜底）直接显示 Error 态，不回退实时**。

## 三个内容 Tab 的数据语义

**摘要 / 总览 / 趋势三个内容 Tab 均纯读归档、不在 App 端调 AI**，各自只做字段反序列化，字段缺失走失败态 / NoData 空态：

- **摘要 Tab** 直接读归档快照顶层 `ai_summary_v2` 字段（JSON 数组，每项含 `title`+`desc`+`url`，流水线预生成；`url` 由流水线按 AI 返回的条目编号回填，空串 = 该条只读不可点），兼容回退旧纯文本 `ai_summary`（历史快照），两者都缺失即失败态。v2 条目 `url` 非空时整行可点，经 `openUrl` 直达内置 WebView。
- **总览 Tab** 直接读 `index.json` 顶层 `latest_overview` 字段（流水线 `scripts/overview_summary.py` 预生成的跨源综合分析，含 `digest` 今日综述 + Top10 items + breaking 标记；`digest` 可能为空串，空串不渲染）。App 端 `OverviewRepository` 只做反序列化，不再有端侧 AI 调用 / 缓存 / ConfigMissing 引导态。首屏 digest Hero = BrandGradient 通栏（「今日综述」label + digest 正文 + 数据截至 caption；digest 空串退化为纯文本时效行），Top10 无头条特殊位统一平铺。
- **历史总览**（「更多 → 历史总览」两级页）：`OverviewRepository.loadDigestOn(date)` 经根级独立索引文件 `overview_history.json` 按日期寻址，拉 `overview/<date>/<HH-MM>-data.json` 归档文件（复用 `fetchSnapshot("overview", relPath)` 路径缓存）反序列化为 `OverviewDigest`——与总览 Tab 同构渲染（共享 `OverviewContent`，仅差二级页顶栏返回/无下拉刷新/底部不预留底栏）。索引仅保留最近 90 天；索引文件由流水线无条件恒写，404/拉取失败走错误态（缺失属异常），文件为空才走空态。VM 按日期隔离实例（`OverviewArchiveViewModel`，key = `overview-date-$date`）。
- **根级独立文件**：`history.json`（历史摘要）与 `overview_history.json`（历史总览）已拆出 `index.json`，App 端经 `ArchiveHttpClient` 的 `fetchHistory()` / `fetchOverviewHistory()` 按需拉取（各自 2 分钟缓存 + 并发去重，与 index 同节奏互不影响）——未进历史页的 tab 不下载这两个文件；趋势 `trends.json` 同理（`fetchLatestTrends`，带 force 语义供趋势 Tab 下拉刷新）。
- **趋势 Tab** 直接读数据仓库根级独立文件 `trends.json`（流水线 `scripts/trend_keywords.py` 在 push 阶段对近 14 天快照做的热词词频统计——**统计为主 + 每批至多一次流水线侧 AI 精修**（合并同话题/剔泛词/规范 display，失败零降级回退统计榜；与端侧 AI 无关）：窗口期命中数 + 每日序列 + 涨跌 + 排名变化 + ≤3 条代表条目，排序为动量加权分；整文件每次批次覆盖、成功才写，无继承语义——文件暂缺（生成失败批次）或无热词 → NoData 空态，下次批次自愈）。App 端 `TrendsRepository` 只做反序列化（display 可能是 AI 精修命名，含中文，解析逻辑无感知）。UI 为热词榜平铺（RankBadge + 其下排名变化小字 + 命中统计 + Canvas 手绘 sparkline + 涨跌箭头），点击词条展开代表条目经 `openUrl` 进内置 WebView。
- **趋势词云**（根级独立文件 `trends_cloud.json`，趋势 Tab caption 行「词云 ›」进入的二级页）：与 `trends.json` 同批生成但互不依赖——纯统计 top ~60 候选词（同口径门槛与动量分值，护栏词优先；只含 `term`/`display`/`total` 轻量字段，不带代表条目，不进按日归档；AI 精修只作用于 `keywords`，词云恒为统计产出）。App 端 `TrendsRepository.loadCloud` 经 `ArchiveHttpClient.fetchTrendsCloud`（独立 2 分钟缓存，未进词云页不下载）只做反序列化；文件缺失（成功才写语义）或 `words` 无效 → NoData 空态（下次批次自愈），**不回退热词榜数据**。词云页 `TrendsCloudScreen` 自持专用 VM（UiState 范式，无下拉刷新），布局支持螺旋散布 / 圆形气泡（词入泡，半径 ∝ √权重且不小于文字所需，贪心正切链堆成圆簇，零丢词，顶栏图标按钮循环切换，瞬态偏好）两种模式，词条不响应点击（无代表条目，阅读出口仍在榜单行）。
- **趋势历史归档**：流水线每批同步落 `trends/<date>/<HH-MM>-data.json` 按日归档并维护根级索引 `trends_history.json`（90 天指针，与 `overview_history.json` 同构）。排名变化字段 `rankChange` / `isNewEntry`（较昨日最后一期）由流水线依据归档计算，随 `trends.json` 下发；无历史基准时字段缺失，App 解析为 `rankChange = null` 不显示标记（旧格式天然兼容）。**历史热词**（「更多 → 历史热词」两级页）：`TrendsRepository.availableDates / loadDigestOn` 经 `fetchTrendsHistory()` 按日期寻址，拉 `trends/<date>/<HH-MM>-data.json` 归档文件（复用 `fetchSnapshot` 路径缓存，`arrayField = "keywords"`）反序列化为 `TrendsDigest`——与趋势 Tab 同构渲染（共享 `TrendsContent`，仅差二级页顶栏返回/无下拉刷新/底部不预留底栏）。VM 按日期隔离实例（`TrendsArchiveViewModel`，key = `trends-date-$date`），全套路式与「历史总览」一致。

## 桌面小组件「今日热点」（widget/ 包，Glance）

- 只读归档 `latest_overview`（与总览同源同语义，与 SourceMode 无关）。缓存为 App 级 SharedPreferences `hot_now_widget`（多小组件实例共享，刻意不用 Glance per-id 状态）。
- 刷新三路：系统 30min `updatePeriodMillis` / 头部按钮 `RefreshHotNowAction` / App 总览刷新成功联动（`HotNowWidgetUpdater.refreshFromApp`，同进程命中 ArchiveHttpClient 2 分钟缓存，零额外网络）。拉取失败保留旧数据不清空（小组件无错误交互入口）。
- 配色直接取 `ui/theme/Color.kt` 设计令牌组 day/night `ColorProvider`（App 迷你版，不用壁纸动态色）；条目只展示排名 + 标题（+突发胶囊），来源/互动指标刻意不上小组件；Glance 1.1.1 不支持 res/font 自定义字体，层级靠字号 + 字重。
- 视觉对齐 App 内「今日热点」卡（`HotTopicsSection`）：头部品牌渐变 Hero（`widget_header_gradient` day/night drawable，BrandGradient 同源）= 标题行 + 「今日综述」digest 正文（≤2 行截断，空串不渲染，与总览 Tab digest Hero 同构；digest 随 items 一同存入 `hot_now_widget` SharedPreferences 的 `digest` 键）+ 卡面色描边背景（`widget_bg` day/night drawable，surfaceContainerLow + 1dp outlineVariant 描边）；条目为迷你排名徽章（18dp，分档同 App RankBadge：1 名 tertiary 实心 / 2-3 tertiaryContainer / 其余 surfaceContainerHigh）+ Bold 标题，行间发丝线。改徽章分档/渐变色须与 `ui/components/RankBadge.kt`、`theme/Color.kt` 保持同步。

## 每日更新本地通知（notify/ 包）

`DailyUpdateNotifier.kt` 单文件三角色（Notifier / Scheduler / Worker）：

- WorkManager one-shot 自查链轮询归档 `latest_overview.generatedAt` 指纹（不用 `updated_at_ms`，总览失败继承旧值时不误报），检查时刻表对齐流水线批次（北京时间 08:40 / 16:10 / 18:40 = 各批次 +40min 余量，**改流水线批次时间必须同步改 `CHECK_SLOTS`**；批次 08:00 / 18:00 由仓库外机器调度，仓库 workflow 仅承载 15:30 批——勿因 workflow 只有一个 cron 误判 CHECK_SLOTS 为 bug）；档内未就绪 40min 补查最多 2 次。
- 每天（北京时间）至多 1 条（当天首批时发，之后批次静默），正文 digest 始终中文。设置页开关默认关，存 `display_prefs` 的 `daily_notify` 键；API 33+ 打开时经设置页请求 `POST_NOTIFICATIONS` 运行时权限。
- Worker 除「开关已关」外所有路径先续链再干活（失败不断链），刻意不用 `Result.retry()`；任务持久化跨重启，无 boot receiver。
- **冷启动新数据弹窗随同一开关**（`MainActivity`）：开关开启时每次冷启动比对指纹（`last_notified_overview_at`），落后于最新 `generatedAt` 即弹全局 AlertDialog（「查看」直达总览根页 /「忽略」），确认与忽略都写回指纹——与通知互补：每天至多 1 条提醒，通知或弹窗任一形式先触达即静默。取数走 `ArchiveHttpClient.fetchLatestOverview(networkOnly = true)`（必须真实打网络：断网/服务端故障不弹窗、不读盘兜底；联网冷启动相比共享 2 分钟缓存至多多一次 index 请求）。

## 源标识 / 元数据单点定义

- 8 源（HackerNews / GitHub Trending / OpenAI×Anthropic / HuggingFace Papers / Product Hunt / The Rundown AI / AIHot 精选 / stormzhang AI）的 **key 字面量集中于 `data/SourceKeys.kt`**（全 App 唯一真相源，归档 Repository / 摘要 Repository / UI 跳转分发 / 强调色 when 分支一律引用其常量，不写裸字符串，杜绝 key 漂移静默断裂）。
- **UI 元数据**（icon / 品牌色 / 标题 / 副标题 / URL）集中于 `ui/more/SourceMeta.kt` 的 `sourceMeta(key)`，`DEFAULT_SOURCE_ORDER` 为默认顺序。信息源页 / 摘要 Tab / 关于页三处都从 `sourceMeta(key)` 派生，不再各自硬编码。
- 用户在「信息源」页长按拖拽自定义顺序（reorderable 库），持久化于 `display_prefs` 的 `source_order` 键；**摘要 Tab 跟随用户顺序**（`SummaryViewModel.sourceKeys` 读 `SettingsStore.sourceOrderFlow`），**关于页固定默认顺序**。

## 其他

- 端侧 AI（翻译 / 系统选中译）统一经 `AiChatClient` 访问「设置 → AI 服务」里的用户配置。
- **本地搜索索引**：`SearchIndexRepository`（单例 object，`App.onCreate` init）在成功取数后回填 Room `search_items` 表（url 主键 = 行点击时传给 `openUrl` 的同一 URL；source 存 `SourceKeys` key 或条目自带源名，UI 经 `sourceMeta` 转本地化标题），90 天抽样清理。回填点三类：① 7 个归档源列表 Repository；② `SummaryRepository.summarize/summarizeOn`（摘要 Tab 与历史摘要——日常阅读主路径，v2 条目带原文 URL，是索引覆盖 8 源的主入口）；③ `OverviewRepository.parseDigest`（总览 Top10/breaking，冷启动即拉取）。**刻意不回填** `NewsRepository.fetchItems`（aihot 实时 API 精选流——第三方数据、permalink 指向其站内阅读页，与「本地搜索 = 本 App 归档数据」定位不符；开发期曾回填过，已由 Room v4 迁移清理）。由独立的「本地搜索」页消费（`Page.LocalSearch`，总览顶栏入口，详见 persistence.md），与联网搜索页互不相干。
- **我的关注**（`Page.Follows`，趋势页顶栏进入）：`FollowsRepository` 聚合当日总览 Top10 + 8 源结构化摘要作为关键词过滤语料——同 URL 去重保留总览版本（信息更全），摘要条目按用户自定义源顺序排列；纯端上 `FollowMatcher` 过滤（关键词存 `display_prefs` 的 `followed_keywords`，≤20 个，增删只重算不发请求；语料复用 `ArchiveHttpClient` 既有 index/快照缓存，摘要/总览 Tab 打开过即预热）；总览 + 8 源并行拉取，单源失败记入 `missingSources` 由页脚标注、全部失败才走 Error 态；`SummaryRepository` 的本地搜索索引回填副作用在此同样生效。
- **更新检查与应用内直装**：`UpdateChecker`（关于页手动触发）查 GitHub `releases/latest` 的 `tag_name` 与本地 versionName 逐段比较，并从 `assets` 解析 APK 直链（release.yml 固定挂单个 `app-release.apk`，按「第一个 `.apk` 资产」解析防改名脆断）；任何失败静默视为已是最新（匿名限频 60/h，仅手动无压力）。命中后经 `UpdateDownloader` 在弹窗内直装：OkHttp（`base` 派生、**清零 callTimeout**，否则 30s 总超时掐断下载）流式写 `cacheDir/updates/`，进度回调驱动弹窗进度条；完成后 FileProvider（authority `${applicationId}.fileprovider`，路径白名单 `res/xml/file_paths.xml`）以 `content://` 拉起系统安装器。Android 8+ 需用户在系统设置为本 App 开「安装未知应用」（Manifest 声明 `REQUEST_INSTALL_PACKAGES`，未授权时跳设置页）；无 APK 资产或下载失败兜底回 Release 网页。
- 数据模型：`NewsItem` / `HackerNewsStory` 用 `@Parcelize`。
