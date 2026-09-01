# 数据层（data/）

> 面向 AI 编码代理的领域约定，从根 `AGENTS.md` 拆出。动数据源、Repository、小组件、通知前先读本文。

## 取数模式

- **data 层包结构**（2026-08-29 重组）：`data/model`（模型 + 各源 Result）、`data/repo`（领域 Repository）、`data/db`（Room）、`data/net`（OkHttp/AI 客户端/更新检查）、`data/prefs`（SettingsStore/AiConfig/AiUsage + 持久化枚举 ThemeMode/FontChoice/FontScale/AppLanguage）、`data/source`（gitcode 归档体系 + SourceKeys/SourceFreshness）、`data/diagnostics`（本地诊断，见下文专节）；`data/` 根仅留跨切面（AppException/PipelineSchedule/CacheManager/JsonExt/HtmlUtil）。持久化枚举的展示映射（labelRes/fontFamily）在 `ui/more/SettingsScreen.kt`。
- **取数模式恒定归档**：5 个稳定源（HackerNews / GitHub Trending / stormzhang AI / HuggingFace Papers / The Rundown AI）固定读 gitcode 归档快照；原实时抓取路径（LIVE 双模式、jsoup 直抓、`SourceMode` 枚举）已于 2026-08-29 整体删除（决策记录见 docs/tech-roadmap.md），HTML 抓取全部由 `scripts/` 流水线承担。HN 评论树是唯一保留的实时路径（`HackerNewsRepository`，归档快照不含评论）。
- **Product Hunt 只归档**（Developer Token 是服务端 secret 不进 APK，两种模式都走归档）。
- 归档走 `ArchiveHttpClient`（gitcode **REST API raw 端点**，**不要**用 raw 直链——背后是 WAF 会 403）。
- 7 个归档源列表二级页 Repository 的 `fetch()` 走 index 2 分钟缓存、`forceRefresh()` 经 `fetchItemsList(force=true)` 绕过 TTL 强制重读 index（下拉刷新，与 4 个根 Tab 语义对齐）；快照本体按路径不可变，无 force 语义（index 强刷拿到新路径自然换缓存键）。
- **归档断网兜底**：index / 快照 / 根级文件网络成功后 write-through 落盘（`ArchiveDiskCache`，`cacheDir/archives/`，64MB 上限最旧淘汰 + 读取 7 天时效，`App.onCreate` init 保证小组件等无 Activity 入口也能落盘）；**仅传输层失败（IOException：连不上/DNS/读超时）读盘兜底**，命中且未过期则置 `ArchiveHttpClient.offlineMode` 并返回旧数据（Repository/VM 签名零改动），UI 层每次离线事件弹一次性顶部胶囊提示（`NoticePill`，5 秒、恢复联网提前消失；下拉刷新无新批次为 2.5 秒对勾胶囊）；**HTTP 层错误（4xx/5xx/空响应）不兜底**，直接走 Error 态——服务端故障不得伪装成「离线」拿旧数据顶上。`fetchLatestOverview(networkOnly = true)` 为网络探测语义（跳过内存缓存与盘兜底、失败即抛），仅供每日通知 Worker 与冷启动弹窗用；未命中才走错误态。
- **归档失败（盘上也无兜底）直接显示 Error 态。**

## 四个内容 Tab 的数据语义

**摘要 / 总览 / 关注 / 趋势四个内容 Tab 均纯读归档、不在 App 端调 AI**，各自只做字段反序列化，字段缺失走失败态 / NoData 空态：

