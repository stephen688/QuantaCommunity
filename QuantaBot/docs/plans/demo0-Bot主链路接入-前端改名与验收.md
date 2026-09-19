# demo0 Bot 小程序、改名与真联调实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在服务端 D1/D2/D4/D5/D6/D7 已完成的基础上，交付小程序 `@框框` 与 AI 身份展示、统一 QuantaBot 用户可见昵称，并用真实依赖完成端到端与 17 场景验收。

**Architecture:** 本计划只消费服务端计划已产出的 `mentionBot`、`isBot`、BOT service token、MQ 事件和 bot 只读/同步接口。小程序负责结构化触发提示与身份透明，QuantaBot 只改用户可见人格锚点；最终由共享 RabbitMQ 串起 demo0、QuantaBot 与两次机审，并以 HTTP 套件和运行证据收口。

**Tech Stack:** 小程序 = TypeScript/JavaScript 原生微信小程序；QuantaBot = Python 3.12 / FastAPI / aio-pika / Qdrant；demo0 验收面 = Java 17 / Spring Boot 3.5 / MySQL / RabbitMQ / Redis。

**Spec:** `QuantaBot/docs/plans/Phase0-契约谈判.md`（C-1~C-7）；`QuantaBot/docs/plans/demo0-Bot主链路接入-服务端.md`（Task 1-10 前置产物）；`QuantaBot/总计划.md`；`QuantaBot/AGENTS.md`。

## Global Constraints

1. 本计划从 Task 11 编号续接服务端计划；Task 11/12 可并行，Task 13 依赖两份计划的 Task 1-12，Task 14 依赖 Task 13 的真实运行证据。
2. 用户可见昵称固定为“框框”，回复徽章固定为 `[框框·AI 学长]`；项目名、包名、`QUANTABOT_`、`quantabot:` 和 `quantabot.*` 不改。
3. 小程序仓库同时跟踪 TypeScript 与运行时 JavaScript；行为改动必须成对落盘，并以微信开发者工具验收。
4. `.env`、service token、DeepSeek/Qwen/Langfuse/阿里云密钥只进本机配置，不进入 Git、HTTP 环境模板或结果文档。
5. 真联调不接受 fake、`not_configured` 或默认放行冒充成功；评论机审必须显式开启并观察待审到通过/驳回的状态变化。
6. 单条 E2E 冒烟与 17 场景回归是两个门：前者通过不能提前勾选 `M3-真联调回补`。
7. 保留用户现有未提交文件；提交命令只暂存本 Task 明列路径。

## Cross-plan Interfaces

服务端计划必须先产出：

- `CommentAddDTO.mentionBot?: Boolean`；
- 评论列表/回复列表的 `isBot: boolean`；
- bot user_id=10000、昵称“框框”、`BOT` 角色和一年期 service token；
- `quantabot.comment.queue` 及 `BOT_MENTION_REQUESTED` Outbox 路由；
- `/bot/comment/chain|history|tree` 与 `/bot/content/sync`；
- QuantaBot 墓碑删除消费与 `ingest_content -> (upserted, deleted)`。

---

## 批4：小程序与用户可见改名（Task 11-12）

### Task 11：D3——小程序 `@框框` 按钮 + AI 角标 + `mentionBot` 透传

**状态**：`DEFERRED（用户于 2026-09-18 暂缓，小程序不纳入本轮后端联调验收）`

**目标**：在现有评论编辑器上增加单 bot 快捷入口；用户点击后插入 `@框框 `，提交时把 `mentionBot=true` 传给 Task 3 已定义的 `CommentAddDTO`。后端返回 `isBot=true` 的一级评论和楼中楼回复，都在昵称旁显示 `AI` 角标。

**现状事实**（计划续写时已核实）：
- 评论编辑器只有 `value/input/submit`，`submit` 事件当前只发 `{content}`；
- 只有 `pages/detail-life` 与 `pages/answer-detail` 两个页面消费 `comment-composer`，两处都手写了提交 payload 类型；
- `types/comment.ts` 尚无 `mentionBot/isBot`，`comment.service.ts` 也未映射或传输这两个字段；
- 小程序同时跟踪 `.ts` 和运行时 `.js`；本 Task 必须成对修改，不能只改 TypeScript 形成“类型对、真机无效”。

**Files**：
- Create: `demo0-miniprogram/miniprogram/constants/bot.ts`
- Create: `demo0-miniprogram/miniprogram/constants/bot.js`
- Create: `demo0-miniprogram/tests/bot-comment.test.cjs`
- Modify: `demo0-miniprogram/package.json`
- Modify: `demo0-miniprogram/miniprogram/types/comment.ts`
- Modify: `demo0-miniprogram/miniprogram/components/comment-composer/index.ts`
- Modify: `demo0-miniprogram/miniprogram/components/comment-composer/index.js`
- Modify: `demo0-miniprogram/miniprogram/components/comment-composer/index.wxml`
- Modify: `demo0-miniprogram/miniprogram/components/comment-composer/index.wxss`
- Modify: `demo0-miniprogram/miniprogram/components/comment-list/index.ts`
- Modify: `demo0-miniprogram/miniprogram/components/comment-list/index.js`
- Modify: `demo0-miniprogram/miniprogram/components/comment-list/index.wxml`
- Modify: `demo0-miniprogram/miniprogram/components/comment-list/index.wxss`
- Modify: `demo0-miniprogram/miniprogram/services/comment.service.ts`
- Modify: `demo0-miniprogram/miniprogram/services/comment.service.js`
- Modify: `demo0-miniprogram/miniprogram/pages/detail-life/index.ts`
- Modify: `demo0-miniprogram/miniprogram/pages/detail-life/index.js`
- Modify: `demo0-miniprogram/miniprogram/pages/answer-detail/index.ts`
- Modify: `demo0-miniprogram/miniprogram/pages/answer-detail/index.js`

**Interfaces**：
- Consumes: Task 3 的 `CommentAddDTO.mentionBot?: Boolean` 与评论 VO `isBot: boolean`。
- Produces: `BOT_NICKNAME = "框框"`、`BOT_MENTION = "@框框"`、`appendBotMention(value) -> string`、`hasBotMention(value) -> boolean`；`comment-composer.submit.detail = {content, mentionBot}`；`CommentItemModel.isBot: boolean`。

