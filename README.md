# AIHot · AI News Hub

> 一个把「AI 圈今天发生了什么」装进一个 Android App 的资讯聚合客户端。

AIHot（应用名 **AI News Hub**）聚合了多个高质量的 AI 信息源——HackerNews、GitHub Trending、HuggingFace Papers、Product Hunt、stormzhang AI 资讯、The Rundown AI、LinuxDo 热榜，以及 `aihot.virxact.com` 提供的「AIHot 精选」与 AI 日报——并在此基础上叠加端侧 AI 的「今日总览」「中文摘要」「整页翻译」等能力，让用户在一个应用里就能读完 AI 圈当日动态。

- 技术栈：Kotlin + Jetpack Compose + Material 3
- 单模块工程，包名 `com.example.aihot`
- 目标 Android 7.0（API 24）及以上

---

## ✨ 核心功能

### 三大根 Tab

| Tab | 做什么 |
|-----|--------|
| **总览** | 端侧 AI 对 7 个归档源的当日综合分析：今日热点 Top10，其中 AI 判定为「突发重磅」的条目带 Breaking 标签、特殊样式，最前展示。用户自配 AI 服务 key，当日指纹缓存命中则零开销刷新。 |
| **摘要** | 7 个归档源当日的 AI 中文要点（流水线预生成），打开即看、无需等待。 |
| **更多** | 设置、AI 服务配置、信息源聚合入口、历史摘要、AI 日报归档、关于页等。 |

### 浏览区（更多 → 信息源）

8 个信息源磁贴，每个都走自己的二级页：

- **HackerNews** —— Firebase API 实时榜单 + 评论树展开
- **GitHub Trending** —— 仓库语言/时间窗口筛选
- **HuggingFace Papers** —— 论文热度榜
- **Product Hunt** —— 当日产品榜（仅归档模式）
- **stormzhang AI 资讯**、**The Rundown AI** —— 中文/英文 AI Newsletter
- **LinuxDo 热榜** —— 实时拉取
- **AIHot 精选** —— 第三方服务 aihot.virxact.com 的「今日热点 + 精选 TOP20」

5 个稳定源支持「实时抓取 / 归档快照」双模式切换（设置 → 数据源模式），归档来自配套的数据流水线。

### AI 能力（用户自配 key）

- **今日总览**：端侧实时综合分析，结果当天缓存
- **AI 中文摘要**：每个源的当日要点（归档预生成）
- **整页翻译**：内置 WebView 的「翻译本页」、系统选中翻译菜单「译」入口
- **用量统计**：按「模型 × 月」聚合 token，按刊例价估算费用

### 内置 WebView

全 App 链接统一在应用内打开，不走外部浏览器：

- 阅读模式（注入 Mozilla Readability 提取正文）
- 整页翻译（与原文对照、可拖拽半/全屏弹层）
- 网页下载、HTML5 视频全屏、长按图片/链接操作
- 字号跟随系统设置

### 其他

- **AI 日报** 与 **历史归档**（按日期回溯）
- **搜索**：本地搜索历史 + 今日热点热词引导
- **浏览历史**（本地 Room 存储）
- **主题**：Material You 动态取色（Android 12+）、字体族切换、字号档位、暗色模式

---

## 📱 下载与安装

- **直接安装**：在 [Releases](../../releases) 页下载最新 APK（`AIHot-*-release.apk`）传到手机安装。
- **从源码构建**：见下文。

> 应用未上架应用商店，APK 直装即可。Android 8+ 首次安装需在系统设置里允许「安装未知来源应用」。

---

## 🛠 从源码构建

### 环境要求

- JDK 17
- Android Studio（任何支持 AGP 8.7.3 的近期版本）或仅命令行 Gradle
- Android SDK Platform 35、Build-Tools 35.x

### Debug 构建

```bash
git clone <本仓库地址>
cd aihot
cp local.properties.example local.properties   # 按需修改 SDK 路径（若没有示例文件，手动指定 sdk.dir）
./gradlew assembleDebug                        # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                         # 直装到已连接的设备/模拟器
```

### Release 构建

Release 构建需要本地签名材料（**绝不入库**）：

1. 在工程根放一个 `keystore.properties`：

   ```properties
   storeFile=app/aihot-release.jks
   storePassword=***
   keyAlias=***
   keyPassword=***
   ```

2. 把对应的 `aihot-release.jks` 放到 `app/` 下。

3. 执行：

   ```bash
   ./gradlew assembleRelease
   # 产物：app/build/outputs/apk/release/app-release.apk
   ```

CI（GitHub Actions）从仓库 secrets 还原 keystore，打 `v*` tag 自动出 Release。

---

## 🔑 配置 AI 服务

AIHot 的所有 AI 能力（今日总览、翻译）**不内置任何 key**，由用户在应用内填入：

