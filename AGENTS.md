# AGENTS.md

面向 AI 编码代理的项目约定。根文件只放「每个任务都可能用到」的全局规则，且只写「猜不到 / 易踩坑 / 反默认」的内容；领域细节拆在 `docs/agents/` 下（见「深入文档」），动对应领域前先读对应文件，其余请直接读对应代码。

**AI News Hub** —— Android AI 资讯聚合客户端。Kotlin + Jetpack Compose + Material3，单模块（`:app`），包名 / namespace / applicationId 均为 `com.peng.ainewshub`。

## 构建

```bash
./gradlew testDebugUnitTest    # 单测（改解析器/纯逻辑/导航序列化必跑，约定见「深入文档」测试篇）
./gradlew assembleDebug        # 日常编译验证（无 lint、无仪器测试）
./gradlew installDebug         # 安装到设备
./gradlew assembleRelease      # 需签名配置，见「安全红线」
```

**无仪器测试、无 lint**（`app/src` 下为 `main` + `test`）。改动后至少跑 `testDebugUnitTest` + `assembleDebug` 确认通过，再真机手测。工具链版本一律以 `gradle/libs.versions.toml` 为准，不在此重复。

## 编码约定（与默认不同，务必遵守）

- **注释用中文，代码/变量名用英文**（与存量代码一致）。
- **不引入 Retrofit / Gson / Moshi**：网络一律 `OkHttpClient`，JSON 用内置 `org.json`（App 端不做 HTML 抓取，抓取全部由流水线承担）。`OkHttpClient` 统一经 `data/HttpClients.kt` 的共享 base 派生（`base` 或 `base.newBuilder()`），不各自 `OkHttpClient.Builder().build()`。
- **不用 Navigation Compose**（见「深入文档」导航篇），**无 DI 框架**：Repository 在 ViewModel / Composable 内直接构造。
- 字号一律 `AppText.xxx`、透明度一律 `AppAlpha.xxx`、圆角一律 `MaterialTheme.shapes` 或 `CircleShape`、颜色只走 `colorScheme`——不散落 `.sp`/`.alpha`/hex 字面量（hex 仅两处集中例外：源品牌色 `ui/more/SourceBrandColors.kt`、词云调色板 `ui/trends/CloudWordColors.kt`）。列表排名/统计/章节条/骨架屏统一复用 `ui/components/` 现有组件，不新建私有拷贝。
- **UI 文案一律走 string 资源，不写硬编码字面量**，新 feature 必须同步 `values/`（中文全集）+ `values-en/` 双语；语言切换机制与共用词条约定见「深入文档」i18n 篇。
- 协程 + Flow：`StateFlow` 驱动 UI，`collectAsStateWithLifecycle` 订阅；网络在 Repository 内切 `Dispatchers.IO`；并发去重用 `Mutex.withLock`。
- release 开启 R8 + shrinkResources；`com.peng.ainewshub.data.**` 已全部保留（`app/proguard-rules.pro`），新增需反射/序列化保留的类时同步补规则。

## 深入文档（动对应领域前先读）

| 任务领域 | 文档 | 内含关键强约束 |
|---|---|---|
| 导航 / 新增二级页 / WebView / 深链 | [docs/agents/navigation.md](docs/agents/navigation.md) | 新页三处同步、列表状态上提、`openUrl` 唯一入口、WebView 页 FADE 转场 |
| 文案 / 双语资源 / 语言切换 | [docs/agents/i18n.md](docs/agents/i18n.md) | 双语同步、`AppLocale.kt` 单点机制、流水线内容恒中文 |
| 数据源 / Repository / 小组件 / 通知 | [docs/agents/data-layer.md](docs/agents/data-layer.md) | 恒定归档（实时路径已删除）、归档禁 raw 直链（WAF）、`CHECK_SLOTS` 联动流水线批次、`SourceKeys.kt` 唯一真相源 |
| DataStore / Room / 缓存 | [docs/agents/persistence.md](docs/agents/persistence.md) | prefs 键清单、favorites 表迁移与清理红线 |
| 单元测试 / fixture / Robolectric | [docs/agents/testing.md](docs/agents/testing.md) | 必测层清单、fixture 存放与裁剪、object 单例重置 |
| `scripts/` 流水线 / CI/CD | [docs/agents/pipeline.md](docs/agents/pipeline.md) | 4 个必需环境变量、失败继承语义、日期统一北京时间 |

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

改动导航机制、数据源模式、流水线行为时，同步更新 `docs/agents/` 对应文件与相关代码文档注释；领域细节不要回填到根文件，保持根文件精简。