- [ ] **Step 1: 红——给纯函数写 Node 测试**

`tests/bot-comment.test.cjs`：

```javascript
const test = require('node:test');
const assert = require('node:assert/strict');
const {
  BOT_MENTION,
  appendBotMention,
  hasBotMention,
} = require('../miniprogram/constants/bot.js');

test('appendBotMention inserts one structured mention and preserves draft', () => {
  assert.equal(BOT_MENTION, '@框框');
  assert.equal(appendBotMention(''), '@框框 ');
  assert.equal(appendBotMention('帮我看看'), '帮我看看 @框框 ');
  assert.equal(appendBotMention('已有 @框框 了'), '已有 @框框 了');
});

test('hasBotMention follows submitted text after user edits', () => {
  assert.equal(hasBotMention('@框框 你好'), true);
  assert.equal(hasBotMention('@框 你好'), false);
  assert.equal(hasBotMention('普通评论'), false);
});
```

`package.json` scripts 增：

```json
"test:bot-comment": "node --test tests/bot-comment.test.cjs"
```

Run: `npm --prefix demo0-miniprogram run test:bot-comment`
Expected: FAIL（`constants/bot.js` 不存在）。

- [ ] **Step 2: 绿——落单一昵称真值与插入规则**

`constants/bot.ts`：

```typescript
export const BOT_NICKNAME = '框框';
export const BOT_MENTION = `@${BOT_NICKNAME}`;

export function hasBotMention(value: string): boolean {
  return value.includes(BOT_MENTION);
}

export function appendBotMention(value: string): string {
  const draft = typeof value === 'string' ? value : '';
  if (hasBotMention(draft)) {
    return draft;
  }
  const separator = draft.length > 0 && !/\s$/.test(draft) ? ' ' : '';
  return `${draft}${separator}${BOT_MENTION} `;
}
```

`constants/bot.js` 写入等价 CommonJS 实现（`exports.BOT_NICKNAME/BOT_MENTION/hasBotMention/appendBotMention`）。

Run: `npm --prefix demo0-miniprogram run test:bot-comment`
Expected: 2 passed。

- [ ] **Step 3: 编辑器增 `@框框` 按钮与 submit 标记**

`comment-composer/index.ts` 和 `.js` 对应修改：

```typescript
import { appendBotMention, hasBotMention } from '../../constants/bot';

// methods 内追加
onMentionBotTap() {
  if (this.data.submitting || this.data.disabled) {
    return;
  }
  const draft = appendBotMention(this.data.draft || '');
  this.setData({ draft });
  this.syncHint(draft);
  this.triggerEvent('input', { value: draft });
},
```

`onSubmitTap` 的事件改为：

```typescript
this.triggerEvent('submit', {
  content: text,
  mentionBot: hasBotMention(text),
});
```

`index.wxml` 的 footer 左侧改为一个 tools 容器，保留字数提示并加按钮：

```xml
<view class="cc__tools">
  <button
    class="cc__mention"
    size="mini"
    disabled="{{disabled || submitting}}"
    hover-class="cc__mention--hover"
    bindtap="onMentionBotTap"
  >@框框</button>
  <text class="cc__hint">{{hintText}}</text>
</view>
```

`index.wxss` 增 `.cc__tools/.cc__mention/.cc__mention--hover`；按钮用现有 `--color-cta` 的浅色版，不新造一套色板，不挤压“发送”主操作。

- [ ] **Step 4: DTO/VO 类型与 service 映射闭环**

`types/comment.ts`：

```typescript
// CommentAddDTO
mentionBot?: boolean;

// CommentRowVO
isBot?: unknown;

// CommentItemModel
isBot: boolean;
```

`comment.service.ts/.js`：

```typescript
// mapCommentRowToItem 返回对象
isBot: toBool(row.isBot),

// sendComment 组 body
if (payload.mentionBot === true) {
  body.mentionBot = true;
}
```

Mock 数据中加一条 `userId: 10000, nickName: '框框', isBot: true`的可见样本，用于开发者工具在无后端时也能验角标。

- [ ] **Step 5: 两个页面透传 `mentionBot`**

`detail-life/index.ts/.js` 与 `answer-detail/index.ts/.js` 的 `onComposerSubmit` 事件类型改为 `{content?: string; mentionBot?: boolean}`，payload 类型加 `mentionBot?: boolean`，并在初始 payload 中加：

```typescript
mentionBot: e.detail?.mentionBot === true,
```

不把 `mentionBot` 挂到页面长期 data：它是本次提交的派生值，用户删掉 `@框框` 后必须自动回到 false。

- [ ] **Step 6: 一级/二级评论渲染 AI 角标**

`comment-list/index.ts/.js` 的 `CommentLike` 增 `isBot`；`enrich` 的 spread 自然保留字段。`index.wxml` 在一级 `cl__name` 与二级 `cl__reply-author` 后分别追加：

```xml
<text wx:if="{{item.isBot}}" class="cl__ai-badge">AI</text>
```

`index.wxss` 将角标做成紧邻昵称的小型高对比胶囊；字号不大于 20rpx，不使用头像或昵称猜 AI 身份。

- [ ] **Step 7: 静态验证 + 开发者工具验收**

```bash
npm --prefix demo0-miniprogram run test:bot-comment
npm --prefix demo0-miniprogram run typecheck
```

微信开发者工具必验（生成证据截图，不以 typecheck 代替 UI 验收）：
1. 生活帖评论和回答详情评论都可点 `@框框`；重复点不重复插入；
2. 删除 @ 文本后提交，Network 面板无 `mentionBot:true`；保留时请求体含 `mentionBot:true`；
3. 顶级 bot 评论和楼中楼 bot 回复均显示 `AI`，普通用户不显示；
4. 键盘弹起、字数计数、发送 loading、回复某人四条旧交互无回归。

- [ ] **Step 8: 提交**

```bash
git add demo0-miniprogram/package.json demo0-miniprogram/miniprogram/constants/bot.ts demo0-miniprogram/miniprogram/constants/bot.js demo0-miniprogram/tests/bot-comment.test.cjs demo0-miniprogram/miniprogram/types/comment.ts demo0-miniprogram/miniprogram/components/comment-composer demo0-miniprogram/miniprogram/components/comment-list demo0-miniprogram/miniprogram/services/comment.service.ts demo0-miniprogram/miniprogram/services/comment.service.js demo0-miniprogram/miniprogram/pages/detail-life/index.ts demo0-miniprogram/miniprogram/pages/detail-life/index.js demo0-miniprogram/miniprogram/pages/answer-detail/index.ts demo0-miniprogram/miniprogram/pages/answer-detail/index.js
git commit -m "feat(miniprogram): add @框框 composer action, mentionBot forwarding and AI badge"
```