- **摘要 Tab** 直接读归档快照顶层 `ai_summary_v2` 字段（JSON 数组，每项含 `title`+`desc`+`url`，流水线预生成；`url` 由流水线按 AI 返回的条目编号回填，空串 = 该条只读不可点），兼容回退旧纯文本 `ai_summary`（历史快照）；两者都缺失但快照 items 仍在（流水线 AI 调用失败、原始数据完好）→ `SummaryContent.Unavailable` 降级（UI 提示「本批未生成」并引导查看完整列表，重试无意义），连 items 都缺失才是失败态。v2 条目 `url` 非空时整行可点，经 `openUrl` 直达内置 WebView。
- **总览 Tab** 直接读 `index.json` 顶层 `latest_overview` 字段（流水线 `scripts/overview_summary.py` 预生成的跨源综合分析，含 `digest` 今日综述 + Top10 items + breaking 标记；`digest` 可能为空串，空串不渲染）。App 端 `OverviewRepository` 只做反序列化，不再有端侧 AI 调用 / 缓存 / ConfigMissing 引导态。首屏 digest Hero = BrandGradient 通栏（「今日综述」label + digest 正文（超 6 行默认折叠、溢出才出「展开/收起」）+ 「数据截至 · 下一批」时效 caption（下一批经 PipelineSchedule 计算，北京定义、设备本地显示）；digest 空串退化为纯文本时效行），Top10 无头条特殊位统一平铺。
- **关注 Tab（我的关注）**（根 tab `AppTab.Follows`，第 3 位；页面 `ui/follows/FollowsScreen.kt`）：`FollowsRepository` 聚合当日总览 Top10 + 8 源结构化摘要作为关键词过滤语料——同 URL 去重保留总览版本（信息更全），摘要条目按用户自定义源顺序排列；纯端上 `FollowMatcher` 过滤（关键词存 `display_prefs` 的 `followed_keywords`，≤20 个，增删只重算不发请求；语料复用 `ArchiveHttpClient` 既有 index/快照缓存，摘要/总览 Tab 打开过即预热）；总览 + 8 源并行拉取，单源失败记入 `missingSources` 由页脚标注、全部失败才走 Error 态；`SummaryRepository` 的本地搜索索引回填副作用在此同样生效。
- **语音速报预生成音频**（总览播报优先通道）：读 `index.json` 顶层 `latest_audio` 字段（流水线 `tts_broadcast.py` 用 Qwen3-TTS 按当日总览综述预合成的**单段 MP3（仅 digest）**描述；`generatedAt` 与 `latest_overview.generatedAt` 严格同值，App 侧据此判定新鲜度——描述缺失 / 批次不一致 → 回落系统 TTS + Toast 提示，预生成音频是增强项不是依赖项）。App 端 `BroadcastRepository` 只反序列化（经 `ArchiveHttpClient.fetchLatestAudio`，与 index 共享 2 分钟缓存与磁盘兜底）；总览播报**恒为单条 `TtsEntry` 整段**（text = digest.trim()，仅今日综述、与流水线合成侧一致）：清单可用挂 `audioUrl` 由 `TtsPlaybackService` 经 `ArchiveHttpClient.audioUrl(relPath)`（REST API raw 端点拼法，WAF 合规）交 `AudioEntryPlayer`（MediaPlayer）流式播放（真暂停/原地续播，播放失败回落系统 TTS 重读综述文本）；清单不可用的兜底同样走该单条整段系统 TTS 朗读（不分段队列，浮窗/通知均单条形态，单条队列下通知栏不显示上一条/下一条按钮）。
- **历史总览**（「更多 → 历史回顾」hub 总览段按日期进入）：`OverviewRepository.loadDigestOn(date)` 经根级独立索引文件 `overview_history.json` 按日期寻址，拉 `overview/<date>/<HH-MM>-data.json` 归档文件（复用 `fetchSnapshot("overview", relPath)` 路径缓存）反序列化为 `OverviewDigest`——与总览 Tab 同构渲染（共享 `OverviewContent`，仅差二级页顶栏返回/无下拉刷新/底部不预留底栏）。索引仅保留最近 90 天；索引文件由流水线无条件恒写，404/拉取失败走错误态（缺失属异常），文件为空才走空态。VM 按日期隔离实例（`OverviewArchiveViewModel`，key = `overview-date-$date`）。
- **根级独立文件**：`history.json`（历史摘要）与 `overview_history.json`（历史总览）已拆出 `index.json`，App 端经 `ArchiveHttpClient` 的 `fetchHistory()` / `fetchOverviewHistory()` 按需拉取（各自 2 分钟缓存 + 并发去重，与 index 同节奏互不影响）——未进历史页的 tab 不下载这两个文件；趋势 `trends.json` 同理（`fetchLatestTrends`，带 force 语义供趋势 Tab 下拉刷新）。
- **远程应用配置 `app_config.json`**（根级、**人工维护**，不经流水线写入）：当前仅承载批次时刻表 `batch_slots`（`["08:00","18:00"]` 北京时间，调整流水线批次时间时须同步改此文件）。App 端 `AppConfigSync`（`data/source/AppConfigSync.kt`）经 `ArchiveHttpClient.fetchAppConfig()` 拉取（独立 2 分钟缓存 + 并发去重 + 断网磁盘兜底），解析校验后应用到 `PipelineSchedule.applyBatchSlots`；404（尚未创建）/ 解析失败 / 断网一律静默保持当前表（内置默认或上次成功值）。触发点：每次进程启动（`AppConfigSyncHost` 进程闸门）+ 每日通知 Worker 运行前（覆盖无 UI 入口）。schema 向前兼容（未知字段忽略），新增配置键须同步本节与 `AppConfigSync` 注释。
- **趋势 Tab** 直接读数据仓库根级独立文件 `trends.json`（流水线 `scripts/trend_keywords.py` 在 push 阶段对近 14 天快照做的热词词频统计——**统计为主 + 每批至多一次流水线侧 AI 精修**（合并同话题/剔泛词/规范 display，失败零降级回退统计榜；与端侧 AI 无关）：窗口期命中数 + 每日序列 + 涨跌 + 排名变化 + ≤3 条代表条目，排序为动量加权分；整文件每次批次覆盖、成功才写，无继承语义——文件暂缺（生成失败批次）或无热词 → NoData 空态，下次批次自愈）。App 端 `TrendsRepository` 只做反序列化（display 可能是 AI 精修命名，含中文，解析逻辑无感知）。UI 为热词榜平铺（RankBadge + 其下排名变化小字 + 命中统计 + Canvas 手绘 sparkline + 涨跌箭头），点击词条展开代表条目经 `openUrl` 进内置 WebView；展开区尾部动作行（**仅根 tab**，历史热词日期页不渲染）：「+ 关注」一键把 display 写入关注词（`TrendsViewModel` 持 `SettingsStore`，结果经 `ui/FollowNotices.kt` 单例通道弹全局玻璃胶囊）+「查看全部命中」带词 push `Page.LocalSearch(initialQuery)` 查本地索引。
- **趋势词云**（根级独立文件 `trends_cloud.json`，趋势 Tab caption 行「词云 ›」进入的二级页）：与 `trends.json` 同批生成但互不依赖——纯统计 top ~60 候选词（同口径门槛与动量分值，护栏词优先；只含 `term`/`display`/`total` 轻量字段，不带代表条目，不进按日归档；AI 精修只作用于 `keywords`，词云恒为统计产出）。App 端 `TrendsRepository.loadCloud` 经 `ArchiveHttpClient.fetchTrendsCloud`（独立 2 分钟缓存，未进词云页不下载）只做反序列化；文件缺失（成功才写语义）或 `words` 无效 → NoData 空态（下次批次自愈），**不回退热词榜数据**。词云页 `TrendsCloudScreen` 自持专用 VM（UiState 范式，无下拉刷新），布局支持螺旋散布 / 圆形气泡（词入泡，半径 ∝ √权重且不小于文字所需，贪心正切链堆成圆簇，零丢词，顶栏图标按钮循环切换，瞬态偏好）两种模式，点击词条带词进本地搜索（`Page.LocalSearch(initialQuery)`，词云无代表条目，全量命中在索引里；命中判定螺旋用词 AABB/竖排交换宽高、气泡用圆心距离，空白点击不动作）。
- **趋势历史归档**：流水线每批同步落 `trends/<date>/<HH-MM>-data.json` 按日归档并维护根级索引 `trends_history.json`（90 天指针，与 `overview_history.json` 同构）。排名变化字段 `rankChange` / `isNewEntry`（较昨日最后一期）由流水线依据归档计算，随 `trends.json` 下发；无历史基准时字段缺失，App 解析为 `rankChange = null` 不显示标记（旧格式天然兼容）。**历史热词**（「更多 → 历史回顾」hub 热词段按日期进入）：`TrendsRepository.availableDates / loadDigestOn` 经 `fetchTrendsHistory()` 按日期寻址，拉 `trends/<date>/<HH-MM>-data.json` 归档文件（复用 `fetchSnapshot` 路径缓存，`arrayField = "keywords"`）反序列化为 `TrendsDigest`——与趋势 Tab 同构渲染（共享 `TrendsContent`，仅差二级页顶栏返回/无下拉刷新/底部不预留底栏）。VM 按日期隔离实例（`TrendsArchiveViewModel`，key = `trends-date-$date`），全套路式与「历史总览」一致。

