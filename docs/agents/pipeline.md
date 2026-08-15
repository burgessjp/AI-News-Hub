# 数据流水线（scripts/）与 CI/CD

> 面向 AI 编码代理的领域约定，从根 `AGENTS.md` 拆出。动 `scripts/` 下脚本、流水线行为、GitHub Actions 前先读本文。

## 流水线

`pipeline.sh` 是唯一编排入口（CI 与本地都调它）。缺这 4 个环境变量之一直接 `exit 1`：

```
AI_NEWS_HUB_AI_BASE_URL / AI_NEWS_HUB_AI_MODEL / AI_NEWS_HUB_AI_API_KEY / GITCODE_TOKEN
```

- 抓 8 源（7 个第三方站点 + `aihot.virxact.com` 精选 `/items?mode=selected&take=20`）→ 各源 AI 摘要（`ai_summary.py`，写入快照顶层 `ai_summary_v2`，每项含 `title`+`desc`+`url`，url 由 AI 返回的条目编号回填）+ 跨源总览（`overview_summary.py`，写入 `index.json` 顶层 `latest_overview`，含 `digest` 综述 + items；生成成功时**同步落盘 `overview/<date>/<HH-MM>-data.json` 按日归档**，保留 90 天——与快照的 31 天刻意不同档，总览是唯一覆盖即失的 AI 产物，归档即其历史）→ 推送到 gitcode 数据仓库 `peng1818/AI-News-Hub-Data` 的 `news-hub-data` 分支；push 阶段 overlay 后由 `trend_keywords.py` 扫近 14 天历史快照做词频统计（**统计为主 + 每批至多一次 AI 精修**），写根级独立文件 `trends.json`（整文件覆盖，失败只告警不阻断推送、下次自愈；统计部分可全量重算，AI 精修失败的批次退回统计回退榜，均无继承语义），并**同步落盘 `trends/<date>/<HH-MM>-data.json` 按日归档**（索引 `trends_history.json`，90 天指针，对齐总览归档档位；归档/索引失败仅告警，根级 `trends.json` 已写仍算成功）＋ 依据「昨日最后一期」归档为每个热词附上排名变化字段 `rankChange` / `isNewEntry`（App 显示 +N/-N/持平/新上榜；基准必须在写今日归档前取，否则会把今日早批当基准；无历史基准时整个字段不输出）。
- **趋势榜单规则**（`trend_keywords.py`）：入榜门槛不变（窗口期 `total ≥ 3` 且 `daysActive ≥ 2`）；排序按**动量加权分**（近 7 日命中和 × min(动量比, 2.5)，动量比 = (近3日+1)/(前3日+1)——纯 total 排序是热度榜，常青词常年霸榜头部固化）；非别名归一的**自由 unigram 不直接入榜**（拆词残留 work/long 之类只能活在 bigram 里或经 AI 捞回，按分值序补位到满 10 个，榜单长度恒定）；`trend` 带 ±max(1, 前3日×15%) 容差带（无容差时 101 vs 100 也标 up，箭头近似噪声）；**AI 精修**每批把候选池（按分值序 top 25，宽口径含被护栏拦下的词）喂给 LLM 合并同话题候选 / 剔除泛词 / 规范 display（可为中文），选不满 10 个时按分值序用统计候选补齐，配置缺失、调用失败、校验不过一律零降级回退统计回退榜，不告警不阻断；排序语义切换后的首批 `rankChange` 会有一次性全榜跳变（归档是成品快照，不受影响）。
- **历史索引与趋势拆在根级独立文件**：`history.json`（摘要历史，每源 31 天）与 `overview_history.json`（总览归档，90 天）由 `build_history` / `build_overview_history` 写出，`trends.json` 与 `trends_history.json` 由 `write_trends` 写出，**均不进 index.json**（index 只保留即时字段 updated_at / latest / latest_overview，体量有界）。`load_previous_index` 只拉前三个文件（fetch 阶段合并基线）；`trends_history.json` 不走它——push 阶段 clone 里就有全量历史，`write_trends` 直接从 checkout 读。独立文件优先；404 时回退 index 内联字段（拆分上线的过渡迁移，一次即完成）；无内联但仓库已有历史数据（文件意外丢失）→ fail-closed `exit 1`，宁可本轮不更新也不推塌缩索引。
- 单源失败跳过且 `index.json` latest 指针从上一次继承（客户端永远拿到有效数据）；总览 AI 生成失败时 `latest_overview` 同样继承上一次，当日归档不落盘（`overview_history.json` 从上一次索引继承，当天早批次的归档仍可寻址）。≥1 源成功退出码即为 0。日期统一北京时间。
- `backfill_overview.py` 为一次性运维脚本：从数据仓库 git 历史提取旧版 index.json 里的 `latest_overview` 回填成 `overview/` 归档文件 + 重建 `overview_history.json` + 清理已下线源遗留目录（含 `--dry-run` 核对清单）；回填一次即完成，日常流水线自动接力，勿重复跑。`backfill_trends.py` 同为一次性运维脚本（从 git 历史提取各期 `trends.json`／拆分前 index.json 内联 `latest_trends`，回填 `trends/` 归档 + 重建 `trends_history.json`；一律完整重克隆 `repo/` 后自写自推，需 `GITCODE_TOKEN`）。`backfill_history.py` 同为运维脚本（重建 `history.json` / `--prune` 删起始日前目录）。
- **Product Hunt `postedAfter` 恒用 PT（太平洋时间）当日 0 点（带时区偏移，`fetch_data.py` 的 `_ph_today_pt_start()`），勿改回 UTC 边界**：PH 把每个帖子的 `createdAt` 规范化到上线日 PT 00:01（夏令时 = UTC 07:01、冬令时 = UTC 08:01）且「Product of the Day」按 PT 自然日排榜；用 UTC 当日 0 点过滤，北京 08:00 批（= UTC 00:00 整）会把当前榜单整体过滤成空、误报「源站改版」，冬令时下 15:30 批（UTC 07:30）同样会拿空。
- 数据格式详见 `docs/news-hub-data-usage.md`；各脚本行为见脚本头注释。

## CI/CD

`.github/workflows/`：`build.yml`（PR 跑 `assembleDebug`）/ `release.yml`（`v*` tag 发版，从 secrets 还原 keystore，versionName/versionCode 从 tag 注入）/ `fetch-data.yml`（每日定时跑数据流水线）。