打开 App → **更多 → AI 服务**，选择服务商预设（DeepSeek / 智谱 GLM / 自定义 OpenAI 兼容服务）→ 填入 API Key 与模型名 → 保存。

- Key 仅存于应用私有目录（DataStore），不上报、不进日志。
- 「自定义」选项兼容任何 OpenAI API 协议的服务，只需填 `baseUrl`（含 `/v1` 段）。
- 用量与费用估算可在「AI 服务」页查看。

---

## 📰 数据来源

| 类别 | 来源 | 说明 |
|------|------|------|
| 第三方 API | `aihot.virxact.com` 公开 API | AIHot 精选（今日热点 + TOP20）、AI 日报与归档 |
| 第三方 · 实时 | HackerNews、LinuxDo、GitHub Trending 等 | App 内直接抓取 |
| 第三方 · 归档 | 配套数据流水线 | 每日 07:00 / 15:00（北京时间）抓取 + AI 总结 + 推送到 [gitcode 数据仓库](https://gitcode.com/peng1818/AI-News-Hub-Data) |
| 端侧 AI | 用户自配 | 总览综合分析、运行时翻译 |

数据仓库格式详见 [`docs/news-hub-data-usage.md`](docs/news-hub-data-usage.md)。

---

## 🗂 项目结构概览

```
app/                       唯一 Android 模块
  src/main/java/com/example/aihot/
    MainActivity.kt        自定义多栈导航 + 页面路由（不用 Navigation Compose）
    data/                  Repository、数据模型、Room、DataStore、数据源双模式
    ui/                    ViewModel + Compose Screen，按功能分包
      tabs/                3 个根 Tab 框架
      overview/            今日总览（端侧 AI 综合分析）
      summary/             摘要 Tab + 历史摘要
      items/               AIHot 精选列表 / 全部动态
      daily/               AI 日报与归档
      more/                更多页 / 信息源 / 关于
      webview/             内置 WebView + 阅读模式 + 整页翻译
      translate/           翻译仓库 + 系统选中翻译入口
      components/           复用组件（卡片、徽章、骨架屏、SectionHeader）
      theme/                Material 3 双层主题（规范 + 语义）
      anim/                转场动画规范
  src/main/assets/readability.js   WebView 阅读模式正文提取
scripts/                   Python 数据流水线 + 图标生成
docs/news-hub-data-usage.md   数据仓库格式与消费方式
.github/workflows/         CI（构建）/ 发布 / 数据流水线
gradle/libs.versions.toml  所有依赖版本集中
```

> 想深入了解架构、约定、数据流水线的每个脚本？看 [`AGENTS.md`](AGENTS.md)。

---

## 🤖 数据流水线（可选）

如果你需要自己跑数据流水线（例如部署私有数据源）：

```bash
pip install -r scripts/requirements.txt
python -m playwright install --with-deps chromium   # 仅 LinuxDo 源过 Cloudflare 需要

export AI_NEWS_HUB_AI_BASE_URL=...
export AI_NEWS_HUB_AI_MODEL=...
export AI_NEWS_HUB_AI_API_KEY=...
export GITCODE_TOKEN=...
export PRODUCT_HUNT_KEY=...                          # 可选，缺失则该源跳过

bash scripts/pipeline.sh
```

流水线编排入口是 `scripts/pipeline.sh`，缺任一硬依赖环境变量会直接退出。单源失败不影响其他源，`index.json` 的 latest 指针从上一次成功快照继承，客户端永远拿到有效数据。

---

## 🧰 技术栈

- **UI**：Jetpack Compose、Material 3（Material You 动态取色）、activity-compose 1.10+（含预测返回手势）
- **异步**：Kotlin Coroutines + Flow，`StateFlow` 驱动 UI
- **网络**：OkHttp（不引入 Retrofit）；JSON 用内置 `org.json`；HTML 解析用 jsoup
- **持久化**：DataStore Preferences（设置/AI 配置/用量）、Room（浏览历史）
- **图片**：Coil
- **字体**：Inter（SIL OFL 1.1，本地内嵌）
- **工具链**：AGP 8.7.3、Kotlin 2.0.21、KSP 2.0.21-1.0.28、JDK 17

---

## 📜 开源依赖致谢

- [Mozilla Readability](https://github.com/mozilla/readability)（Apache-2.0）—— WebView 阅读模式正文提取
- [Inter Font](https://github.com/rsms/inter)（SIL OFL 1.1）—— 内嵌字体
- [jsoup](https://jsoup.org/) —— HTML 抓取与解析
- [OkHttp](https://square.github.io/okhttp/)、[Coil](https://coil-kt.github.io/coil/)、Jetpack 系列组件

---

## 📄 许可

本项目代码仅供学习交流。第三方依赖遵循各自开源协议（如上「开源依赖致谢」）。

如果你打算基于此项目二次开发或部署私有数据流水线，请同时遵守各信息源的访问频率限制与服务条款。