---

### Task 12：QuantaBot 用户可见身份改名为“框框”

**目标**：触发昵称、回复徽章、人格自我介绍、决策器身份和全部可执行样例统一为“框框”；项目/模块名 `QuantaBot`、`QUANTABOT_` 环境变量前缀、`quantabot:` Redis 键、`quantabot.*` MQ 名保持不变。

**精确影响面**（当前 checkout 已清点）：
- `QuantaBot/eval/**/*.yaml` 中字面量 `QuantaBot` **42 处**（`@QuantaBot` 22 + `[QuantaBot·AI 学长]` 20）；
- `QuantaBot/tests/**/*.py` 中字面量 `QuantaBot` **75 处**（触发文本 55 + 徽章 12 + 人格 fixture/断言 8）；
- 生产人格面只改 `trigger.py/generation.py/decision.py/prompts/kernel.md`；不对整仓做无差别替换。

**Files**：
- Modify: `QuantaBot/src/quanta_bot/pipeline/trigger.py`
- Modify: `QuantaBot/src/quanta_bot/pipeline/generation.py`
- Modify: `QuantaBot/src/quanta_bot/pipeline/decision.py`
- Modify: `QuantaBot/prompts/kernel.md`
- Modify: `QuantaBot/eval/cases/*.yaml`（当前 21 个命中文件）
- Modify: `QuantaBot/tests/**/*.py`（当前 15 个命中文件）

**Interfaces**：
- `AI_NICKNAME = "框框"`
- `AI_BADGE = "[框框·AI 学长]"`
- `kernel.md` 自我介绍：“你是框框，校园社区 QuantaCommunity 的 AI 学长”。

- [x] **Step 1: 红——先改核心断言**

`tests/unit/pipeline/test_trigger.py` 改 `assert AI_NICKNAME == "框框"`；`tests/unit/pipeline/test_generation.py` 的徽章断言改为 `[框框·AI 学长]`；`test_persona.py` 的人格 fixture 改为框框。

Run:

```bash
cd QuantaBot
uv run pytest tests/unit/pipeline/test_trigger.py tests/unit/pipeline/test_generation.py tests/unit/pipeline/test_persona.py -q
```

Expected: FAIL，分别指向旧 `AI_NICKNAME`、旧 `AI_BADGE`、旧 kernel 文本。

- [x] **Step 2: 绿——改四个生产人格锚点**

```python
# trigger.py
AI_NICKNAME = "框框"

# generation.py
AI_BADGE = "[框框·AI 学长]"
```

`trigger.py` 头部删掉“昵称待定”注记；`decision.py` 的 `DECISION_SYSTEM_PROMPT` 将“AI 学长 QuantaBot”改为“AI 学长框框”；`kernel.md` 标题与第一句身份改为框框，保留 `QuantaCommunity` 社区名。

- [x] **Step 3: 机械替换 eval 42 处，用计数门防漏改**

仅在 `QuantaBot/eval` 下替换字面量 `QuantaBot` → `框框`。替换前必须记录 42，替换后必须为 0：

```powershell
(rg -o 'QuantaBot' QuantaBot/eval -g '*.yaml' -g '*.yml' | Measure-Object).Count
rg -n 'QuantaBot' QuantaBot/eval -g '*.yaml' -g '*.yml'
```

预期结果：替换前 `42`；替换后 `rg` 无输出。

- [x] **Step 4: 机械替换 tests 75 处，用计数门防漏改**

仅在 `QuantaBot/tests` 下替换字面量 `QuantaBot` → `框框`。

```powershell
(rg -o 'QuantaBot' QuantaBot/tests -g '*.py' | Measure-Object).Count
rg -n 'QuantaBot' QuantaBot/tests -g '*.py'
```

预期结果：替换前 `75`；替换后 `rg` 无输出。这 75 处均是触发/徽章/人格测试数据，不包含环境变量或 MQ/Redis 系统标识。

- [x] **Step 5: 验证用户可见改名与系统名不变**

```bash
cd QuantaBot
uv run ruff format src tests
uv run ruff check src tests
uv run pytest tests/unit -q
uv run pytest tests/eval -q
```

改人格必须再跑真模型档（会产生费用）：

```bash
QUANTABOT_EVAL=1 uv run pytest tests/eval -q
```

系统名保护扫描：

```bash
rg -n 'QUANTABOT_|quantabot:|quantabot\.(exchange|comment\.queue)|title="QuantaBot"' src docker .env.example
```

Expected: 上述系统标识仍存在且 diff 未更名；新 `@框框` 和 `[框框·AI 学长]` 断言全绿。

已执行证据（2026-09-18）：`QUANTABOT_EVAL=1 uv run pytest tests/eval -q` → `28 passed in 660.36s`；旧用户可见昵称在 eval/tests 中为 0 处，`QUANTABOT_`、`quantabot:`、`quantabot.*` 系统标识仍保留。

- [ ] **Step 6: 提交**

```bash
git add QuantaBot/src/quanta_bot/pipeline/trigger.py QuantaBot/src/quanta_bot/pipeline/generation.py QuantaBot/src/quanta_bot/pipeline/decision.py QuantaBot/prompts/kernel.md QuantaBot/eval QuantaBot/tests
git commit -m "feat(bot): rename user-facing AI identity to 框框"
```

---

## 批5：端到端真联调（Task 13-14）

> **前置**：Task 1-10、Task 12 已完成并进入联调；Task 11 按用户要求 `DEFERRED`，不作为本轮后端联调前置。demo0 的 MySQL/Redis/阿里云文本机审密钥、QuantaBot 的 DeepSeek/Qwen embedding/Langfuse 密钥真实可用。缺任一真依赖时只能记 `BLOCKED`，不能把 fake/降级路径冒充端到端通过。

### Task 13：`.env` 真值接线 + `@框框 → 可见 AI 回复` 冒烟

