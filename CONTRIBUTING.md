# 贡献指南

欢迎给 AI News Hub 提 Issue 和 PR 👋

## 提交 Issue

- Bug 请附：复现步骤、预期/实际行为、App 版本、Android 版本。
- 功能建议请说明使用场景，而不只是实现方案。

## 提交 PR

1. Fork → 新建分支（`feat/xxx` / `fix/xxx`），不要直接在 `main` 上开发。
2. 改动后请本地跑通构建：

   ```bash
   ./gradlew assembleDebug
   ```

   项目**无单元测试、无 lint**，编译通过 + 真机手测是主要验证手段。
3. 遵循现有代码约定（详见 [`AGENTS.md`](AGENTS.md)），核心几条：
   - 注释中文、代码/变量名英文。
   - 不引入 Retrofit / Gson / Moshi；网络走 `OkHttp`，JSON 用 `org.json`。
   - 字号走 `AppText.xxx`、透明度走 `AppAlpha.xxx`、颜色只走 `colorScheme`，不散落 `.sp`/`.alpha`/hex 字面量。
   - `OkHttpClient` 统一经 `data/HttpClients.kt` 派生，不各自 new。
4. Commit message 用 Conventional Commits 风格（`feat:` / `fix:` / `refactor:` / `docs:` 等），**一律用英文**，与现有历史一致。
5. PR 描述写清做了什么、为什么、怎么验证。

## 安全相关的注意事项

- 发现安全漏洞请**不要**开公开 Issue，参见 [`SECURITY.md`](SECURITY.md) 私密上报。
- **不要在 PR / Issue / 截图里泄露任何 API Key、密钥、签名密码。**
