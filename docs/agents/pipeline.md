# 数据流水线（scripts/）与 CI/CD

> 面向 AI 编码代理的领域约定，从根 `AGENTS.md` 拆出。动 `scripts/` 下脚本、流水线行为、GitHub Actions 前先读本文。

## 流水线

`pipeline.sh` 是唯一编排入口（CI 与本地都调它）。缺这 4 个环境变量之一直接 `exit 1`：

```
AI_NEWS_HUB_AI_BASE_URL / AI_NEWS_HUB_AI_MODEL / AI_NEWS_HUB_AI_API_KEY / GITCODE_TOKEN
```

- 抓 8 源（7 个第三方站点 + `aihot.virxact.com` 精选 `/items?mode=selected&take=20`）→ 各源 AI 摘要（`ai_summary.py`，写入快照顶层 `ai_summary_v2`，每项含 `title`+`desc`+`url`，url 由 AI 返回的条目编号回填）+ 跨源总览（`overview_summary.py`，写入 `index.json` 顶层 `latest_overview`，含 `digest` 综述 + items；生成成功时**同步落盘 `overview/<date>/<HH-MM>-data.json` 按日归档**，保留 90 天——与快照的 31 天刻意不同档，总览是唯一覆盖即失的 AI 产物，归档即其历史）→ 推送到 gitcode 数据仓库 `peng1818/AI-News-Hub-Data` 的 `news-hub-data` 分支；push 阶段 overlay 后由 `trend_keywords.py` 扫近 14 天历史快照做纯统计词频分析（不调 AI），写入 `index.json` 顶层 `latest_trends`（热词榜，失败只告警不阻断推送；读-改-写，不冲掉其它顶层字段）。
- **历史索引拆在根级独立文件**：`history.json`（摘要历史，每源 31 天）与 `overview_history.json`（总览归档，90 天）由 `build_history` / `build_overview_history` 写出，**不进 index.json**（index 只保留即时字段 updated_at / latest / latest_overview / latest_trends，体量有界）。`load_previous_index` 三个文件都拉：独立文件优先；404 时回退 index 内联字段（拆分上线的过渡迁移，一次即完成）；无内联但仓库已有历史数据（文件意外丢失）→ fail-closed `exit 1`，宁可本轮不更新也不推塌缩索引。
- 单源失败跳过且 `index.json` latest 指针从上一次继承（客户端永远拿到有效数据）；总览 AI 生成失败时 `latest_overview` 同样继承上一次，当日归档不落盘（`overview_history.json` 从上一次索引继承，当天早批次的归档仍可寻址）。≥1 源成功退出码即为 0。日期统一北京时间。
- `backfill_overview.py` 为一次性运维脚本：从数据仓库 git 历史提取旧版 index.json 里的 `latest_overview` 回填成 `overview/` 归档文件 + 重建 `overview_history.json` + 清理已下线源遗留目录（含 `--dry-run` 核对清单）；回填一次即完成，日常流水线自动接力，勿重复跑。`backfill_history.py` 同为运维脚本（重建 `history.json` / `--prune` 删起始日前目录）。
- **Product Hunt `postedAfter` 恒用 PT（太平洋时间）当日 0 点（带时区偏移，`fetch_data.py` 的 `_ph_today_pt_start()`），勿改回 UTC 边界**：PH 把每个帖子的 `createdAt` 规范化到上线日 PT 00:01（夏令时 = UTC 07:01、冬令时 = UTC 08:01）且「Product of the Day」按 PT 自然日排榜；用 UTC 当日 0 点过滤，北京 08:00 批（= UTC 00:00 整）会把当前榜单整体过滤成空、误报「源站改版」，冬令时下 15:30 批（UTC 07:30）同样会拿空。
- 数据格式详见 `docs/news-hub-data-usage.md`；各脚本行为见脚本头注释。

## CI/CD

`.github/workflows/`：`build.yml`（PR 跑 `assembleDebug`）/ `release.yml`（`v*` tag 发版，从 secrets 还原 keystore，versionName/versionCode 从 tag 注入）/ `fetch-data.yml`（每日定时跑数据流水线）。