**目标**：让 demo0 与 QuantaBot 共用同一个 RabbitMQ，将 QuantaBot 切到 `fake_mode=false`，并用一条唯一标记评论证明整条链路真正经过“用户评论机审 → Outbox → MQ → bot → service token 写库 → bot 评论机审 → 列表可见”。

**关键网络口径**：
- demo0 跑在 Windows 宿主机 `:9191`；QuantaBot `app` 跑在 Docker，因此 `QUANTABOT_MAIN_SERVICE_BASE_URL` 必须是 `http://host.docker.internal:9191`，不是容器内的 `127.0.0.1`；
- demo0 必须通过 Spring 环境变量覆盖到 `127.0.0.1:5673 / quantabot / quantabot-dev`；QuantaBot 容器继续用 compose 内置 `rabbitmq:5672`。两边不共用 broker 时，各自 health 可能都绿，但事件永远不会到 bot。

**Files**：
- Local-only modify: `QuantaBot/.env`（git ignored，不提交）
- Evidence only: Docker/demo0/QuantaBot 日志、MySQL 查询结果、Langfuse trace URL/trace id（Task 14 回填）

- [x] **Step 1: 安全性前置检查**

```powershell
git check-ignore QuantaBot/.env
git status --short
docker compose -f QuantaBot/docker/docker-compose.yml config --services
docker compose -f QuantaBot/docker/docker-compose.yml up -d rabbitmq agent-redis
```

Expected: 第一条输出 `QuantaBot/.env`；本地密钥/token 不出现在 `git status`。不得把 `.env` 内容复制到计划、RESULTS 或日志摘要。

执行 `demo0/src/main/resources/db/dev-seed-incremental.sql` 中本计划新增的幂等段，然后核对：`tb_user.id=10000 AND nick_name='框框'`恰好一行，`user_role(user_id=10000, role_code='BOT')`恰好一行，`tb_outbox_event/tb_bot_policy_doc` 已存在。

- [x] **Step 2: 生成并验证 bot service token**

```powershell
mvn -f demo0/pom.xml test '-Dtest=BotServiceTokenGeneratorTest' '-Dbot.service-token.generate=true'
```

只把输出 token 写入 `QuantaBot/.env`；用 `Authorization: Bearer <token>` 调 `GET /bot/comment/history?userId=10000&postId=<visiblePostId>&pageNum=1&pageSize=1`，预期 HTTP 200 + `body.code=200`。无 token 同路径必须 401。

- [x] **Step 3: 写入 QuantaBot 真模式 `.env`**

至少满足（密钥右值不落计划）：

```dotenv
QUANTABOT_APP_ENV=dev
QUANTABOT_FAKE_MODE=false
QUANTABOT_REDIS_URL=redis://127.0.0.1:6379/0
QUANTABOT_DEEPSEEK_BASE_URL=https://api.deepseek.com
QUANTABOT_DEEPSEEK_MODEL=deepseek-v4-flash
QUANTABOT_LANGFUSE_HOST=http://127.0.0.1:3000
QUANTABOT_QDRANT_URL=http://127.0.0.1:6333
QUANTABOT_MQ_URL=amqp://quantabot:quantabot-dev@127.0.0.1:5673/
QUANTABOT_MAIN_SERVICE_BASE_URL=http://host.docker.internal:9191
QUANTABOT_MAIN_SERVICE_BOT_USER_ID=10000
QUANTABOT_EMBEDDING_DIM=1024
```

另外保留或写入本机真实的 `QUANTABOT_DEEPSEEK_API_KEY`、`QUANTABOT_LANGFUSE_PUBLIC_KEY`、`QUANTABOT_LANGFUSE_SECRET_KEY`、`QUANTABOT_MAIN_SERVICE_TOKEN`、`QUANTABOT_EMBEDDING_BASE_URL`、`QUANTABOT_EMBEDDING_API_KEY`、`QUANTABOT_EMBEDDING_MODEL`；计划不展示密钥右值。`EMBEDDING_BASE_URL/MODEL/DIM` 要与 demo0 当前 Qwen embedding 实配对照，不凭文档旧值猜。

- [x] **Step 4: 启动共享依赖与 demo0**

先启 QuantaBot compose 依赖，再在同一 PowerShell 会话给 demo0 注入 broker/机审真值：

```powershell
docker compose -f QuantaBot/docker/docker-compose.yml up -d qdrant rabbitmq agent-redis langfuse-web
$env:SPRING_RABBITMQ_HOST='127.0.0.1'
$env:SPRING_RABBITMQ_PORT='5673'
$env:SPRING_RABBITMQ_USERNAME='quantabot'
$env:SPRING_RABBITMQ_PASSWORD='quantabot-dev'
$env:QUANTA_MODERATION_TARGETS_COMMENT_ENABLED='true'
mvn -f demo0/pom.xml spring-boot:run
```

同一会话还必须已有 `DB_PASSWORD/JWT_USER_SECRET_KEY/ALIYUN_ACCESS_KEY_ID/ALIYUN_ACCESS_KEY_SECRET` 等 demo0 原有真值。该命令在一个专用终端中前台保持运行，后续验收使用另一终端。启动日志要确认 Rabbit 连接的是 `127.0.0.1:5673`，不是 `192.168.100.128:5672`。

- [x] **Step 5: 启 QuantaBot app 并过真健康门**

```powershell
docker compose -f QuantaBot/docker/docker-compose.yml up -d --build app
Invoke-RestMethod http://127.0.0.1:8000/health | ConvertTo-Json -Depth 6
```

Expected:
- `fake_mode=false`；
- `dependencies.mq/kv/vector/llm/tracing/main_service` 都是 `ok`（不接受 `fake/not_configured/error`）；
- RabbitMQ 管理页的 `quantabot.comment.queue` 有一个活跃 consumer。

- [x] **Step 6: 手动摄取真内容源**

```powershell
$headers = @{ Authorization = "Bearer $env:QUANTABOT_ADMIN_TOKEN" }
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8000/admin/ingest -Headers $headers
```

Expected: Task 10 后响应同时有 `ingested` 和 `deleted`，`ingested > 0`；Qdrant `qb_content` 点数增长。管理 token 只通过 `Authorization: Bearer ...` 请求头传递，不写入文档或 JSON body。

- [x] **Step 7: 发一条唯一冒烟评论**

