# 发布流程

本文档说明 AI News Hub 的版本发布机制与操作步骤。发版由推送 `v*` tag 自动触发 CI,无需手动构建或上传。

## 机制总览

| 项 | 说明 |
|---|---|
| 触发方式 | 推送 `v*` 格式的 tag(如 `v1.0.1`)到 origin |
| 执行器 | `.github/workflows/release.yml`,ubuntu-latest,30 分钟超时 |
| versionName | tag 去掉 `v` 前缀(`v1.0.1` → `1.0.1`) |
| versionCode | 公式 `MAJOR*10000 + MINOR*100 + PATCH`(`v1.0.1` → `10101`) |
| 产物 | 签名 release APK,自动发布到 GitHub Release |
| Release notes | GitHub 自动从上一 tag 到当前 tag 间的合并 PR 生成(`generate_release_notes: true`) |

> ⚠️ **versionCode 公式限制**:MINOR 与 PATCH 各占两位,上限均为 99。`v1.0.99` → `10099` 没问题,但 `v1.0.100` 会与 `v1.1.0` 冲突(`10100`)。当前离上限很远,正常迭代无需担心。

versionCode / versionName 的注入见 `app/build.gradle.kts` 的 `defaultConfig`:

```kotlin
// 版本号默认 1.2.3(10203),发版时同步此兜底值;release.yml 从 tag 经 -PversionName/-PversionCode 注入
versionCode = (findProperty("versionCode") as? String)?.toIntOrNull() ?: 10203
versionName = findProperty("versionName") as? String ?: "1.2.3"
```

本地 debug 构建与 `build.yml` 的 PR 构建走上述兜底值(每次发版时同步),**tag 触发的 CI 注入 tag 计算的真实版本号,覆盖兜底**。

## 前置条件

### GitHub Secrets(一次性配置)

在仓库 **Settings → Secrets and variables → Actions** 配置 4 个 secret,v1.0.0 发过即已满足:

| Secret | 值 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | 本地 `app/release.jks` 的 base64(`base64 -i app/release.jks`) |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | key 别名 |
| `RELEASE_KEY_PASSWORD` | key 密码 |

### 本地签名材料(仅本地构建需要)

- `keystore.properties` 放在工程根目录(已 gitignore),含 `storeFile`/`storePassword`/`keyAlias`/`keyPassword` 四项
- `app/release.jks` 放在 app 模块下(已 gitignore)
- CI 不依赖本地文件,从 secrets 还原 keystore

> 签名密钥绝不入库(`*.jks`、`*.keystore`、`keystore.properties` 均 gitignore)。详见 `AGENTS.md`「安全红线」。

## 标准发版步骤

以发布 `v1.0.1` 为例。

**1. 确认基线干净且最新**

```bash
git checkout main
git pull origin main
```

**2. 整理 CHANGELOG.md**

在 `[Unreleased]` 与上一版本之间插入新版本区块,只写用户能直接感知的改动:

```markdown
## [1.0.1] - 2026-08-08

### 新增

- **应用内双语(简体中文 / English)** — ...

### 改进

- **翻译入口提升为主操作** — ...
- **改用系统默认字体** — ...

### 修复

- **切换主题时刷新系统状态栏样式** — ...
```

约定:
- 条目只保留**终端用户能直接感知**的功能增改,工程化/测试/文档类改动不写
- 顺手核对上一版本条目状态(如 `[1.0.0] - 未发布` 实际已发版,应改为真实日期)
- 若改动涉及数据源模式 / 流水线行为 / 导航机制,按 `AGENTS.md`「维护」小节约定同步更新 `AGENTS.md`

**2.1 同步 build.gradle.kts 兜底版本号**

把 `app/build.gradle.kts` defaultConfig 中的兜底 `versionCode`/`versionName` 更新为本次发版值(如 `10201`/`"1.2.1"`),让本地 debug 构建与 PR 构建也显示当前版本。

**3. 提交**

```bash
git add CHANGELOG.md app/build.gradle.kts   # 若有 AGENTS.md / docs 同步改动一并 add
git commit -m "docs(release): prepare v1.0.1"
```

**4. 打 tag**

```bash
git tag v1.0.1
```

**5. 推送 main 与 tag**

```bash
git push origin main
git push origin v1.0.1
```

推送 tag 后 `release.yml` 自动触发,无需人工干预。

## release.yml 自动完成的部分

推送 `v*` tag 后,CI 按顺序执行:

1. **还原签名 keystore** — 从 secrets 解码 `RELEASE_KEYSTORE_BASE64` 写入 `app/release.jks`,用 heredoc 单引号生成 `keystore.properties`(防密码中的 `$` / 反引号被 bash 二次展开)
2. **从 tag 计算 versionName / versionCode** — 写入 `$GITHUB_ENV`
3. **构建签名 APK** — `./gradlew assembleRelease -PversionName=... -PversionCode=...`
4. **创建 GitHub Release** — `softprops/action-gh-release@v2`,附上 `app/build/outputs/apk/release/app-release.apk`,release notes 由 GitHub 自动生成

产物**只发布到 GitHub Release**,不上传任何应用商店。

## 发版验证清单

推送 tag 后核对:

- [ ] 仓库 **Actions** 页:`release.yml` 工作流跑成绿灯
- [ ] 仓库 **Releases** 页:`v1.0.1` 已创建、`app-release.apk` 已附上
- [ ] 下载 APK 安装到真机,「设置 → 关于」确认版本号显示为 `1.0.1`

## 本地构建 release(可选)

用于发版前在本地验证签名构建是否正常(需本地 `keystore.properties` + `app/release.jks`):

```bash
# 临时注入版本号,模拟 CI 的 tag 注入
./gradlew assembleRelease -PversionName=1.0.1 -PversionCode=10101
# 产物:app/build/outputs/apk/release/app-release.apk
```

## 常见问题

**推送 tag 时 SSH 连接被中断(`Connection closed by 198.18.0.19 port 22`)**
本地代理(如 Clash/Surge 的 fake-ip)对 github.com 的 SSH 流量拦截。解法:放行代理对 github.com 的 SSH 规则,或改用 HTTPS + token(`git remote set-url origin https://<token>@github.com/burgessjp/AI-News-Hub.git`,推送后改回)。

**versionCode 没生效,装出来还是 1**
`build.gradle.kts` 用 `findProperty(...) as? String` 读版本号,CI 必须以字符串形式传参(带引号:`-PversionCode="$vc"`),release.yml 已正确处理。本地手动跑若去掉引号可能失效。

**Release 创建失败 / 权限不足**
`release.yml` 需 `permissions: contents: write`(已在 workflow 内声明)。若 fork 仓库发版,确认 fork 仓库的 Actions 有写权限。
