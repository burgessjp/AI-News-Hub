# 数据层（data/）

> 面向 AI 编码代理的领域约定，从根 `AGENTS.md` 拆出。动数据源、Repository、小组件、通知前先读本文。

## 取数模式

- **取数模式恒定归档**（设置页「数据源」入口已移除）：5 个稳定源（HackerNews / GitHub Trending / stormzhang AI / HuggingFace Papers / The Rundown AI）固定走 ARCHIVE；底层 `SourceMode` 枚举与 LIVE 分支保留待恢复（`SettingsStore` 强制返回 `ARCHIVE`）。
- **Product Hunt 只归档**（Developer Token 是服务端 secret 不进 APK，两种模式都走归档）。
- 归档走 `ArchiveHttpClient`（gitcode **REST API raw 端点**，**不要**用 raw 直链——背后是 WAF 会 403）。
- **归档失败直接显示 Error 态，不回退实时**。

## 三个内容 Tab 的数据语义

**摘要 / 总览 / 趋势三个内容 Tab 均纯读归档、不在 App 端调 AI**，各自只做字段反序列化，字段缺失走失败态 / NoData 空态：

- **摘要 Tab** 直接读归档快照顶层 `ai_summary_v2` 字段（JSON 数组，每项含 `title`+`desc`+`url`，流水线预生成；`url` 由流水线按 AI 返回的条目编号回填，空串 = 该条只读不可点），兼容回退旧纯文本 `ai_summary`（历史快照），两者都缺失即失败态。v2 条目 `url` 非空时整行可点，经 `openUrl` 直达内置 WebView。
- **总览 Tab** 直接读 `index.json` 顶层 `latest_overview` 字段（流水线 `scripts/overview_summary.py` 预生成的跨源综合分析，含 `digest` 今日综述 + Top10 items + breaking 标记；`digest` 可能为空串，空串不渲染）。App 端 `OverviewRepository` 只做反序列化，不再有端侧 AI 调用 / 缓存 / ConfigMissing 引导态。首屏 digest Hero = BrandGradient 通栏（「今日综述」label + digest 正文 + 数据截至 caption；digest 空串退化为纯文本时效行），Top10 无头条特殊位统一平铺。
- **历史总览**（「更多 → 历史总览」两级页）：`OverviewRepository.loadDigestOn(date)` 经根级独立索引文件 `overview_history.json` 按日期寻址，拉 `overview/<date>/<HH-MM>-data.json` 归档文件（复用 `fetchSnapshot("overview", relPath)` 路径缓存）反序列化为 `OverviewDigest`——与总览 Tab 同构渲染（共享 `OverviewContent`，仅差二级页顶栏返回/无下拉刷新/底部不预留底栏）。索引仅保留最近 90 天；索引文件由流水线无条件恒写，404/拉取失败走错误态（缺失属异常），文件为空才走空态。VM 按日期隔离实例（`OverviewArchiveViewModel`，key = `overview-date-$date`）。
- **根级独立文件**：`history.json`（历史摘要）与 `overview_history.json`（历史总览）已拆出 `index.json`，App 端经 `ArchiveHttpClient` 的 `fetchHistory()` / `fetchOverviewHistory()` 按需拉取（各自 2 分钟缓存 + 并发去重，与 index 同节奏互不影响）——未进历史页的 tab 不下载这两个文件；趋势 `trends.json` 同理（`fetchLatestTrends`，带 force 语义供趋势 Tab 下拉刷新）。
- **趋势 Tab** 直接读数据仓库根级独立文件 `trends.json`（流水线 `scripts/trend_keywords.py` 在 push 阶段对近 14 天快照做的**纯统计**热词词频分析，**不调 AI**、确定性结果：窗口期命中数 + 每日序列 + 涨跌 + 排名变化 + ≤3 条代表条目；整文件每次批次覆盖、成功才写，无继承语义——文件暂缺（生成失败批次）或无热词 → NoData 空态，下次批次自愈）。App 端 `TrendsRepository` 只做反序列化。UI 为热词榜平铺（RankBadge + 其下排名变化小字 + 命中统计 + Canvas 手绘 sparkline + 涨跌箭头），点击词条展开代表条目经 `openUrl` 进内置 WebView。
- **趋势历史归档**：流水线每批同步落 `trends/<date>/<HH-MM>-data.json` 按日归档并维护根级索引 `trends_history.json`（90 天指针，与 `overview_history.json` 同构）。排名变化字段 `rankChange` / `isNewEntry`（较昨日最后一期）由流水线依据归档计算，随 `trends.json` 下发；无历史基准时字段缺失，App 解析为 `rankChange = null` 不显示标记（旧格式天然兼容）。**App 暂不拉取 `trends_history.json`**，为后续热词历史浏览预留。

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
- **冷启动新数据弹窗随同一开关**（`MainActivity`）：开关开启时每次冷启动比对指纹（`last_notified_overview_at`），落后于最新 `generatedAt` 即弹全局 AlertDialog（「查看」直达总览根页 /「忽略」），确认与忽略都写回指纹——与通知互补：每天至多 1 条提醒，通知或弹窗任一形式先触达即静默。取数与总览 Tab 共享 `ArchiveHttpClient` 2 分钟缓存，冷启动零额外请求。

## 源标识 / 元数据单点定义

- 8 源（HackerNews / GitHub Trending / OpenAI×Anthropic / HuggingFace Papers / Product Hunt / The Rundown AI / AIHot 精选 / stormzhang AI）的 **key 字面量集中于 `data/SourceKeys.kt`**（全 App 唯一真相源，归档 Repository / 摘要 Repository / UI 跳转分发 / 强调色 when 分支一律引用其常量，不写裸字符串，杜绝 key 漂移静默断裂）。
- **UI 元数据**（icon / 品牌色 / 标题 / 副标题 / URL）集中于 `ui/more/SourceMeta.kt` 的 `sourceMeta(key)`，`DEFAULT_SOURCE_ORDER` 为默认顺序。信息源页 / 摘要 Tab / 关于页三处都从 `sourceMeta(key)` 派生，不再各自硬编码。
- 用户在「信息源」页长按拖拽自定义顺序（reorderable 库），持久化于 `display_prefs` 的 `source_order` 键；**摘要 Tab 跟随用户顺序**（`SummaryViewModel.sourceKeys` 读 `SettingsStore.sourceOrderFlow`），**关于页固定默认顺序**。

## 其他

- 端侧 AI（翻译 / 系统选中译）统一经 `AiChatClient` 访问「设置 → AI 服务」里的用户配置。
- 数据模型：`NewsItem` / `HackerNewsStory` 用 `@Parcelize`。
