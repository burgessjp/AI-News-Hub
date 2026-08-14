# AI-News-Hub-Data 数据用法

数据仓库:[gitcode.com/peng1818/AI-News-Hub-Data](https://gitcode.com/peng1818/AI-News-Hub-Data)
分支:`news-hub-data`

本仓库定时抓取 [AI News Hub](../) App 浏览区域(更多 → 信息源)的 8 个数据源(7 个第三方站点源 + AIHot 精选 TOP20,后者来自第三方服务 aihot.virxact.com),解析成 JSON 后按日期归档。本文档说明数据结构、获取方式与消费示例。

## 更新频率

- **定时**:每天多个批次(北京时间 08:00 / 15:30 / 18:00)。其中 15:30 批由本仓库 GitHub Actions 触发(cron 不保证准点,通常 ±15 分钟内);08:00 / 18:00 批由仓库外机器调度,不在本仓库 workflow 内
- **手动**:workflow 支持 `workflow_dispatch`,可随时手动触发补抓
- 任何源抓取失败会自动重试最多 3 次(间隔 2s/4s);3 次全败才跳过,不影响其余源
- 失败源的 `index.json` latest 指针会保留上一次成功的指向(见下「失败保留机制」)

## 仓库结构

```
news-hub-data 分支/
├── index.json                          ← 入口:各源最新快照路径(latest)+ 按日期历史索引(history)
├── manifest.json                       ← 最近一次运行总览(成功/失败状态)
├── hackernews/
│   ├── 2026-07-14/
│   │   └── 08-00-data.json
│   └── 2026-07-15/
│       └── 08-00-data.json
├── github-trending/
│   └── 2026-07-15/
│       └── 08-00-data.json
├── stormzhang-ai/
│   └── 2026-07-15/
│       └── 08-00-data.json
├── huggingface-papers/
│   └── 2026-07-15/
│       └── 08-00-data.json
├── producthunt/                         ← 需 PRODUCT_HUNT_KEY;token 失效时可能指向旧日期
│   └── 2026-07-15/
│       └── 08-00-data.json
├── rundown-ai/                          ← The Rundown AI newsletter 首页文章卡片墙(无 token)
│   └── 2026-07-15/
│       └── 08-00-data.json
└── aihot-featured/                      ← AIHot 精选(第三方服务 aihot.virxact.com /items?mode=selected&take=20,公开 API,无 token)
    └── 2026-07-15/
        └── 08-00-data.json
```

路径规则:`<源名>/<YYYY-MM-DD>/<HH-MM>-data.json`,日期与时间均为**北京时间(UTC+8)**。

## 快速开始:拉最新数据

**第一步:读 `index.json` 拿到各源最新路径。**

```json
{
  "updated_at": "2026-07-15T08:00:12+0800",
  "updated_at_ms": 1784073612000,
  "latest": {
    "hackernews": "2026-07-15/08-00-data.json",
    "github-trending": "2026-07-15/08-00-data.json",
    "stormzhang-ai": "2026-07-15/08-00-data.json",
    "huggingface-papers": "2026-07-15/08-00-data.json",
    "producthunt": "2026-07-15/08-00-data.json",
    "rundown-ai": "2026-07-15/08-00-data.json",
    "aihot-featured": "2026-07-15/08-00-data.json",
    "openai-anthropic-news": "2026-07-15/08-00-data.json"
  },
  "history": {
    "hackernews": {
      "2026-07-19": "2026-07-19/10-12-data.json",
      "2026-07-18": "2026-07-18/15-00-data.json"
    }
  },
  "latest_overview": {
    "generatedAt": 1784073612000,
    "dataFetchedAt": 1784073600000,
    "missingSources": ["openai-anthropic-news"],
    "digest": "2-3 句跨源今日综述",
    "items": [
      {
        "source": "hackernews",
        "title": "...", "url": "...", "metrics": "得分 630 · 评论 61",
        "comment": "AI 写的一句话重要性",
        "breaking": false, "breakingReason": ""
      }
    ]
  },
  "latest_trends": {
    "generatedAt": 1786277998615,
    "windowDays": 14,
    "days": ["2026-07-27", "...", "2026-08-09"],
    "keywords": [
      {
        "term": "agent", "display": "Agent",
        "total": 365, "daysActive": 14,
        "daily": [25, 29, "...", 24],
        "trend": "up",
        "items": [{"title": "...", "url": "...", "source": "hackernews", "date": "2026-08-09"}]
      }
    ]
  }
}
```

`latest` 里的路径是**相对于源目录**的。设计行为:某源当天抓取失败时,`index.json` 会保留它最后一次成功的指向(可能落在前一天),客户端永远能拿到有效数据。

`history` 是按日期寻址的历史索引(`{源名: {日期: relpath}}`,上例仅示意一个源,实际每源都有;只收录 2026-07-18 起的日期),详见下文「按日期取历史快照」。

`latest_overview` 是**今日总览**(流水线预生成的跨源综合分析,详见下文「今日总览 latest_overview」)。App 首页「总览」tab 直接读这个字段,不再端侧调 AI。

`latest_trends` 是**热词趋势榜**(流水线对近 14 天快照的纯统计词频分析,不调 AI,详见下文「热词趋势 latest_trends」)。App「趋势」tab 直接读这个字段。

**第二步:拼完整路径拉数据。** `index.latest.<源>` 前面加上 `<源>/` 即得完整路径。

### 文件直链(URL 模板)

**推荐用 gitcode 官方 REST API**(稳定,公开仓库匿名可读):

```
https://api.gitcode.com/api/v5/repos/peng1818/AI-News-Hub-Data/raw/<完整路径>?ref=news-hub-data
```

例如拉 2026-07-15 的 hackernews:

```
https://api.gitcode.com/api/v5/repos/peng1818/AI-News-Hub-Data/raw/hackernews/2026-07-15/08-00-data.json?ref=news-hub-data
```

也支持 raw 直链 `https://raw.gitcode.com/peng1818/AI-News-Hub-Data/raw/news-hub-data/<路径>`,但 raw 背后是华为云 WAF,部分网络环境(数据中心 IP)会被拦 403,**优先用 API 端点**。

> 📌 **API 返回 Content-Type 为 `application/octet-stream`**(非 `application/json`)。多数客户端按响应体内容解析 JSON 不受影响;若用严格按响应头判断类型的封装,需手动按 JSON 解析。

### 消费示例

**JavaScript / 浏览器:**

```javascript
const BASE = 'https://api.gitcode.com/api/v5/repos/peng1818/AI-News-Hub-Data/raw'
const REF = 'news-hub-data'

// 1. 读 index(API 返回 application/octet-stream,.json() 按内容解析无碍)
const index = await fetch(`${BASE}/index.json?ref=${REF}`).then(r => r.json())

// 2. 拼完整路径拉某源最新数据
async function getLatest(source) {
  const rel = index.latest[source]            // "2026-07-15/08-00-data.json"
  const r = await fetch(`${BASE}/${source}/${rel}?ref=${REF}`)
  return r.json()
}

const hn = await getLatest('hackernews')
console.log(hn.items[0].title)
```

**Python:**

```python
import requests

BASE = 'https://api.gitcode.com/api/v5/repos/peng1818/AI-News-Hub-Data/raw'
REF = 'news-hub-data'

index = requests.get(f'{BASE}/index.json', params={'ref': REF}, timeout=15).json()

def get_latest(source):
    rel = index['latest'][source]
    return requests.get(f'{BASE}/{source}/{rel}', params={'ref': REF}, timeout=15).json()

hn = get_latest('hackernews')
print(hn['items'][0]['title'])
```

## 按日期取历史快照(history 索引)

`index.json` 顶层除 `latest` 外还有 `history` 字段,结构为 `{源名: {日期: relpath}}`:

```json
{
  "history": {
    "hackernews": {
      "2026-07-19": "2026-07-19/10-12-data.json",
      "2026-07-18": "2026-07-18/15-00-data.json"
    }
  }
}
```

- **日期 → 当日最后一次快照**:键为北京时间日期(`YYYY-MM-DD`);一天抓两次时,当天较早的那份不进索引。
- **每源只保留最近 31 天,且不早于 2026-07-18**(历史摘要功能起始日;更早的快照源覆盖不全,日期目录已从仓库删除——`backfill_history.py --prune` 执行,推送的 `_overlay` 只增不删,删除只能显式做)。
- `relpath` 与 `latest` 一样是**相对于源目录**的,消费方式相同:拼上 `<源>/` 前缀后走 gitcode raw API(见上「文件直链」),如 `hackernews/2026-07-19/10-12-data.json`。
- **用途**:App「历史摘要」按日期查看当日快照与 `ai_summary_v2`;其它消费方亦可据此按日回溯。

## 今日总览(latest_overview 字段)

`index.json` 顶层还有 `latest_overview` 字段——**今日总览**,流水线在抓取后做跨源综合分析预生成,App 首页「总览」tab 直接读这个字段(不再端侧调 AI)。

```json
{
  "latest_overview": {
    "generatedAt": 1784073612000,
    "dataFetchedAt": 1784073600000,
    "missingSources": ["openai-anthropic-news"],
    "digest": "今天的主线集中在……",
    "items": [
      {
        "source": "hackernews",
        "title": "PGSimCity - How PostgreSQL Works",
        "url": "https://...",
        "metrics": "得分 630 · 评论 61",
        "comment": "AI 写的一句话重要性",
        "breaking": false,
        "breakingReason": ""
      }
    ]
  }
}
```

| 字段 | 说明 |
|------|------|
| `generatedAt` | 流水线生成时刻,Unix 毫秒时间戳 |
| `dataFetchedAt` | 输入快照里最大的 `fetched_at_ms`(「数据截至」) |
| `missingSources` | 本次生成时未能加载的源 key 数组(页脚标注用) |
| `digest` | 跨源「今日综述」(2-3 句简体中文,≤120 字,AI 生成)。可能为空串(AI 未返回/旧数据无此字段),消费方空串不渲染 |
| `items` | 今日热点 Top10,breaking 条目排最前。每项含 `source`/`title`/`url`/`metrics`(从快照回填的最终值) + `comment`(AI 写的一句话) + `breaking` + `breakingReason`(仅 breaking=true 有) |

**与单源 `ai_summary_v2` 的区别**:`ai_summary_v2` 是各源快照内的分源要点(8 个独立摘要);`latest_overview` 是跨 8 源的综合研判(1 个总榜),AI 会按跨源归一化热度档位排序、合并同事件、标 breaking。

**失败保留**:本次总览 AI 生成失败时,`latest_overview` 继承上一次的值(同 `latest` 指针的失败保留机制),避免一次失败导致 App 端总览空掉。字段完全缺失时,App 走「今日总览尚未生成」空态。

## 热词趋势(latest_trends 字段)

`index.json` 顶层还有 `latest_trends` 字段——**跨源热词趋势榜**,流水线(`scripts/trend_keywords.py`)在 push 阶段扫近 14 天各源快照做**纯统计词频分析**(不调 AI、确定性结果),App「趋势」tab 直接读这个字段。

```json
{
  "latest_trends": {
    "generatedAt": 1786277998615,
    "windowDays": 14,
    "days": ["2026-07-27", "2026-07-28", "...", "2026-08-09"],
    "keywords": [
      {
        "term": "gpt-5",
        "display": "GPT-5",
        "total": 87,
        "daysActive": 9,
        "daily": [0, 3, 5, "...", 12],
        "trend": "up",
        "items": [
          {"title": "...", "url": "...", "source": "hackernews", "date": "2026-08-09"}
        ]
      }
    ]
  }
}
```

| 字段 | 说明 |
|------|------|
| `generatedAt` | 流水线生成时刻,Unix 毫秒时间戳 |
| `windowDays` | 统计窗口天数(当前 14) |
| `days` | 窗口内日历日期序列(yyyy-MM-dd,北京时间,连续 WINDOW_DAYS 天;缺数据的日期命中计 0),与各 keyword 的 `daily` 按下标对齐 |
| `keywords` | 热词榜,按 `total` 降序 ≤10 个。入榜门槛:窗口期 `total ≥ 3` 且 `daysActive ≥ 2` |

`keywords[]` 单项:

| 字段 | 说明 |
|------|------|
| `term` | 归一化 canonical key(小写,如 `gpt-5`) |
| `display` | 展示形(内置 AI 实体映射表指定形,如 `GPT-5`;或语料中最常见的原始大小写写法) |
| `total` | 窗口期总命中次数(按「条目命中」计:一个词在一条条目中出现算 1 次) |
| `daysActive` | 窗口期活跃天数(当日命中 ≥1 即活跃) |
| `daily` | 每日命中序列,与 `days` 对齐(sparkline 数据源) |
| `trend` | 涨跌标记:`up` / `down` / `flat`(近 3 日命中和 vs 前 3 日) |
| `items` | ≤3 条代表条目(日期新的优先,按 URL 去重,源尽量多样),每项含 `title`/`url`/`source`/`date` |

**统计口径**:文本取自各源条目标题/简介(aihot-featured 优先 `titleEn`,stormzhang-ai 优先 `english` 并截掉 "PLUS:" 赞助尾巴);英文按 `[a-z0-9]+` 分词去停用词后取 unigram + 相邻 bigram,内置 AI 实体别名表做归一(GPT-5/OpenAI/Claude/千问 等);中文不分词,只对别名表内含 CJK 的词做子串匹配。

**失败语义**:趋势生成失败不阻断推送,当次 `index.json` 暂缺该字段(下次运行自愈);字段完全缺失时 App 走「热词趋势尚未生成」空态。

## 单个数据文件的通用结构

每个 `<HH-MM>-data.json` 顶层结构相同:

```json
{
  "source": "github-trending",
  "fetched_at": "2026-07-15T08:00:12+0800",
  "fetched_at_ms": 1784073612000,
  "count": 25,
  "items": [ ... ],
  "ai_summary_v2": [
    { "title": "owner/name(价值定位)", "desc": "... 中文 AI 要点 ...", "url": "https://..." },
    { "title": "...", "desc": "...", "url": "..." }
  ]
}
```

| 字段 | 说明 |
|------|------|
| `source` | 源标识,同目录名 |
| `fetched_at` | 抓取时刻,ISO 8601 带时区(北京时间) |
| `fetched_at_ms` | 抓取时刻,Unix 毫秒时间戳 |
| `count` | `items` 数组长度 |
| `items` | 该源的条目数组,结构因源而异(见下) |
| `ai_summary_v2` | 本次数据的简体中文 AI 要点,JSON 数组(6-10 个对象,每个含 `title` 加粗导语 + `desc` 2-3 句正文 + `url` 对应原始条目链接)。`url` 由数据侧按 AI 返回的条目编号回填(AI 不输出 URL);编号无效时为空串,消费方应把空串条目按只读处理(App 端即不可点)。8 个稳定源都有(hackernews / github-trending / openai-anthropic-news / huggingface-papers / stormzhang-ai / producthunt / rundown-ai / aihot-featured);AI 调用失败时该字段缺省。**新快照只写 `ai_summary_v2`**;旧快照仅有 `ai_summary`(纯文本 `• **标题**：描述` 串),App 兼容回退 |

部分源会有额外顶层字段(如 stormzhang-ai 带 `pageDate`)。

## 各源 items 字段说明

### hackernews(HackerNews Top Stories)

HackerNews 热门榜,取前 20 条。两步拉取:先取 topstories id 数组,再并发拉每条详情。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | int | 故事 id |
| `title` | string | 标题 |
| `url` | string | 外链原文地址;Ask HN 等文本帖为空 |
| `by` | string | 作者 |
| `score` | int | 得分 |
| `descendants` | int | 评论数 |
| `time` | int | 发布时刻,Unix 秒 |
| `time_iso` | string | 发布时刻,ISO 8601(UTC) |
| `discussion_url` | string | HN 讨论页 `https://news.ycombinator.com/item?id=<id>` |
| `target_url` | string | `url` 非空时为 `url`,否则为 `discussion_url`(点击落地页) |

### github-trending(GitHub Trending 仓库)

GitHub Trending 日榜,默认 daily 窗口。

| 字段 | 类型 | 说明 |
|------|------|------|
| `rank` | int | 排名(1 起,由页面位置决定) |
| `owner` | string | 仓库所有者 |
| `name` | string | 仓库名 |
| `url` | string | 仓库完整地址 `https://github.com/<owner>/<name>` |
| `description` | string | 一句话描述 |
| `language` | string | 主语言;无则为空 |
| `languageColor` | string | 语言色点十六进制(如 `#3178c6`);无则为空 |
| `totalStars` | int | 累计 star 数 |
| `forks` | int | fork 数 |
| `starsToday` | int | 今日新增 star 数(daily 窗口) |

### stormzhang-ai(stormzhang AI 资讯)

stormzhang AI Daily 每日资讯聚合(中文摘要 + 英文原文),聚合 Hacker News / Reddit / Product Hunt / The Rundown AI / TLDR AI 等信源。

顶层额外字段:`pageDate`(string,页面声明的资讯日期,如 `2026.07.15`,取自页面 title,可能为空)。

| 字段 | 类型 | 说明 |
|------|------|------|
| `rank` | int | 排名(取自页面 `.item-index`) |
| `url` | string | 资讯原文完整 HTTPS 地址 |
| `summary` | string | 中文摘要(AI 生成,主标题) |
| `english` | string | 英文原文一句话;部分条目为空 |
| `source` | string | 来源信源名,如 `Hacker News` / `Reddit` / `Product Hunt` / `The Rundown AI` / `TLDR AI` |
| `time` | string | 发布时间原文,如 `2026-07-15 20:00` |

### huggingface-papers(HuggingFace Trending Papers)

HuggingFace Trending Papers(AK 每日精选 arXiv 论文,按社区 upvote 排序)。

| 字段 | 类型 | 说明 |
|------|------|------|
| `rank` | int | 排名(1 起,由列表位置决定) |
| `id` | string | 论文 id,即 arXiv 编号,如 `2403.08299` |
| `url` | string | 论文页地址 `https://huggingface.co/papers/<id>` |
| `title` | string | 论文标题 |
| `summary` | string | 一句话摘要;可能为空 |
| `upvotes` | int | 社区 upvote 数(热度主指标) |
| `published` | string | 发布日期原文,如 `Jul 8, 2026` |
| `authors` | string | 作者信息,如 `5 authors`;取不到则为空 |
| `githubUrl` | string | 关联 GitHub 仓库地址;无则为空 |

### producthunt(Product Hunt 当日热门)

Product Hunt 当日(Product of the Day 语义)按 upvote 排序的热门产品,取前 20。走 V2 GraphQL API(`api.producthunt.com/v2/api/graphql`,`posts(first:20, order:VOTES, postedAfter: 当日UTC0点)`),需 Developer Token(`PRODUCT_HUNT_KEY`)。

> ⚠️ **token 失效时该源失败**:`PRODUCT_HUNT_KEY` 缺失或 401/403 时该源 3 次重试后跳过,`index.latest.producthunt` 保留上一次成功指向(失败保留机制见下)。token 在 Product Hunt API Dashboard 重新生成即可恢复。

| 字段 | 类型 | 说明 |
|------|------|------|
| `rank` | int | 排名(1 起,由列表位置决定) |
| `id` | string | 产品 id(GraphQL Post.id,字符串形态数字) |
| `slug` | string | 产品 slug |
| `name` | string | 产品名 |
| `tagline` | string | 一句话价值定位;可能为空 |
| `votesCount` | int | 社区 upvote 数(热度主指标) |
| `commentsCount` | int | 评论数 |
| `website` | string | 产品官网/落地页(PH 跳转链接,含 utm;url 为空时回退) |
| `url` | string | PH 产品页(点击优先用此) |
| `createdAt` | string | 上线时刻 ISO 8601(UTC),如 `2026-07-18T07:01:00Z` |
| `dailyRank` | int | PH 当日综合榜排名(0 表示当日未上榜) |
| `topics` | string[] | 话题标签,如 `["Developer Tools", "Artificial Intelligence"]`;至多 3 个 |
| `thumbnailUrl` | string | 产品主图 URL(PH `thumbnail.url`,列表缩略图用);无则为空 |

### rundown-ai(The Rundown AI 近况 newsletter)

The Rundown AI(beehiiv 托管的头部英文 AI 日更 newsletter)首页文章卡片墙,取首页全部(约 16 篇近况 newsletter)。每篇 1 张卡:主标题(主事件)+ PLUS 副标题(次要工具/技巧)+ 作者段 + 封面图。走 `https://www.therundown.ai/` 首页 jsoup HTML 抓取(`a[href^="/p/"]`),无 token、无 CF 挑战、robots.txt 允许。**列表页无文章日期**(日期只在详情页 JSON-LD),故快照不带 `pageDate`。

| 字段 | 类型 | 说明 |
|------|------|------|
| `rank` | int | 排名(1 起,由首页卡片顺序决定) |
| `slug` | string | 文章 slug,用于拼 URL;同时作翻译状态 key |
| `url` | string | 文章完整地址 `https://www.therundown.ai/p/<slug>` |
| `title` | string | 主标题(当日主事件) |
| `subtitle` | string | PLUS 副标题(次要工具/技巧);可能为空 |
| `authors` | string | 作者段,如 `Zach Mink, +4`(+4 表示还有 4 位合著者);原样展示 |
| `coverUrl` | string | 封面图 URL(beehiiv cdn-cgi 图,排除作者头像);无则为空 |

### aihot-featured(AIHot 精选 TOP20,第三方源)

第三方服务 `aihot.virxact.com` 的「精选」列表 TOP20,来自 `/api/public/items?mode=selected&take=20` 公开 JSON API(无 token,UA 必填否则 nginx 403)。该服务已聚合多源 RSS/X 等并人工/算法筛选,字段对齐 App 端 `NewsItem.fromJson`。

> 📌 **此源特殊性**:aihot-featured 与其它第三方源一样都是第三方源,但它是 App 唯一「只取归档供摘要 Tab、二级页仍走实时接口」的源。归档仅供 App 摘要 Tab 消费(`ai_summary_v2`,兼容旧 `ai_summary`);App「AIHot 精选」二级页本身继续实时拉该服务分页接口(数据更新鲜),不走此归档。

| 字段 | 类型 | 说明 |
|------|------|------|
| `rank` | int | 排名(1 起,由列表顺序决定) |
| `id` | string | 条目 id(后端 cuid,如 `cmrpwwjho06djbisr218fe8ro`) |
| `title` | string | 中文标题 |
| `titleEn` | string | 英文原标题;可能为空 |
| `summary` | string | 中文一句话摘要;可能为空 |
| `url` | string | 第三方原文地址(TechCrunch / The Verge / X 等) |
| `permalink` | string | 站内中文阅读页深链 `https://aihot.virxact.com/items/<id>`;点击优先用此 |
| `source` | string | 来源信源名,如 `TechCrunch:AI(RSS)` / `X:宝玉 (@dotey)` |
| `publishedAt` | string | 发布时刻 ISO 8601(UTC),如 `2026-07-18T04:47:25.000Z` |
| `category` | string | 分类,如 `tip` / `industry` / `paper`;可能为空 |
| `score` | int | 后端筛选权重(越高越重要) |
| `selected` | bool | 是否入选精选池(此源恒为 true) |

## 辅助文件

### `manifest.json`

最近一次抓取运行的总览,用于排查问题:

```json
{
  "run_at": "2026-07-15T08:00:12+0800",
  "run_at_ms": 1784073612000,
  "sources": {
    "hackernews": {"status": "ok", "count": 20, "file": "..."},
    "producthunt": {"status": "fail", "error": "HTTPError: 401 ..."}
  }
}
```

`status` 取值:`ok`(成功,带 count/file)/ `fail`(失败,带 error,此时该源 3 次重试已全败)。每天会被覆盖,只保留最近一次。

## 失败保留机制

某源本次抓取失败(3 次重试后仍失败,如 Cloudflare 拦截)时:

1. **快照不落盘** —— 本次没有该源的新文件,仓库里保留它最后一次成功的快照。
2. **`index.json` 继承旧指向** —— `fetch_data.py` 会在抓取前拉一次上一次的 `index.json`,把失败源的 `latest` 指针原样保留到本次 `index.json`,客户端读到的永远是有效数据路径(不会指空或丢源)。

> 例如:producthunt 在 07-15 当天因 token 失效被 401 拦截,`index.latest.producthunt` 仍指向 `2026-07-14/...`,客户端照常能拉到 07-14 的数据。这是设计行为,对应用户「某源偶尔失败不能让 App 空白」的预期。

该机制依赖 gitcode 上一次 `index.json` 可匿名拉取(公开仓库)。首跑(仓库还没有 `index.json`)时无旧指向可继承,失败源在首跑的 `index.json` 中会缺省。

## 限制与注意事项

1. **Product Hunt 源依赖 token**:`PRODUCT_HUNT_KEY`(Developer Token)缺失或失效(401/403)时该源会失败跳过并保留旧指向。token 在 [Product Hunt API Dashboard](https://api.producthunt.com/v2/dashboard) 重新生成、更新仓库 secret 即可恢复。

2. **无历史数据保证**:数据从 workflow 首次成功运行起开始积累。某源若从未成功过,对应目录不会存在。

3. **频率与配额**:每天多批次定时 + 偶发手动触发。不要高频轮询 raw URL,gitcode 有访问频率限制。客户端建议缓存 `index.json` 的 `updated_at` 判断是否需要刷新。

4. **字段可能变化**:各源抓自第三方页面(GitHub Trending / HuggingFace 等)或第三方 API(aihot-featured 来自 aihot.virxact.com),若对方改版/接口调整导致字段缺失,会在 `manifest.json` 的 error 中体现。字段语义遵循上述文档,新增字段不破坏旧消费者。

5. **时区**:所有时间戳与文件名路径均为北京时间(UTC+8)。`fetched_at` / `run_at` 带显式 `+0800` 偏移,`_ms` 为 UTC Unix 毫秒(与时区无关)。

6. **gitcode 访问特性**(实测):
   - **优先用官方 API**(`api.gitcode.com/api/v5/.../raw/?ref=news-hub-data`):公开仓库匿名可读,稳定。`raw.gitcode.com` 直链背后是华为云 WAF,部分网络环境(数据中心 IP)会被拦 403。
   - **Content-Type 为 `application/octet-stream`**(API)/ `text/plain`(raw 直链):非 `application/json`。`fetch().json()` / `requests.json()` 按响应体内容解析不受影响;若用严格按响应头判断类型的封装,需手动覆盖类型。
   - **建议缓存**:客户端可缓存 `index.json` 的 `updated_at` 做增量判断,避免无脑轮询。
