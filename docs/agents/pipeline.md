# 数据流水线（scripts/）与 CI/CD

> 面向 AI 编码代理的领域约定，从根 `AGENTS.md` 拆出。动 `scripts/` 下脚本、流水线行为、GitHub Actions 前先读本文。

## 流水线

`pipeline.sh` 是唯一编排入口（CI 与本地都调它）。缺这 4 个环境变量之一直接 `exit 1`：

```
AI_NEWS_HUB_AI_BASE_URL / AI_NEWS_HUB_AI_MODEL / AI_NEWS_HUB_AI_API_KEY / GITCODE_TOKEN
```

- 抓 8 源（7 个第三方站点 + `aihot.virxact.com` 精选 `/items?mode=selected&take=20`）→ 各源 AI 摘要（`ai_summary.py`，写入快照顶层 `ai_summary_v2`，每项含 `title`+`desc`+`url`，url 由 AI 返回的条目编号回填）+ 跨源总览（`overview_summary.py`，写入 `index.json` 顶层 `latest_overview`，含 `digest` 综述 + items）→ 推送到 gitcode 数据仓库 `peng1818/AI-News-Hub-Data` 的 `news-hub-data` 分支；push 阶段 overlay 后由 `trend_keywords.py` 扫近 14 天历史快照做纯统计词频分析（不调 AI），写入 `index.json` 顶层 `latest_trends`（热词榜，失败只告警不阻断推送）。
- 单源失败跳过且 `index.json` latest 指针从上一次继承（客户端永远拿到有效数据）；总览 AI 生成失败时 `latest_overview` 同样继承上一次。≥1 源成功退出码即为 0。日期统一北京时间。
- 数据格式详见 `docs/news-hub-data-usage.md`；各脚本行为见脚本头注释。

## CI/CD

`.github/workflows/`：`build.yml`（PR 跑 `assembleDebug`）/ `release.yml`（`v*` tag 发版，从 secrets 还原 keystore，versionName/versionCode 从 tag 注入）/ `fetch-data.yml`（每日定时跑数据流水线）。
