# 安全策略

## 报告漏洞

发现安全漏洞请**不要开公开 Issue**，改为私密上报：

- GitHub 私密公告（Security Advisories）：仓库 **Security → Report a vulnerability**

请附：问题描述、影响范围、复现步骤（如适用）、建议修复方向。收到后会在 72 小时内回复确认。

## 支持版本

仅对 [最新 Release](https://github.com/burgessjp/AI-News-Hub/releases) 提供安全更新，旧版本不再维护。

## 已知安全边界

| 资产 | 处理方式 |
|------|----------|
| 用户 AI API Key | 仅存于应用私有目录（DataStore），不进 APK、不进日志、不上报 |
| Release 签名密钥 | 绝不入库；CI 从 GitHub Secrets 还原 |
| 数据流水线 Token（GITCODE_TOKEN / AI Key） | 仅经环境变量注入，代码不硬编码 |
| 网络 | 全域名禁明文流量、仅系统 CA |

## 安全红线（贡献者必读）

- 任何 PR 不得硬编码 API Key、Token、密码、签名材料。
- 不得为调试放开明文流量（`network_security_config.xml`）。
- 不得把 `*.jks` / `keystore.properties` / `local.properties` 提交入库。
