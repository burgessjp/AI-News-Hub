# 导航（ui/nav/，自实现多栈）

> 面向 AI 编码代理的领域约定，从根 `AGENTS.md` 拆出。改导航、新增二级页、动 WebView / 深链前先读本文。导航相关代码集中在 `ui/nav/`；`MainActivity.kt` 只剩 Activity 本体（深链 extra 解析 + 挂载壳 `AiNewsHubApp`）。

- 5 个根 tab（总览/摘要/关注/趋势/更多）+ 每 tab 独立二级页栈。导航模型在 `ui/nav/Page.kt`（`Page`/`Screen` sealed interface + `toBundle()`/`pageFromBundle()`/`stacksToBundle()`/`stacksFromBundle()`），状态机在 `ui/nav/AppNavState.kt`（`AppNavState`：currentTab/pageStacks/push/pop/selectTab/goToRoot + `rememberAppNavState()` 自定义 Saver 挂 `rememberSaveable`，进程被杀可恢复）。切 tab 保留各自栈。「关注」（`ui/follows/FollowsScreen.kt`，关键词订阅命中流）原为趋势页顶栏进入的二级页 `Page.Follows`，现已是根 tab（`AppTab.Follows`）。
- ⚠️ **新增二级页必须同步加三处**：`ui/nav/Page.kt` 的 `Page` 子类、同文件 `toBundle()`/`pageFromBundle()` 分支、`ui/nav/PageView.kt` 的 `PageView` 分支。页面入口回调统一在 `PageView`/`TabRoot` 分支内经 `nav.push(Page.Xxx)` 构造，不新增穿线参数；stores/repos 经 `PageEnv`、显示偏好经 `DisplayControls` 分组下传（`ui/nav/PageView.kt`）。
- ⚠️ **含列表/页码的页，`LazyListState`/`PagerState` 一律在 `AiNewsHubApp`（`ui/nav/AiNewsHubApp.kt`）层持有并下传**——`AnimatedContent` 换页即销毁屏内 `remember`/`rememberSaveable`，屏内自持会丢滚动位置。不要在屏内 `rememberLazyListState()`。摘要 Tab 为「顶部提示行 + 源名 chips 导航 + 内容 Pager」结构（与历史摘要按日期页同构），`summaryPagerState` 同理上提。
- ⚠️ 含 WebView 的页转场 override 为 `FADE`（横向位移会让 AndroidView 撕裂），其余约定见 `ui/anim/Motion.kt`。
- 二级页取数的两条路式：① 独立数据源的小 VM（如 `Page.TrendsCloud` 词云页读独立 `trends_cloud.json`，UiState 范式、Success 幂等）；② 按参数隔离实例（如日期页 `viewModel(key = "trends-date-$date")`）。纯 Canvas 页无列表，不上提 listState（同 `Page.Sources`）。
- `openUrl` 是打开网页的**唯一入口**（统一记录浏览历史 + push `Page.Web`，也兼任列表「已读」判定数据源——`rememberReadUrls()` 以 `url in browse_history` 弱化已读条目），定义于 `ui/nav/AiNewsHubApp.kt`，全 App 链接都走内置 WebView，不走外部浏览器。
- 外部深链三组入口，`MainActivity` 为 `singleTask`，冷/热启动分别在 `onCreate`/`onNewIntent` 解析（`applyDeepLinks`：extras 优先、其次 uri），旋转重建不重复 push（`savedInstanceState == null` 守卫）：
  - `EXTRA_OPEN_SETTINGS`（系统选中译「去设置」）与 `EXTRA_OPEN_URL`/`_TITLE`/`_SOURCE`（桌面小组件点击直达内置 WebView）；
  - **`ainewshub://` 自定义 scheme**（Manifest intent-filter，供浏览器/二维码/自动化工具）：`ainewshub://web?url=<encoded>&title=&source=`（仅接受 http/https，防本地 scheme 注入）→ 复用小组件的 `pendingOpenUrl` 消费链直达 WebView；`ainewshub://tab/<overview|summary|follows|trends|more>` → `pendingTab` 经 `nav.goToRoot` 切根 tab；`ainewshub://settings` → 与 `EXTRA_OPEN_SETTINGS` 共用 `openSettingsRequest`（消费后复位，热启动可重复触发）。不做 https App Links（需 assetlinks 验证，不上商店无收益）。