## 桌面小组件「今日热点」（widget/ 包，Glance）

- 只读归档 `latest_overview`（与总览同源同语义）。缓存为 App 级 SharedPreferences `hot_now_widget`（多小组件实例共享，刻意不用 Glance per-id 状态）。
- 刷新三路：系统 30min `updatePeriodMillis` / 头部按钮 `RefreshHotNowAction` / App 总览刷新成功联动（`HotNowWidgetUpdater.refreshFromApp`，同进程命中 ArchiveHttpClient 2 分钟缓存，零额外网络）。拉取失败保留旧数据不清空（小组件无错误交互入口）。
- 配色直接取 `ui/theme/Color.kt` 设计令牌组 day/night `ColorProvider`（App 迷你版，不用壁纸动态色）；条目只展示排名 + 标题（+突发胶囊），来源/互动指标刻意不上小组件；Glance 1.1.1 不支持 res/font 自定义字体，层级靠字号 + 字重。
- 视觉对齐 App 内「今日热点」卡（`HotTopicsSection`）：头部品牌渐变 Hero（`widget_header_gradient` day/night drawable，BrandGradient 同源）= 标题行 + 「今日综述」digest 正文（≤2 行截断，空串不渲染，与总览 Tab digest Hero 同构；digest 随 items 一同存入 `hot_now_widget` SharedPreferences 的 `digest` 键）+ 卡面色描边背景（`widget_bg` day/night drawable，surfaceContainerLow + 1dp outlineVariant 描边）；条目为迷你排名徽章（18dp，分档同 App RankBadge：1 名 tertiary 实心 / 2-3 tertiaryContainer / 其余 surfaceContainerHigh）+ Bold 标题，行间发丝线。改徽章分档/渐变色须与 `ui/components/RankBadge.kt`、`theme/Color.kt` 保持同步。

