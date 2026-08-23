# AI-News-Hub-Data 数据用法

数据仓库:[gitcode.com/peng1818/AI-News-Hub-Data](https://gitcode.com/peng1818/AI-News-Hub-Data)
分支:`news-hub-data`

本仓库定时抓取 [AI News Hub](../) App 浏览区域(更多 → 信息源)的 8 个数据源(7 个第三方站点源 + AIHot 精选 TOP20,后者来自第三方服务 aihot.virxact.com),解析成 JSON 后按日期归档。本文档说明数据结构、获取方式与消费示例。

## 更新频率

- **定时**:每天多个批次(北京时间 08:00 / 18:00 / 22:00)。其中 22:00 批由本仓库 GitHub Actions 触发(cron 不保证准点,通常 ±15 分钟内);08:00 / 18:00 批由仓库外机器调度,不在本仓库 workflow 内
- **手动**:workflow 支持 `workflow_dispatch`,可随时手动触发补抓
- 任何源抓取失败会自动重试最多 3 次(间隔 2s/4s);3 次全败才跳过,不影响其余源
- 失败源的 `index.json` latest 指针会保留上一次成功的指向(见下「失败保留机制」)

## 仓库结构

```
news-hub-data 分支/
├── index.json                          ← 入口:即时字段(updated_at / latest /
│                                         latest_overview / latest_audio)
├── history.json                        ← 摘要历史索引:{源名: {日期: relpath}}(每源 31 天)
├── overview_history.json               ← 总览归档索引:{日期: relpath}(保留 90 天)
├── trends.json                         ← 热词趋势榜(纯统计,每次批次整文件覆盖)
├── trends_cloud.json                   ← 趋势词云(纯统计 top ~60 词云候选,专用文件,不归档)
├── trends_history.json                 ← 趋势归档索引:{日期: relpath}(保留 90 天;App「历史热词」页按日期寻址,流水线亦用作排名变化基准)
├── manifest.json                       ← 最近一次运行总览(成功/失败状态)
├── overview/                           ← 今日总览按日归档(内容与 index 的 latest_overview 同构)
│   └── 2026-08-15/
│       └── 11-49-data.json
├── trends/                             ← 热词趋势榜按日归档(内容与当期 trends.json 同构)
│   └── 2026-08-15/
│       └── 18-00-data.json
├── audio/                              ← 语音速报预生成 MP3(单声道 24kHz 48kbps,按日目录,保留 14 天)
│   └── 2026-08-15/
│       └── broadcast.mp3
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
  }
}
```

`index.json` 只含**即时字段**(每次批次整体刷新,体量有界不随保留期增长)。其余内容在根级独立文件按需拉取:`trends.json`(热词趋势)、`history.json`(摘要历史索引)与 `overview_history.json`(总览归档索引),详见下文对应章节。另有 `trends_history.json`(趋势归档索引,App「更多 → 历史热词」页经其按日期寻址,流水线以「昨日最后一期」归档为排名变化基准,详见「趋势历史归档」)。

`latest` 里的路径是**相对于源目录**的。设计行为:某源当天抓取失败时,`index.json` 会保留它最后一次成功的指向(可能落在前一天),客户端永远能拿到有效数据。

`latest_overview` 是**今日总览**(流水线预生成的跨源综合分析,详见下文「今日总览 latest_overview」)。App 首页「总览」tab 直接读这个字段,不再端侧调 AI。

`latest_audio` 是**语音速报预生成音频描述**(流水线 `tts_broadcast.py` 用 Qwen3-TTS 按当日总览预合成的单段全量 MP3,详见下文「语音速报音频」章节)。App「语音速报」优先流式播放该音频,描述缺失/批次滞后时回落系统 TTS。

按日期回看的数据走根级独立索引文件(不在 index.json 里,按需拉取):`history.json`(摘要历史,`{源名: {日期: relpath}}`,详见「按日期取历史快照」)与 `overview_history.json`(总览归档,`{日期: relpath}`,详见「历史总览归档」)。

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

## 按日期取历史快照(history.json 独立文件)

摘要历史索引在根级独立文件 `history.json`(已拆出 index.json,结构为 `{源名: {日期: relpath}}`,内容与原 index 内联 `history` 字段完全一致):

```json
{
  "hackernews": {
    "2026-07-19": "2026-07-19/10-12-data.json",
    "2026-07-18": "2026-07-18/15-00-data.json"
  }
}
```

- **日期 → 当日最后一次快照**:键为北京时间日期(`YYYY-MM-DD`);一天抓多次时,当天较早的那份不进索引。
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

## 历史总览归档(overview/ 目录 + overview_history.json 独立索引)

`latest_overview` 只保留最新一份,历史版本以**按日归档文件**形式留痕:总览生成成功时,流水线把同一对象落盘到 `overview/<YYYY-MM-DD>/<HH-MM>-data.json`(路径规则与各源快照一致,北京时间),并在根级独立文件 `overview_history.json` 维护索引(已拆出 index.json):

```json
{
  "2026-08-15": "2026-08-15/11-49-data.json",
  "2026-08-14": "2026-08-14/18-00-data.json"
}
```

- **日期 → 当日最后一次总览**:一天多批次(08:00 / 18:00 / 22:00)时索引取最后一次;当日全部批次都失败时该日不在索引中(当天早批次的归档仍保留)。
- **保留最近 90 天,且不早于 2026-07-18**。归档文件每份数 KB,历史日期可追至 2026-07-28(总览功能上线日,更早的总览未留痕;2026-08 之前的归档由 `backfill_overview.py` 从 git 历史一次性回填)。
- **relpath 相对 `overview/` 目录**,拼前缀后走 gitcode raw API(同 history 消费方式),如 `overview/2026-08-15/11-49-data.json`。
- **归档文件内容与当日 `latest_overview` 完全同构**(同一对象两处落盘):`generatedAt` / `dataFetchedAt` / `missingSources` / `digest`(2026-08-10 前生成的旧总览可能无此字段,空串不渲染)/ `items`。
- **用途**:App「更多 → 历史总览」按日期回看;总览是流水线唯一花钱调 AI 且覆盖即失的产物(AI 摘要随快照留痕、趋势可从快照重算),归档即它的历史。

## 热词趋势(trends.json 独立文件)

根级独立文件 `trends.json` ——**跨源热词趋势榜**,流水线(`scripts/trend_keywords.py`)在 push 阶段扫近 14 天各源快照做**词频统计 + 每批至多一次 AI 精修**(统计部分确定性可全量重算;AI 精修只做合并同话题/剔除泛词/规范 display,失败零降级回退统计榜),每次批次整文件覆盖,App「趋势」tab 直接读这个文件(内容与原 index 内联 `latest_trends` 字段同构)。

```json
{
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
      "rankChange": 2,
      "items": [
        {"title": "...", "url": "...", "source": "hackernews", "date": "2026-08-09"}
      ]
    }
  ]
}
```

| 字段 | 说明 |
|------|------|
| `generatedAt` | 流水线生成时刻,Unix 毫秒时间戳 |
| `windowDays` | 统计窗口天数(当前 14) |
| `days` | 窗口内日历日期序列(yyyy-MM-dd,北京时间,连续 WINDOW_DAYS 天;缺数据的日期命中计 0),与各 keyword 的 `daily` 按下标对齐 |
| `keywords` | 热词榜,按**动量加权分**降序(近 7 日命中和 × min(动量比, 2.5),动量比 = (近3日+1)/(前3日+1)),恒 10 个(数据稀薄期候选耗尽时允许更少)。入榜门槛:窗口期 `total ≥ 3` 且 `daysActive ≥ 2`;非别名归一的自由 unigram 不直接入榜,按分值序补位 |

`keywords[]` 单项:

| 字段 | 说明 |
|------|------|
| `term` | 归一化 canonical key(小写,如 `gpt-5`) |
| `display` | 展示形(内置 AI 实体映射表指定形,如 `GPT-5`;语料中最常见的原始大小写写法;或 AI 精修命名,可为中文) |
| `total` | 窗口期总命中次数(按「条目命中」计:一个词在一条条目中出现算 1 次) |
| `daysActive` | 窗口期活跃天数(当日命中 ≥1 即活跃) |
| `daily` | 每日命中序列,与 `days` 对齐(sparkline 数据源) |
| `trend` | 涨跌标记:`up` / `down` / `flat`(近 3 日命中和 vs 前 3 日,±max(1, 前3日×15%) 容差带内算 `flat`) |
| `rankChange` | 排名变化(较昨日最后一期榜单):正 = 上升 N 名、0 = 持平、负 = 下降。仅流水线有历史基准时输出(首期运行 / 基准归档缺失时整个字段不输出,App 不显示标记) |
| `isNewEntry` | 新上榜标记(昨日最后一期不在榜),与 `rankChange` 互斥;同样仅在存在历史基准时输出 |
| `items` | ≤3 条代表条目(日期新的优先,按 URL 去重,源尽量多样),每项含 `title`/`url`/`source`/`date` |

**统计口径**:文本取自各源条目标题/简介(aihot-featured 优先 `titleEn`,stormzhang-ai 优先 `english` 并截掉 "PLUS:" 赞助尾巴);英文按 `[a-z0-9]+` 分词去停用词后取 unigram + 相邻 bigram,内置 AI 实体别名表做归一(GPT-5/OpenAI/Claude/千问 等);中文不分词,只对别名表内含 CJK 的词做子串匹配。

**AI 精修**:统计产出候选池(按动量分值序 top 25,宽口径含被自由 unigram 护栏拦下的词)后,流水线调一次 LLM 从中选 10 个、合并同话题候选(被吸收词条的 `daily`/`items` 并入主词后重算统计)、剔除泛词、给出规范 display(可为中文);AI 未选满时按分值序用统计候选补齐,榜单长度恒定。term/absorb 必须来自候选池且不重复、数量与 display 长度校验不过 → 整体回退统计回退榜;AI 配置缺失时跳过。排序语义(动量加权)切换后的首批 `rankChange` 会有一次性全榜跳变,属预期过渡。

**失败语义**:趋势生成失败不阻断推送,当次 `trends.json` 暂缺(clone 自带上一版时实际继承上一期;下次运行自愈。统计部分可从快照全量重算,AI 精修失败退回统计回退榜,均无继承语义);文件完全缺失时 App 走「热词趋势尚未生成」空态。

## 趋势词云(trends_cloud.json 独立文件)

根级独立文件 `trends_cloud.json` ——**趋势词云候选词表**,App「趋势词云」页(趋势 Tab caption 行进入)的数据源。流水线(`trend_keywords.py` 的 `write_trends`)与 `trends.json` **同批生成**:词频统计口径、入榜门槛(total ≥ 3 且 daysActive ≥ 2)、动量分值排序完全一致,取护栏词 top **60** 个(护栏词不足才用自由 unigram 补位)——榜单看头部,词云看全景。**恒为纯统计产出,AI 精修只作用于 `keywords`,不触碰词云**;不进按日归档(词云是即时全景可视化,无历史回看),每次批次整文件覆盖。

```json
{
  "generatedAt": 1786277998615,
  "windowDays": 14,
  "days": ["2026-07-27", "2026-07-28", "...", "2026-08-09"],
  "words": [
    {"term": "agent", "display": "Agent", "total": 489},
    {"term": "claude", "display": "Claude", "total": 145}
  ]
}
```

| 字段 | 说明 |
|------|------|
| `generatedAt` | 流水线生成时刻,Unix 毫秒时间戳(与同批 `trends.json` 一致) |
| `windowDays` | 统计窗口天数(与 `trends.json` 一致,当前 14) |
| `days` | 窗口内日历日期序列(App caption 取末位作「数据截至」) |
| `words` | 词云候选词,按动量加权分降序,轻量词条只有 `term`/`display`/`total` 三个字段(不带 `daily`/`items`,控制体积)。App 按词频映射字号与放置顺序,不依赖数组顺序 |

**失败语义**:与 `trends.json` 同批生成但独立落盘——词云文件写入失败仅告警,不影响热词榜推送;文件缺失(404)或 `words` 为空时 App「趋势词云」页走空态,下次批次自愈。

## 趋势历史归档(trends/ 目录 + trends_history.json 独立索引)

`trends.json` 只保留最新一期,历史版本以**按日归档文件**留痕:趋势生成成功时,流水线把同一对象落盘到 `trends/<YYYY-MM-DD>/<HH-MM>-data.json`(北京时间),并在根级独立文件 `trends_history.json` 维护索引(结构与 `overview_history.json` 同构):

```json
{
  "2026-08-15": "2026-08-15/18-00-data.json",
  "2026-08-14": "2026-08-14/18-00-data.json"
}
```

- **日期 → 当日最后一期榜单**:一天多批次(08:00 / 18:00 / 22:00)时索引取最后一次;当日趋势生成失败的批次不落归档(该日期沿用早批次指向)。
- **保留最近 90 天指针**;旧归档目录只增不删,超出保留期的日期仅不再被索引指向。
- **历史日期可追至 2026-08-10**(趋势功能上线日,更早无数据)。存量历史由 `backfill_trends.py` 从数据仓库 git 历史一次性回填(遍历每次提交的 `trends.json`,拆分前回退读 index.json 内联 `latest_trends`,按 `generatedAt` 去重)。
- **relpath 相对 `trends/` 目录**,拼前缀后走 gitcode raw API(同 history / overview_history 消费方式)。
- **归档文件内容与当期 `trends.json` 完全同构**(同一对象两处落盘)。
- **用途**:① 每期 `rankChange` / `isNewEntry` 以「昨日最后一期」归档为基准计算(基准读取失败只是少这两个字段,不影响榜单本身);② App「更多 → 历史热词」按日期回看(经 `trends_history.json` 寻址,复用 `fetchSnapshot` 路径缓存,归档内容与当期榜单同构渲染)。

## 语音速报音频(audio/ 目录 + index.json 顶层 latest_audio 字段)

App「语音速报」的预生成神经语音:流水线(`scripts/tts_broadcast.py`,fetch 与 push 之间)按当日 `latest_overview` 的**综述 digest**(仅综述,不含 Top10 条目明细;文本与 App 端兜底朗读一致)用 Qwen3-TTS(Qwen3-TTS-12Hz-0.6B-CustomVoice,Apache-2.0,音色 serena,经 qwentts.cpp 纯 C++ 推理)合成为**单段 MP3**落盘 `audio/<YYYY-MM-DD>/broadcast.mp3`(北京时间),并把描述写进 `index.json` 顶层即时字段 `latest_audio`:

```json
{
  "latest_audio": {
    "generatedAt": 1784073612000,
    "voice": "serena",
    "model": "qwen3-tts-0.6b-customvoice",
    "file": "audio/2026-08-15/broadcast.mp3",
    "title": "今日速报",
    "durationMs": 42000,
    "bytes": 252000
  }
}
```

| 字段 | 说明 |
|------|------|
| `generatedAt` | 与同批 `latest_overview.generatedAt` **严格同值**(音频按综述文本合成,批次绑定;App 比对判定新鲜度,不一致回落系统 TTS) |
| `voice` / `model` | 音色名 / 模型名(信息字段) |
| `file` | 单段音频的仓库根相对路径,走 gitcode raw API 直读(见上「文件直链」) |
| `title` | 音频标题(清单参考值,App 端仍用本地化标题) |
| `durationMs` | 音频时长,毫秒(流水线按 wav 帧数预读) |
| `bytes` | 文件大小,字节 |

- **MP3 规格**:单声道 24kHz 48kbps(≈0.36MB/分钟;综述百字级约 0.5-1 分钟 ≈0.2-0.4MB/天,引擎原生 24kHz 输出不升采样)。
- **单段综述**:综述百字级,单次合成远低于引擎 2048 帧上限,无需分段拼接;App 以单条播放(无上一条/下一条概念);合成失败(重试 1 次后)当批不写 `latest_audio`(App 整批回落系统 TTS,下次批次自愈)。
- **同日覆盖**:文件名恒为 `broadcast.mp3`,同日多批次天然覆盖不累积。
- **保留 14 天**:`audio/` 下过期日期目录由 `push_data.py` 在 overlay 后显式删除(overlay 只增不删),仓库工作区稳定 ≈34MB。
- **失败语义**:语音合成任何失败(引擎/模型缺失/综述合成失败/整阶段异常/阶段墙钟预算耗尽)只告警不阻断推送,当次不写 `latest_audio`(App 回落系统 TTS,下次批次自愈)。

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

`status` 取值:`ok`(成功,带 count/file)/ `fail`(失败,带 error,此时该源 3 次重试已全败)。每天会被覆盖,只保留最近一次。`file` 为相对仓库根的路径(如 `hackernews/2026-07-15/08-00-data.json`)。

## 失败保留机制

某源本次抓取失败(3 次重试后仍失败,如 Cloudflare 拦截)时:

1. **快照不落盘** —— 本次没有该源的新文件,仓库里保留它最后一次成功的快照。
2. **`index.json` 继承旧指向** —— `fetch_data.py` 会在抓取前拉一次上一次的 `index.json`,把失败源的 `latest` 指针原样保留到本次 `index.json`,客户端读到的永远是有效数据路径(不会指空或丢源)。

> 例如:producthunt 在 07-15 当天因 token 失效被 401 拦截,`index.latest.producthunt` 仍指向 `2026-07-14/...`,客户端照常能拉到 07-14 的数据。这是设计行为,对应用户「某源偶尔失败不能让 App 空白」的预期。

该机制依赖 gitcode 上一次 `index.json` 可匿名拉取(公开仓库)。首跑(仓库还没有 `index.json`)时无旧指向可继承,失败源在首跑的 `index.json` 中会缺省。

总览同理:本次 AI 生成失败时 `latest_overview` 继承上一次,当日归档文件不落盘(`overview_history` 从上一次索引继承,当天早批次的归档仍可寻址)。

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
