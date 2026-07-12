# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

AIHot — Android  AI 资讯聚合客户端。Kotlin + Jetpack Compose + Material3，从 aihot.virxact.com 公开 API 拉取 AI 行业动态，同时接入 HackerNews 和 AI 翻译功能。

## 常用命令

```bash
# 编译 debug
./gradlew assembleDebug

# 编译 release（需 keystore.properties）
./gradlew assembleRelease

# 安装到设备
./gradlew installDebug

# 编译并安装 release
./gradlew installRelease

# 检查代码（目前无 lint/test task）
./gradlew compileDebugKotlin
```

无单元测试、无 lint 配置。验证靠 `assembleDebug` 编译通过。

## 架构

### 导航：自定义多栈底部导航

`MainActivity.kt` 自实现多栈导航，**不依赖 Navigation Compose**。

- `currentTab`（4 个 tab）+ `pageStacks: Map<AppTab, List<Page>>` 为每个 tab 维护独立的二级页栈
- 切 tab 保留各自二级栈；push/pop 只操作当前 tab 栈
- `Page` 是 sealed interface，通过 `toBundle()`/`pageFromBundle()` 序列化到 `Bundle`，经 `pageStacksSaver` (Saver) 绑定 `rememberSaveable` 实现跨进程重启恢复
- `Screen` sealed interface 区分根/二级页，驱动 `AnimatedContent` 转场：PUSH（横向推入）用于普通页，FADE 用于含 WebView 的页（AndroidView 位移会撕裂）
- Tab 根页：`FeaturedTab` / `AllTab` / `DailyTab` / `MoreScreen`

### 数据层（`data/`）

所有网络请求用 `OkHttpClient`，**不引入 Retrofit**。JSON 解析用 `org.json`（内置），**不引入 Gson/Moshi**。`NewsItem` 和 `HackerNewsStory` 用 `@Parcelize` 实现 Parcelable。

| 类 | 职责 |
|---|---|
| `NewsRepository` | `/items`（分页）、`/hot-topics`、`/daily`、`/dailies` |
| `HackerNewsRepository` | HN Firebase API — top stories（4h 文件缓存 + forceRefresh）、评论（懒加载逐层拉取） |
| `TranslationRepository` | OpenAI 兼容 `/v1/chat/completions`，SHA256 缓存到文件，Mutex 按 key 防并发重复请求 |

数据模型都在 `NewsItem.kt`（NewsItem、DailyReport、HotTopic 等）和 `HackerNews.kt`（HackerNewsStory、HackerNewsComment、缓存包装类）。

### ViewModel 层（`ui/`，与各自 Screen 同包或就近放置）

- `ItemsViewModel` — 动态列表（精选/全部 + 分类筛选 + 搜索），用 `StateFlow<Filter>` + `flatMapLatest` + `debounce(300ms)` 驱动重新加载，cursor 分页
- `HotTopicsViewModel` — 今日热点
- `DailyViewModel` — 日报/归档
- `HackerNewsViewModel` — HN 列表（翻译状态管理、缓存新鲜度展示）
- `HackerNewsCommentsViewModel` — HN 评论树懒加载 + 逐条翻译

### 主题系统（`ui/theme/`）

两层设计：

1. **MD3 规范层**（`Color.kt` / `Type.kt` / `Shape.kt` / `Theme.kt`）：Light/Dark 双色板（cyan 主色 `#0098A2`），固定品牌色不跟随系统 dynamic color
2. **语义层**（`AppText.kt` / `AppAlpha.kt`）：`AppText.titleHero` / `body` / `caption` 等 9 个语义档，组件统一用 `style = AppText.xxx` 而非散落 sp 字面量；`AppAlpha` 同理收口透明度

`AIHotTheme` 接收 `darkTheme: Boolean` + `fontFamily: FontFamily?`，字体切换通过 `Typography.withFontFamily()` 全文替换。

### 持久化

- `SettingsStore` — DataStore `display_prefs`：主题模式（System/Light/Dark）+ 字体（System/Serif/Monospace）
- `TranslationConfigStore` — DataStore `translation_prefs`：翻译服务配置（baseUrl/apiKey/model/enabled）
- HN 缓存和翻译缓存分别写 `cacheDir` 下的 JSON 文件

### 翻译功能（`ui/translate/`）

`TranslateSelectionActivity` 响应 `ACTION_PROCESS_TEXT`，在系统选中菜单注册"译"。走 `TranslationRepository`，复用 HN 评论翻译的缓存/Mutex 机制。

### 组件（`ui/components/`）

`AppTopBar`、`AppBottomBar`（4 tab NavigationBar）、`Card`、`SettingsRow`、`Skeleton`、`HotTopicsSection`、`StateViews`（Loading/Error 通用组件）、`NewsCard`

### 页面（`ui/` 下按功能分包）

- `ui/tabs/` — 三个内容 tab 的根页
- `ui/items/` — ItemsScreen（带分类/搜索的列表）、SearchScreen、HackerNewsScreen、HackerNewsCommentsScreen
- `ui/daily/` — DailyScreen、DailyDateScreen、DailyArchiveScreen
- `ui/more/` — MoreScreen、SettingsScreen、AboutScreen、SettingsStore
- `ui/webview/` — WebViewScreen（内置 WebView）
- `NewsDetailScreen.kt` — 新闻详情（中文翻译页 + 原文链接）

### 关键依赖

- **Compose BOM** — Material3 + icons-extended
- **OkHttp** — 网络
- **Coil** — 图片加载
- **DataStore Preferences** — 持久化
- **WebKit** — `androidx.webkit` 用于内置 WebView
- 无 DI 框架 — Repository 在 ViewModel/Composable 内直接 `new`

## 编码约定

- 包名 `com.example.aihot`，namespace 同
- minSdk 24，targetSdk/compileSdk 35，JVM target 17
- ProGuard/R8 已配置 release 混淆，数据模型类全保留（`-keep class com.example.aihot.data.**`）
- 注释用中文，代码/变量名英文
- `AppText.xxx` 收口所有字号，`AppAlpha.xxx` 收口透明度，不直接写 `.sp` / `.alpha`