## 每日更新本地通知（notify/ 包）

`DailyUpdateNotifier.kt` 单文件三角色（Notifier / Scheduler / Worker）：

- WorkManager one-shot 自查链轮询归档 `latest_overview.generatedAt` 指纹（不用 `updated_at_ms`，总览失败继承旧值时不误报），检查时刻表对齐流水线批次（北京时间 08:40 / 18:40 = 各批次 +40min 余量；批次时刻**唯一真相源是 `data/PipelineSchedule.kt`**：内置默认 `DEFAULT_BATCH_SLOTS`（08:00 / 18:00）+ 数据仓库 `app_config.json` 的 `batch_slots` 远程覆盖（`AppConfigSync`，见上文根级文件节）——`checkSlots()` 按需读当前生效表派生、总览 Hero「下一批」caption 与「已是最新」胶囊也经 `nextBatchEpoch` 同源计算，**改批次时间只改 PipelineSchedule 默认值与 app_config.json**；批次 08:00 由仓库外机器调度，仓库 workflow 承载 18:00 批——勿因 workflow 只有一个 cron 误判检查时刻为 bug。Worker 运行前先 `AppConfigSync.refresh()` 同步远程配置再续链，生效表变化且开关开启时启动侧 Host 会重排检查链）；档内未就绪 40min 补查最多 2 次。
- 每天（北京时间）至多 1 条（当天首批时发，之后批次静默），正文 digest 始终中文。设置页开关默认关，存 `display_prefs` 的 `daily_notify` 键；API 33+ 打开时经设置页请求 `POST_NOTIFICATIONS` 运行时权限。
- Worker 除「开关已关」外所有路径先续链再干活（失败不断链），刻意不用 `Result.retry()`；任务持久化跨重启，无 boot receiver。
- **冷启动新数据提示随同一开关**（`NewDataPromptHost` 挂载于 `AiNewsHubApp`——ModalBottomSheet 须在主题内取色）：开关开启时每次冷启动比对指纹（`last_notified_overview_at`），落后于最新 `generatedAt` 即弹底部弹层（今日综述 + 单个「我知道了」按钮，无跳转动作），关闭即写回指纹，与首启引导经 `deferWhile` 互斥（引导优先）——与通知互补：每天至多 1 条提醒，通知或提示任一形式先触达即静默。取数走 `ArchiveHttpClient.fetchLatestOverview(networkOnly = true)`（必须真实打网络：断网/服务端故障不弹窗、不读盘兜底；联网冷启动相比共享 2 分钟缓存至多多一次 index 请求）。

