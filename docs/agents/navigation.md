# 导航（MainActivity.kt，自实现多栈）

> 面向 AI 编码代理的领域约定，从根 `AGENTS.md` 拆出。改导航、新增二级页、动 WebView / 深链前先读本文。

- 4 个根 tab（总览/摘要/趋势/更多）+ 每 tab 独立二级页栈；`Page` 是 sealed interface，经 `toBundle()`/`pageFromBundle()` + 自定义 `Saver` 挂 `rememberSaveable`，进程被杀可恢复。切 tab 保留各自栈。
- ⚠️ **新增二级页必须同步加三处**：`Page` 子类、`toBundle`/`pageFromBundle` 分支、`PageView` 分支。
- ⚠️ **含列表/页码的页，`LazyListState`/`PagerState` 一律在 `AiNewsHubApp` 层持有并下传**——`AnimatedContent` 换页即销毁屏内 `remember`/`rememberSaveable`，屏内自持会丢滚动位置。不要在屏内 `rememberLazyListState()`。摘要 Tab 为「顶部提示行 + 源名 chips 导航 + 内容 Pager」结构（与历史摘要按日期页同构），`summaryPagerState` 同理上提。
- ⚠️ 含 WebView 的页转场 override 为 `FADE`（横向位移会让 AndroidView 撕裂），其余约定见 `ui/anim/Motion.kt`。
- `openUrl` 是打开网页的**唯一入口**（统一记录浏览历史 + push `Page.Web`），全 App 链接都走内置 WebView，不走外部浏览器。
- 外部深链两个入口：`EXTRA_OPEN_SETTINGS`（系统选中译「去设置」）与 `EXTRA_OPEN_URL`/`_TITLE`/`_SOURCE`（桌面小组件点击直达内置 WebView）。`MainActivity` 为 `singleTask`，冷/热启动分别在 `onCreate`/`onNewIntent` 解析，统一经 `openUrl` 消费；旋转重建不重复 push（与 `EXTRA_OPEN_SETTINGS` 同款 `savedInstanceState == null` 守卫）。
