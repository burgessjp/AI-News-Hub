# 测试

> 面向 AI 编码代理的领域约定，从根 `AGENTS.md` 拆出。新增/改动解析器、纯逻辑、导航序列化前先读本文。

## 现状与验证闭环

- `app/src/test`（JVM 单测，JUnit4）是当前唯一的自动化测试层；**无 androidTest 仪器测试、无 Compose UI 测试**（暂不要求）。
- 改动后跑 `./gradlew testDebugUnitTest`，配合 `./gradlew assembleDebug` 构成验证闭环；CI（build.yml）PR 时先跑单测再打 APK。
- 依赖（`gradle/libs.versions.toml`，全部 testImplementation 不进 APK）：`junit`、`kotlinx-coroutines-test`、`mockwebserver`（OkHttp 同族）、`robolectric`、`org-json`（纯 JVM 用例的 org.json 实现，替代 android.jar 桩）。

## 哪些层必须覆盖（按优先级）

1. **数据解析器**：各模型 companion `fromJson` / jsoup `fromArticle`（HTML 片段按真实页面选择器构造，上游改版时这里是第一报警哨兵）；归档快照结构经 `ArchiveHttpClientTest` 端到端覆盖。
2. **纯逻辑**：`FollowMatcher`、`PipelineSchedule`、`AppException → UiState.Error` 映射等无依赖函数。
3. **导航序列化契约**：`Page` Bundle 往返（`PageBundleTest` 有「页面类型全量覆盖护栏」——新增 Page 子类型必须同步补样例，护栏断言 `permittedSubclasses` 数量）。
4. **取数骨架**：`ArchiveHttpClient` 的缓存/刷新/错误分野/磁盘兜底语义（MockWebServer + fixture）。
5. Room 迁移：自 v5 起 schema 已导出（`app/schemas/`），新版本必须配 Migration + MigrationTestHelper 回归（依赖 schema 文件，历史 v1-v4 无 schema 只能真机验证）。

暂不要求：Composable UI 测试、ViewModel 层测试（基建验证后逐步补）、仪器测试。

## 约定

- **fixture 存放**：`app/src/test/resources/fixtures/`，从数据仓库（`repo/` 克隆或 gitcode 归档）真实批次裁剪，保持仓库相对路径结构（`index.json`、`hackernews/<date>/<time>-data.json`…）；裁剪原则：latest 指针保真、items 每源 ≤5 条、历史索引每源 ≤3 日期。小型字段级样例可直接内联在测试代码里。
- **测 object 单例时的状态重置**：`ArchiveHttpClient.reconfigureForTest(baseUrl)` 把基址指向 MockWebServer 并清空全部内存缓存，每个用例 `@Before` 调用（生产代码禁止调用）。
- **断网模拟**：把基址指向必然连接拒绝的本地端口（如 `http://127.0.0.1:1`），得到的是传输层 `IOException`——与真实断网同语义；不要用 `SocketPolicy.DISCONNECT_AT_START`（与 OkHttp 连接复用交互后可能走出 HTTP 响应路径）。
- **需要 Android 框架类的用例**（Bundle / Context / 资源）用 Robolectric（`@RunWith(RobolectricTestRunner::class)`，`RuntimeEnvironment.getApplication()` 取 context）；纯 Kotlin 用例不挂 runner，保持秒级。
- 测试方法名用反引号中文描述被钉住的契约；注释解释「为什么钉住」而非「测了什么」。
- 时间相关逻辑（如 `PipelineSchedule`）以显式入参注入时刻断言，不依赖真实时钟；`ArchiveHttpClient` 的 TTL/去重窗口用 `Thread.sleep` 越窗（仅 2 处，各 ~2s）。