## 源标识 / 元数据单点定义

- 8 源（HackerNews / GitHub Trending / OpenAI×Anthropic / HuggingFace Papers / Product Hunt / The Rundown AI / AIHot 精选 / stormzhang AI）的 **key 字面量集中于 `data/source/SourceKeys.kt`**（全 App 唯一真相源，归档 Repository / 摘要 Repository / UI 跳转分发 / 强调色 when 分支一律引用其常量，不写裸字符串，杜绝 key 漂移静默断裂）。
- **UI 元数据**（icon / 品牌色 / 标题 / 副标题 / URL）集中于 `ui/more/SourceMeta.kt` 的 `sourceMeta(key)`，`DEFAULT_SOURCE_ORDER` 为默认顺序。信息源页 / 摘要 Tab / 关于页三处都从 `sourceMeta(key)` 派生，不再各自硬编码。
- 用户在「信息源」页长按拖拽自定义顺序（reorderable 库），持久化于 `display_prefs` 的 `source_order` 键；**摘要 Tab 跟随用户顺序**（`SummaryViewModel.sourceKeys` 读 `SettingsStore.sourceOrderFlow`），**关于页固定默认顺序**。

## 本地诊断（data/diagnostics/，零遥测）

崩溃可见性方案：**纯本地收集 + 用户主动导出**，不接任何崩溃上报 SDK、不自动上传任何数据（安全红线：报告不含用户密钥/AI key）。两个组件，均在 `App.onCreate` 初始化（CrashMarker 最先安装）：

- **`CrashMarker`**：垫在默认 UncaughtExceptionHandler 链上的一层，崩溃时**同步**写 `filesDir/last_crash.txt`（时间戳 + 线程名 + 截断 16KB 的栈），写完委托 previous handler 保系统崩溃流程。只留最近一次（新崩溃覆盖）。选 filesDir 而非 cacheDir——「清理缓存」整删 cacheDir，诊断数据必须存活。ANR 不在覆盖范围（明确不支持）。改它前注意：崩溃时进程将死，**不能改走协程**，一切 runCatching 兜底。
- **`DiagnosticsLog`**：错误环形记录（容量 20，`filesDir/diagnostics/recent_errors.json`，org.json）+ 报告组装。**唯一喂入点是 `ui/UiState.kt` 的 `toUiError` 统一漏斗**（16 个 ViewModel 出口全覆盖）；`AppException.NoData` 例行空态不记；数据层静默 runCatching 吞掉的失败有意不记（补日志是另一课题）。`record` 为同步入口（漏斗非 suspend），内部丢到自有 IO 协程落盘；测试用 `awaitWrites` 排空（先 join 再取锁，纯空锁排队有调度竞态）。报告 `buildReport` 恒中文（受众是开发者，与流水线内容恒中文同理），含版本/设备/系统/语言/离线态/最近崩溃/最近错误；纯拼装拆在顶层 `formatReport` 供 JVM 直测。