先用 `SELECT content_id FROM tb_content WHERE audit_status=1 AND is_deleted=0 ORDER BY content_id LIMIT 1` 选一条真实可见内容，记为 PowerShell 整数变量 `$botTestContentId`。再用普通用户 token 调 `POST /comment/send`：

```powershell
$smokeMarker = 'E2E-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
$requestBody = @{
  contentId = [long]$botTestContentId
  content = "@框框 $smokeMarker 请用一句话说明你是 AI"
  mentionBot = $true
} | ConvertTo-Json
$response = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:9191/comment/send' `
  -Headers @{ authorization = $userToken } -ContentType 'application/json' -Body $requestBody
$triggerCommentId = [long]$response.data
```

把返回的 `commentId` 记为 `triggerCommentId`。该条必须先出现 `audit_status=0`，再被真机审改为 1；如因机审延迟，按间隔 2s、最长 60s 轮询，不手工改库跳过。

- [x] **Step 8: 五段证据对账**

1. **机审**：`tb_content_comment.comment_id=triggerCommentId` 最终 `audit_status=1`，有 `audit_time`；
2. **Outbox/MQ**：`tb_outbox_event` 中 `event_type='BOT_MENTION_REQUESTED' AND aggregate_id=triggerCommentId`恰好 1 条，最终 `status='SENT'`；
3. **bot**：QuantaBot 日志/决策库与 Langfuse 有同一 `comment_id`的一条完整 run，生成文本以 `[框框·AI 学长]` 开头；
4. **写库 + 二次机审**：`tb_content_comment` 中 `user_id=10000 AND reply_comment_id=triggerCommentId` 恰好 1 条，且最终 `audit_status=1`；
5. **可见**：`GET /comment/list` 或 `/comment/replyList` 能查到该回复，返回 `nickName="框框"` 且 `isBot=true`。

任一段失败时在该段停住定位，不用重复手工发多条评论掩盖首条故障。

- [x] **Step 9: 幂等与静默不回核对（已执行，范围化通过）**

已核验幂等与静默语义：重放原 event 后 QuantaBot 记录 `skipped_idempotent`，trigger=347 仍只有 reply=348 一条；主轮 S04 在 LLM 失败时记录 `failed` 并静默，S09 按决策记录静默，主轮 S05/S06 的重复投递记录 `skipped_idempotent` 且未新增回复。随后用全新事件做 v4-flash 补跑：S04=381 在 demo0 写库敏感词校验失败并静默，S05=382→384、S06=383→385 均生成并可见，说明主轮 S05/S06 的静默是幂等结果而非模型档位必然静默。尚未单独补一条“明确违规文本被机审驳回且无 BOT_MENTION_REQUESTED”的专用 fixture，因此不能把本 Step表述为该专用机审驳回样本已通过。

- 从 `tb_outbox_event` 取该 `triggerCommentId` 的 payload，通过 RabbitMQ Management HTTP API 再发布到 exchange=`quantabot.exchange`、routing_key=`quantabot.comment.created`一次（不调 `/admin/event/outbox/{eventId}/replay`，该端点只允许 DEAD 事件），然后断言 AI 回复数仍为 1；
- 将 SQL 返回的 JSON payload 只放入当前 PowerShell 会话变量 `$eventPayload` 后执行（不落盘、不打印 token）：

```powershell
if ([string]::IsNullOrWhiteSpace($eventPayload)) { throw 'eventPayload 为空' }
$rmqCredential = [PSCredential]::new(
  'quantabot',
  (ConvertTo-SecureString 'quantabot-dev' -AsPlainText -Force)
)
$publishBody = @{
  properties = @{}
  routing_key = 'quantabot.comment.created'
  payload = [string]$eventPayload
  payload_encoding = 'string'
} | ConvertTo-Json -Depth 8
$publishResult = Invoke-RestMethod -Method Post `
  -Uri 'http://127.0.0.1:15673/api/exchanges/%2F/quantabot.exchange/publish' `
  -Credential $rmqCredential -ContentType 'application/json' -Body $publishBody
if (-not $publishResult.routed) { throw 'RabbitMQ publish 未路由到 exchange' }
```

- 明确违规文本的独立机审驳回 fixture 仍作为后续补证项；本轮已用 S04/S09 的失败/决策静默证据核验“不乱回”语义，但不替代该 fixture；
- 本步使用专用测试数据，不重放或修改非本轮事件。

Task 13 无需提交 `.env`；证据在 Task 14 统一回填。

---

### Task 14：验收清单 + `07-bot.http` + 项目文档收口

**目标**：把 C-1~C-7、D1~D7和真链路证据固化为可重跑的 HTTP 套件与验收表；只有真跑证据通过后，才勾选总计划和 Phase 0 待定项。

**Files**：
- Create: `demo0/docs/api-test/cases/07-bot.http`
- Local-only create: `demo0/docs/api-test/cases/http-client.private.env.json`（真 token，git ignored）
- Modify: `demo0/.gitignore`（忽略上述 private env）
- Modify: `demo0/docs/api-test/cases/http-client.env.json`
- Modify: `demo0/docs/api-test/env.example.sh`
- Modify: `demo0/docs/api-test/TEST_PLAN.md`
- Modify: `demo0/docs/api-test/RESULTS.md`
- Modify: `QuantaBot/docs/plans/Phase0-契约谈判.md`
- Modify: `QuantaBot/总计划.md`
- Modify: `QuantaBot/docs/技术选型.md`（只回填已验证的终态事实/运行命令）
- Modify: `QuantaBot/AGENTS.md`（只在真链路跑通后增联调命令与变更记录）

**Interfaces**：
- `07-bot.http` 新增变量：`botToken`、`botUserId=10000`、`botTestContentId`、`botTriggerCommentId`、`botReplyCommentId`；真 `botToken` 只写 `http-client.private.env.json`。
- 验收状态允许 `PASS/FAIL/PARTIAL/BLOCKED`；`PARTIAL` 必须写清已执行范围与缺口，`BLOCKED` 必须写具体缺失依赖；不得以 `PARTIAL` 勾总计划。

- [x] **Step 1: 新建 `07-bot.http` 的环境与鉴权组**

文件头明确：需先跑 `01-auth-user.http` 拿 `userToken`，且 Task 13 的 demo0/QuantaBot 真栈已启动。用例：
- `B0-01`：无 token 调 `/bot/comment/chain` → HTTP 401；
- `B0-02`：普通 `userToken` 调 `/bot/comment/chain` → HTTP 403；
- `B0-03`：`Bearer {{botToken}}` 调 `/bot/comment/history` → HTTP 200 + `body.code=200`；
- `B0-04`：裸 `{{botToken}}` 再调一次 → 同样成功，锁定 Bearer/裸 token 兼容。

`http-client.env.json` 和 `env.example.sh` 只增空值及变量名，不填真 token。`demo0/.gitignore` 增 `docs/api-test/cases/http-client.private.env.json`，并用 `git check-ignore` 确认后才将真 token 写入 private env。

- [x] **Step 2: 在 `07-bot.http` 加 D5/D6 契约组**

- `B1-01`：`chain?commentId={{botTriggerCommentId}}` 校验 comment/post/parent-chain 字段；
- `B1-02`：`tree?postId={{botTestContentId}}&pageNum=1&pageSize=50&sortType=asc` 校验稳定分页与时序；
- `B1-03`：`history?userId=10000...` 校验只返 bot 可见历史；
- `B1-04`：`content/sync?since=1970-01-01&pageNum=1&pageSize=50` 校验 `docId/docKind/status/updatedAt`；
- `B1-05`：用运营管理员 token upsert 一条带唯一 docId 的 policy doc，sync 能见 active；
- `B1-06`：删除同 docId，sync 能见 `status=deleted` 墓碑；
- `B1-07`：普通 user token 写 policy doc → 403。

- [x] **Step 3: 在 `07-bot.http` 加单条 E2E 组**

`B2-01` 用 `userToken` 提交：

```http
POST {{baseUrl}}/comment/send
authorization: {{userToken}}
Content-Type: application/json

