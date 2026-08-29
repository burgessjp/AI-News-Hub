# 测试

> 面向 AI 编码代理的领域约定，从根 `AGENTS.md` 拆出。新增/改动解析器、纯逻辑、导航序列化、流水线脚本前先读本文。

## 现状与验证闭环

- `app/src/test`（JVM 单测，JUnit4）与 `scripts/tests`（流水线 pytest，2026-08-29 起）是当前两个自动化测试层；**无 androidTest 仪器测试、无 Compose UI 测试**（暂不要求）。
- App 侧改动后跑 `./gradlew testDebugUnitTest`，配合 `./gradlew assembleDebug` 构成验证闭环；CI（build.yml）PR 时先跑单测再打 APK，并行 `pipeline-tests` job 跑流水线 pytest（不在每日 fetch-data.yml 里跑，理由见 docs/agents/pipeline.md「测试」）。
- App 依赖（`gradle/libs.versions.toml`，全部 testImplementation 不进 APK）：`junit`、`kotlinx-coroutines-test`、`mockwebserver`（OkHttp 同族）、`robolectric`、`org-json`（纯 JVM 用例的 org.json 实现，替代 android.jar 桩）。
- 流水线依赖（`scripts/requirements-dev.txt`，不进生产运行环境）：`pytest`、`requests-mock`；本地跑 `scripts/.venv/bin/python -m pytest scripts`（CI 直接 `pytest scripts`，rootdir 由 `scripts/pytest.ini` 锚定）。

## 哪些层必须覆盖（按优先级）

1. **数据解析器**：各模型 companion `fromJson`（内联 JSON 样例，钉住 asClean 语义与主键缺失跳过规则）；归档快照结构经 `ArchiveHttpClientTest` 端到端覆盖。被测对象删除时其测试随之删除（例：LIVE 模式删除时 HtmlParsersTest 一并退场）。
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

## Python 侧（scripts/tests/，流水线）

- **布局**：`scripts/tests/` 按「被测对象_层次」分文件（`test_common` / `test_fetch_pure` 纯函数 / `test_fetch_io` 落盘扫描 / `test_fetch_index` 继承语义核心 / `test_fetch_main` 端到端 / `test_sources_registry` 注册表护栏——SOURCES 序与 SOURCE_KEYS 集合相等、EMPTY_OK 子集、fetch_with_retry 契约冒烟）；fixtures 复用同一套裁剪规约（真实批次、latest 指针保真、items ≤5），来源 `out/` 本地产物或 `repo/` 克隆——**绝不把 `repo/` 的 remote URL 写进测试或 fixture（内嵌 token）**。
- **conftest 惯例**：`frozen_now` 钉到 2026-08-29 11:01（与 fixtures 批次同刻），一切「快照目录日期 / fetched_at / updated_at」断言才有确定值——**patch 面是包迭代**：`fetch_data` + `sources` 包内各子模块凡绑定了 `now_cst` 的一并替换（抓取器按源拆包后，新增源模块自动进入 patch 面，不必记得改 conftest）；`no_retry_backoff` 把 `fetch_data.retry` 换直调版跳过 2s/4s 退避（retry 的调用方全在 fetch_data.py 编排/索引层，单点 patch 即可）。**注意 patch 的是各模块命名空间里 `from common import` 的绑定，不是 `common.retry` / `common.now_cst`**。
- **网络 mock**：全部 HTTP 走 `sources/httpio.py` 的 `fetch_text` + 模块级 `SESSION` 单一咽喉（Product Hunt 的 POST 也走它），`requests-mock` 的 `requests_mock` fixture 直接拦截；404/500 × 内联字段有无的矩阵即 `load_previous_index` 的迁移阶梯回归。真实重试路径（`on_exhausted` → fail-closed）要保留真 `retry`，只 patch `time.sleep`。
- **main() 端到端**：`monkeypatch.setattr(sys, "argv", [...])` 注入参数、`fd.SOURCES` 换 stub 源（hackernews 的 stub 签名须兼容 `limit=` 关键字）、断言 `main()` 返回值（0/1/2）+ `tmp_path` 产物（index/manifest/快照路径）。`--no-summary` 跳过 AI 两阶段，无 AI key 依赖。
- 测试名与注释惯例与 App 侧一致：中文直述被钉住的契约。`trend_keywords.py` / `push_data.py` / `tts_broadcast.py` 尚未覆盖（后续按同一标准补）。