UI 出口在 设置 → 诊断信息（`SettingsScreen` 末 section + ModalBottomSheet，复制/分享/清除；复制与分享经 `ui/components/ShareText.kt` 通用助手，`WebIntents.shareUrl` 同源委托）。

## 其他

- 端侧 AI（翻译 / 系统选中译）统一经 `AiChatClient` 访问「设置 → AI 服务」里的用户配置。
- **本地搜索索引**：`SearchIndexRepository`（单例 object，`App.onCreate` init）在成功取数后回填 Room `search_items` 表（url 主键 = 行点击时传给 `openUrl` 的同一 URL；source 存 `SourceKeys` key 或条目自带源名，UI 经 `sourceMeta` 转本地化标题），90 天抽样清理。回填点三类：① 7 个归档源列表 Repository；② `SummaryRepository.summarize/summarizeOn`（摘要 Tab 与历史摘要——日常阅读主路径，v2 条目带原文 URL，是索引覆盖 8 源的主入口）；③ `OverviewRepository.parseDigest`（总览 Top10/breaking，冷启动即拉取）。**刻意不回填** `NewsRepository.fetchItems`（aihot 实时 API 精选流——第三方数据、permalink 指向其站内阅读页，与「本地搜索 = 本 App 归档数据」定位不符；开发期曾回填过，已由 Room v4 迁移清理）。由独立的「本地搜索」页消费（`Page.LocalSearch`，总览顶栏入口，详见 persistence.md），与联网搜索页互不相干。
- **更新检查与应用内直装（含后台下载）**：`UpdateChecker`（关于页手动触发）查 GitHub `releases?per_page=20` 列表，与本地 versionName 逐段比较，剔预发布后聚合「比当前新的全部 Release」（跨版本更新一次看全），APK 直链从最新一条的 `assets` 解析（release.yml 固定挂单个 `app-release.apk`，按「第一个 `.apk` 资产」解析防改名脆断）；任何失败静默视为已是最新（匿名限频 60/h，仅手动无压力）。更新说明数据流：release.yml 从 CHANGELOG.md 提取版本节写入 Release body → `UpdateInfo.notes` 聚合 → 弹窗拼 `## [version]` 合成头复用 `parseChangelog` 结构化渲染（共享渲染件 `ui/more/ChangelogBlocks.kt`），历史 Release 的自动生成 body 解析不出条目时退纯文本。命中后下载交前台服务 `UpdateDownloadService`（dataSync 类型，manifest 已配 `FOREGROUND_SERVICE_DATA_SYNC`）：关弹窗/离开关于页/退后台均继续，进度常驻通知栏（渠道 `update_download`，可取消），状态经 companion StateFlow 回流弹窗；实际下载由 `UpdateDownloader` 执行——OkHttp（`base` 派生、**清零 callTimeout**，否则 30s 总超时掐断下载）流式写 `cacheDir/updates/`；完成后 FileProvider（authority `${applicationId}.fileprovider`，路径白名单 `res/xml/file_paths.xml`）以 `content://` 拉起系统安装器——弹窗开着走弹窗「安装」，否则点完成通知经 MainActivity（extra 直连，Android 12+ 禁通知 trampoline）同一通路安装。Android 8+ 需用户在系统设置为本 App 开「安装未知应用」（Manifest 声明 `REQUEST_INSTALL_PACKAGES`，未授权时跳设置页）；无 APK 资产或下载失败兜底回 Release 网页。
- 数据模型：`NewsItem` / `HackerNewsStory` 用 `@Parcelize`。