{
  "contentId": {{botTestContentId}},
  "content": "@框框 E2E-{{$timestamp}} 请简短回复",
  "mentionBot": true
}
```

响应脚本把 `data` 写入 `botTriggerCommentId/BOT_TRIGGER_COMMENT_ID`。文件中用明确分隔注释要求等待异步链路完成后再跑 B2-02~B2-04：
- `B2-02`：用 bot token 查 chain，证明触发评论已机审可见；
- `B2-03`：用 user token 查 comment/reply list，找到 `replyUserId`/目标评论对应的 `userId=10000`回复，校验 `isBot=true`、`nickName="框框"`、`content` 以 `[框框·AI 学长]` 开头，写回 `botReplyCommentId`；
- `B2-04`：重复查询并断言该 trigger 只有 1 条 bot 回复。

HTTP Client 不做无界轮询；60s 内未可见就标 FAIL/BLOCKED，转 Task 13 五段证据定位。

- [x] **Step 4: 加 17 场景真链路回归区**

`07-bot.http` 增 `S01`~`S17` 请求，文本与 `QuantaBot/eval/cases/persona-01`~`persona-17` 的触发问句一一对应，但统一使用 `@框框`。每条记录：`triggerCommentId`、最终是否应答/静默、回复 `commentId`、Langfuse trace id、机审结果。

通过口径：
- 非红线场景：恰好一条可见 bot 回复，有 AI 标识；
- 注入/引战/高风险场景：按 eval 预期安全回复或静默，不回显内部 prompt/键名/工具名；
- 所有应答都经 demo0 机审；若二次机审进入 `MANUAL`，必须记录为不可见/待人工，不得算作全绿；任一条未完成都不勾 `M3-真联调回补`。

静态检查证据（2026-09-18）：`demo0/docs/api-test/cases/07-bot.http` 已包含 B0-01～B0-04、B1-01～B1-07、B2-01～B2-04 与 S01～S17。

真实执行回填（2026-09-19）：S01～S17 已逐条发起并完成决策/链路核对，但原单帖 run 结果为 `PARTIAL`，不能视为 17 场景全通过。模型分界为：S01～S05 的 decision 记录落在模型切换前，使用 `deepseek-v4-pro`；容器约在本地 08:51:10 重启并确认 `deepseek-v4-flash`。S06 的 trigger 在重启前提交，但其 `skipped_idempotent` decision 落在重启后；S07～S17 的处理均在重启后按 flash 档执行。首轮缺口为：S04 LLM 失败后按红线静默；S05、S06 被幂等状态拦截；S09 按决策静默；S14 生成回复进入 demo0 二次机审 `MANUAL/DONE`（medium/ad），`audit_status=0`，普通用户列表不可见。S14 的不可见来自同帖异步场景污染：回复混入后续“联系方式”后被 medium/ad 分流人工，不是配置错误；随后在独立 `contentId=69` 上复核 `386→387`，治理安全拒答未污染，trigger/reply 均 `ALIYUN/PASS/DONE`、`audit_status=1`，普通用户可见且身份为 `userId=10000,isBot=true,nickName=框框`。另在独立 `contentId=68` 上复核 S04 `388→389`，回复通过写库与二次机审并可见；S05/S06 的 flash 补跑 `382→384`、`383→385` 也已通过。补跑证明隔离 fixture 能跑通，但没有抹平原单帖记录，不能直接将总门改为 PASS。其余场景的实际 trigger/reply、决策、机审、Outbox/Inbox 证据按本轮验收记录回填；没有把静默、幂等拦截或人工审核中的回复冒充 PASS。

v4-flash 补跑（唯一文本 fixture，确认容器模型后执行）：S04-flash-rerun=`381`，`failed`，原因是 demo0 写库命中敏感词“外挂”后静默；触发评论 `ALIYUN/PASS/DONE`，`MODERATION_REQUESTED` 与 `BOT_MENTION_REQUESTED` Outbox 均 `SENT`，moderation Inbox=`SUCCESS`，无回复。S05=`382`→`384`、S06=`383`→`385` 均 `replied`/专业答疑且可见，触发/回复均 `ALIYUN/PASS/DONE`；触发 `MODERATION+BOT_MENTION`、回复 `MODERATION+NOTIFICATION` Outbox 均 `SENT`，对应 moderation/notification Inbox 均 `SUCCESS`（通知各 2 条），回复 `audit_status=1,userId=10000,isBot=true,nickName=框框`。另有隔离 S04=`388`→`389`，触发/回复均 `ALIYUN/PASS/DONE`、`audit_status=1`、可见。补跑证据已写入 `demo0/docs/api-test/RESULTS.md`，补跑本身通过但总状态仍为 `PARTIAL`。

最终门禁判定：不能将 17 场景真链路从 `PARTIAL` 改为 `PASS`，也不能勾选 `M3-真联调回补`。原因是 S04/S14 的通过证据来自独立帖子新事件，而原单帖 run 仍分别暴露了写库敏感词失败与同帖异步上下文污染；当前 `main_service` tree/history 读取没有 trigger-time waterline，尚未完成产品级修复后的原 17 场景复验。另有 17 条 Langfuse trace id 未完整落盘、明确违规文本机审驳回且无 `BOT_MENTION_REQUESTED` 的独立 fixture 未补。结论应写成“隔离补跑 PASS、总门 PARTIAL”，Task 11 继续 `DEFERRED`。

M4 Task 5（2026-09-19）已在 QuantaBot 侧补上 trigger-time waterline：pipeline 使用父链中触发节点的 `createTime` 与 `TriggerEvent.comment_id` 双重收口；时间缺失或不可解析时回退到 `comment_id <= trigger.comment_id`，未知后发楼层宁可丢弃。该改动不修改 demo0 契约；真实主服务原 17 场景仍需按下方轨道 A/B 重新复验，不能把本地单测 GREEN 写成真实联调 PASS。

**原始17场景跑法与正式重跑方案**：首轮 S01~S17 共用 `contentId=12`，是为了降低 fixture 成本并快速验证同帖 tree/history/记忆上下文；它不是正式的独立场景验收。S14 已证明该跑法会暴露同帖异步上下文污染，根因是 tree/history 读取缺少 trigger-time waterline。正式重跑必须分轨：

- **轨道 A（正式人格验收）**：准备 17 个独立、已审核的真实帖子/评论线程，固定主楼/父链/历史上下文；每条严格串行，等待上一条从 trigger 到 decision、回复/静默、触发与回复机审、Outbox/Inbox、普通用户可见性全部完成后再跑下一条，并保存每项证据。
- **轨道 B（并发/水位专项）**：另建同帖并发压测，专门验证 trigger-time waterline 与异步到达顺序；B 的污染/延迟结果不混入 17 场景人格验收，也不以 B 的结果替代轨道 A。

重跑前先修复或验证 waterline、固定帖子上下文并锁定模型/机审配置；轨道 A 全部闭环后，才允许把总门改为 `PASS` 并勾选 `M3-真联调回补`。在此之前，隔离补跑只能作为局部 PASS 证据，整体保持 `PARTIAL`，Task 11 继续 `DEFERRED`。

- [x] **Step 5: 建立并执行统一验收清单**

已写入 `demo0/docs/api-test/RESULTS.md` 的 Bot 主链路真实执行记录；各项按 `PASS/PARTIAL/BLOCKED` 区分。Task 11 小程序仍暂缓；S01～S17 原单帖 run 加上隔离/补跑后仍为 `PARTIAL`，不再写成“未执行”，也未冒充全量通过。

`RESULTS.md` 新增“Bot 主链路”章，至少含下表：

| 验收项 | 契约/改造 | 证据 | 状态 |
|---|---|---|---|
| MQ 拓扑 + Outbox Confirm | C-1 / D4 | `BOT_MENTION` Outbox=`SENT`；两次 `MODERATION` Outbox=`SENT` 且 demo0 moderation Inbox=`SUCCESS`；BOT_MENTION 由 QuantaBot 外部消费者处理，不写 demo0 Inbox | PASS |
| chain/history/tree | C-2 / D5 | B1-01~03，chain/tree/history 正常 | PASS |
| sync + policy + 墓碑 | C-3 / D6 | B1-04~B1-06b、B1-07 均通过；`ingest=156,deleted=53`；临时管理员权限清理 count=0 | PASS |
| @ 主判定 | C-4 / D3+D4 | trigger=347 的 `mentionBot`、Outbox 与 decision=`replied` | PASS |
| service token + 写库 + BOT 身份 | C-5 / D1+D2 | B0-01~B0-04 均通过（B0-02 HTTP 403/code 403）；reply=348 的 `userId=10000,isBot=true,nickName=框框` | PASS |
| 输出二次机审 | C-6 / D2 | trigger=347/reply=348 均为 `ALIYUN/PASS/DONE`，日志有真实 `AliyunTextModerationClient` | PASS |
| 控制面键名 | C-7 / D7 | 常量/代码回归通过；临时写入 Redis 三个控制键后，`/health` 在 6 秒内反映 `persona_version=task14-control-plane-proof`，清理后恢复默认值 | PASS |
| 小程序 @/AI 角标 | D3 | Task 11 暂缓，未做开发者工具/Network 验收 | BLOCKED（DEFERRED） |
| 17 场景真链路 | M3 回补 | 主轮 S01~S17 已执行；主轮 S04 LLM 失败、S05/S06 幂等拦截，S09 按决策静默；v4-flash/隔离补跑 S04、S05、S06、S14 均可见或按预期静默；但原单帖 S04 写库失败、S14 同帖异步污染，waterline、trace 与独立违规机审 fixture 仍缺 | PARTIAL |

执行后必须写实际日期、命令、用例数、trace id/截图路径；禁止预填 PASS。

- [x] **Step 6a: 回填 Phase 0 §5 已证实项**

在 `Phase0-契约谈判.md` 按证据逐项回填，文字必须与实现一致。本轮五项均已根据代码与真实后端验收证据回填；小程序仍 DEFERRED，17 场景虽已执行但为 PARTIAL，仍是独立回补门。
- [x] bot 昵称 = **框框**；C-4 文本 `@框框`，C-5 系统账号 user_id=10000；
- [x] service token = **1 年 JWT**，`tokenType=service`，只限 bot user_id；泄露后轮换 `JWT_USER_SECRET_KEY` 并重签；
- [x] bot 评论限流 = **6 次/60s**，scene=`comment-send-bot`；
- [x] 内容编辑 = 同 docId upsert，删除 = `status=deleted` 墓碑，bot 按稳定点 ID 删 Qdrant；B1-05/B1-06/B1-06b 已通过，临时管理员权限已清理；
- [x] AI 标识 = `BOT` 用户角色 + 评论 VO `isBot` 布尔，不在评论表重复存字段。

- [x] **Step 6b: 完成 policy upsert/墓碑管理路径**

B1-05 upsert、B1-06 delete、B1-06b 翻页发现 `status=deleted`、B1-07 普通用户权限边界均已通过并写入 `RESULTS.md`；临时管理员权限清理 count=0。

- [ ] **Step 7: 回填总计划（有证据才勾）**

`QuantaBot/总计划.md`：
- §1 P0-1~P0-7 全勾，状态改为“已实施并真联调验收”，链接 `RESULTS.md` Bot 章；
- M2 状态备注中的“真实 @ 回补”改为完成；
- `M3-真联调回补` 只在 S01~S17 全通过后勾选；当前隔离补跑已通过但原单帖总门仍为 PARTIAL，保持未勾；本轮从 S06 起使用 `deepseek-v4-flash`，切换前 S01~S05 使用 `deepseek-v4-pro`；
- §7 追加当日版本记录，列出 demo0 D1-D7、小程序 D3、框框改名与真链路证据。

如 17 场景未全通过：P0 可按对应契约逐项勾，但 `M3-真联调回补` 保持未勾并写明 PARTIAL 缺口。

- [x] **Step 8a-1: 后端/QuantaBot 回归证据回填**

```bash
mvn -f demo0/pom.xml test
cd QuantaBot && uv run ruff check src tests && uv run pytest tests/unit -q && uv run pytest tests/eval -q
```

已记录的最终回归证据：demo0 full Maven `115 tests / 0 failures / 1 skipped`；方法授权修复后的 targeted test `1 pass`；QuantaBot unit `153 passed`；default eval `11 passed / 17 skipped`；`QUANTABOT_EVAL=1` true eval `28 passed in 660.36s`。这些结果不等同于 S01~S17 真链路全通过。

真依赖回归已复跑通过：`QUANTABOT_INTEGRATION=1 uv run pytest tests/integration -q` 为 `12 passed`；首跑的 `2 fail/10 pass` 已定位为 publisher 错误地带 DLX 参数重声明已有生产队列，已改为被动获取现有 exchange/queue 后重跑通过。`app` 已恢复并健康。`07-bot.http` 的 B1-06b 已改为动态水位线（B1-05 保存当前时间回拨 120 秒后编码查询），静态检查通过。

真依赖档（已执行通过；以下命令留作重跑）：

```bash
cd QuantaBot
QUANTABOT_INTEGRATION=1 uv run pytest tests/integration -q
QUANTABOT_EVAL=1 uv run pytest tests/eval -q
```

跑前按 AGENTS 要求暂停 compose `app` 避免 integration 测试消费者抢队列，跑完立即恢复 `app`，然后再跑 `07-bot.http` 的 B2/S01~S17。

`promptfoo redteam` 配置与首份报告仍属于 `QuantaBot/总计划.md` 的 M4 发布门禁；当前未完成，不作为本轮后端联调已通过的依据。

- [x] **Step 8a-2: 独立审查复审（本轮后端范围）**

复审结论：无新 Critical/Important；C1/I1/I3/I4 已关闭，I2 认定为 M5 后续项，不构成本轮阻塞。`promptfoo redteam` 配置与首份报告仍属于 M4 发布门禁，当前未完成，不作为本轮后端联调已通过的依据。

独立审查重点：
1. HTTP/MQ/shared service 鉴权边界未把 `SecurityContext` 带入 listener/scheduler；
2. 每个“成功”都有真运行证据，无用单测代替联调；
3. `.env`、service token、API key 未入 diff/日志摘要；
4. 系统名 `QuantaBot` 和基础设施标识未被用户昵称替换。

- [ ] **Step 8b: 小程序回归** — `DEFERRED（用户于 2026-09-18 暂缓，小程序不纳入本轮后端联调验收）`

以下命令与微信开发者工具截图保留到 Task 11 恢复后执行：

```bash
npm --prefix demo0-miniprogram run test:bot-comment
npm --prefix demo0-miniprogram run typecheck
```

- [ ] **Step 9: 提交收口文档与 HTTP 套件**

```bash
git add demo0/.gitignore demo0/docs/api-test/cases/07-bot.http demo0/docs/api-test/cases/http-client.env.json demo0/docs/api-test/env.example.sh demo0/docs/api-test/TEST_PLAN.md demo0/docs/api-test/RESULTS.md QuantaBot/docs/plans/Phase0-契约谈判.md QuantaBot/总计划.md QuantaBot/docs/技术选型.md QuantaBot/AGENTS.md
git commit -m "docs(bot): record D1-D7 end-to-end acceptance and close Phase 0"
```

---

## 计划终稿自查（Task 1-14）

### 覆盖与依赖

- C-1~C-7 分别由 Task 5-7、8-10、1-3 实现，Task 13/14 统一验收；D1~D7 无空项。
- Task 11 只依赖 Task 3 的 DTO/VO 字段；本轮标记为 DEFERRED，不纳入后端联调验收；Task 12 可并行；Task 13 严格依赖服务端 Task 1-10 与 Task 12；Task 14 严格依赖 13 真跑证据。
- M3 真联调 checkbox 与单条冒烟分开：单条冒烟通过不代表 17 场景全通过。

### 执行前必须注意的四个风险

1. **同 broker 假连通**：demo0 旧默认是 `192.168.100.128:5672/admin`，compose broker 是宿主 `5673/quantabot`；Task 13 已用 Spring 环境变量强制统一。
2. **容器 localhost 陷阱**：QuantaBot app 在容器中访问 demo0 必须用 `host.docker.internal:9191`。
3. **机审假通过**：评论机审默认关闭且 disabled-policy=APPROVED；验收时必须显式开启并观察 0→1，否则不算“经过机审”。
4. **小程序 TS/JS 双轨**：当前仓库两者并存；Task 11 已要求成对修改和真机验收，本轮暂缓，恢复后再执行。

### 无占位符/类型一致结论

- 已区分代码/静态套件完成与真实联调门禁；密钥右值明确属于本地配置边界，不写入实施文档。
- `mentionBot` 一律为可选 Boolean/bool，`isBot` 从后端 VO 到前端 model 归一为 `boolean`。
- 用户可见名称一律为 `框框`，回复徽章一律为 `[框框·AI 学长]`；系统标识一律保留 `QuantaBot/QUANTABOT_/quantabot.*`。
